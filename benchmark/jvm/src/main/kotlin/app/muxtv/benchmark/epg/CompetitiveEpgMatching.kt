package app.muxtv.benchmark.epg

import java.text.Normalizer
import java.util.LinkedHashMap
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

data class EpgBenchmarkCandidate(
    val canonicalChannelId: String,
    val providerSourceId: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val rawName: String? = null,
) {
    init {
        require(canonicalChannelId.isNotBlank())
        require(providerSourceId.isNotBlank())
    }
}

data class EpgBenchmarkQuery(
    val providerSourceId: String,
    val epgExternalId: String?,
    val epgDisplayName: String,
) {
    init {
        require(providerSourceId.isNotBlank())
        require(epgDisplayName.isNotBlank())
    }
}

enum class EpgBenchmarkDecision {
    AUTO,
    REVIEW,
    AMBIGUOUS,
    UNRESOLVED,
}

enum class EpgBenchmarkReason {
    EXACT_ID,
    EXACT_TVG_NAME,
    EXACT_RAW_NAME,
    NORMALIZED_ALIAS_EXACT,
    NORMALIZED_ALIAS_AMBIGUOUS,
    FUZZY_AUTO,
    FUZZY_REVIEW,
    OWNTV_REFERENCE_AUTO,
    OWNTV_REFERENCE_REVIEW,
    NO_MATCH,
}

data class EpgRankedCandidate(
    val canonicalChannelId: String,
    val score: Double,
)

data class EpgBenchmarkMatch(
    val decision: EpgBenchmarkDecision,
    val reason: EpgBenchmarkReason,
    val canonicalChannelId: String? = null,
    val ranking: List<EpgRankedCandidate> = emptyList(),
) {
    init {
        require((decision == EpgBenchmarkDecision.AUTO) == (canonicalChannelId != null))
    }
}

data class HybridThresholds(
    val autoThreshold: Double = 0.95,
    val reviewThreshold: Double = 0.90,
    val ambiguityMargin: Double = 0.03,
) {
    init {
        require(autoThreshold in 0.0..1.0)
        require(reviewThreshold in 0.0..autoThreshold)
        require(ambiguityMargin in 0.0..1.0)
    }
}

/**
 * Benchmark-only reproduction of MuxTV main@9daac297 exact matching semantics.
 * It is deliberately isolated from production Room publication code.
 */
class MuxBaselineEpgMatcher(
    candidates: List<EpgBenchmarkCandidate>,
    providerSourceId: String,
) {
    private val exact = ExactEvidenceIndex(candidates, providerSourceId)

    fun match(query: EpgBenchmarkQuery): EpgBenchmarkMatch = exact.match(query) ?: unresolved()
}

/**
 * Benchmark candidate B. Exact MuxTV evidence always wins. Fuzzy work is only reached for unresolved
 * rows and is never intended for canonical multi-provider merge.
 */
class HybridEpgMatcher(
    candidates: List<EpgBenchmarkCandidate>,
    providerSourceId: String,
    val thresholds: HybridThresholds = HybridThresholds(),
    normalizationCacheMaxEntries: Int = DEFAULT_NORMALIZATION_CACHE_MAX,
) {
    private val exact = ExactEvidenceIndex(candidates, providerSourceId)
    private val normalizer = BoundedNormalizer(normalizationCacheMaxEntries, ::normalizeHybridUncached)
    private val prepared: List<HybridPreparedCandidate> = prepareCandidates(candidates, providerSourceId)

    fun match(query: EpgBenchmarkQuery): EpgBenchmarkMatch {
        exact.match(query)?.let { return it }

        val target = normalizer.normalize(query.epgDisplayName)
        if (target.isEmpty()) return unresolved()
        val targetDigits = digitRuns(target)
        val targetTokens = tokenSet(target)

        val bestByCanonical = LinkedHashMap<String, Double>()
        prepared.forEach { candidate ->
            var score = 0.0
            candidate.names.forEach { name ->
                score = max(
                    score,
                    scoreHybridNormalized(
                        target,
                        name.normalized,
                        targetDigits,
                        name.digitRuns,
                        targetTokens,
                        name.tokens,
                    ),
                )
            }
            val previous = bestByCanonical[candidate.canonicalChannelId]
            if (previous == null || score > previous) {
                bestByCanonical[candidate.canonicalChannelId] = score
            }
        }

        val ranking = bestByCanonical.entries
            .map { EpgRankedCandidate(it.key, it.value) }
            .sortedWith(compareByDescending<EpgRankedCandidate> { it.score }.thenBy { it.canonicalChannelId })
        if (ranking.isEmpty()) return unresolved()

        val top = ranking.first()
        val tiedPerfect = ranking.count { it.score == 1.0 }
        if (top.score == 1.0) {
            return if (tiedPerfect == 1) {
                EpgBenchmarkMatch(
                    decision = EpgBenchmarkDecision.AUTO,
                    reason = EpgBenchmarkReason.NORMALIZED_ALIAS_EXACT,
                    canonicalChannelId = top.canonicalChannelId,
                    ranking = ranking,
                )
            } else {
                EpgBenchmarkMatch(
                    decision = EpgBenchmarkDecision.AMBIGUOUS,
                    reason = EpgBenchmarkReason.NORMALIZED_ALIAS_AMBIGUOUS,
                    ranking = ranking,
                )
            }
        }

        val secondScore = ranking.getOrNull(1)?.score ?: 0.0
        if (top.score >= thresholds.autoThreshold && top.score - secondScore >= thresholds.ambiguityMargin) {
            return EpgBenchmarkMatch(
                decision = EpgBenchmarkDecision.AUTO,
                reason = EpgBenchmarkReason.FUZZY_AUTO,
                canonicalChannelId = top.canonicalChannelId,
                ranking = ranking,
            )
        }
        if (top.score >= thresholds.reviewThreshold) {
            return EpgBenchmarkMatch(
                decision = EpgBenchmarkDecision.REVIEW,
                reason = EpgBenchmarkReason.FUZZY_REVIEW,
                ranking = ranking,
            )
        }
        return unresolved(ranking)
    }

    fun normalizationCacheEntries(): Int = normalizer.size()

    private fun prepareCandidates(
        candidates: List<EpgBenchmarkCandidate>,
        providerSourceId: String,
    ): List<HybridPreparedCandidate> = candidates
        .asSequence()
        .filter { it.providerSourceId == providerSourceId }
        .map { candidate ->
            val names = listOfNotNull(candidate.tvgName, candidate.rawName)
                .distinct()
                .map { raw ->
                    val normalized = normalizer.normalize(raw)
                    PreparedName(
                        normalized = normalized,
                        digitRuns = digitRuns(normalized),
                        tokens = tokenSet(normalized),
                    )
                }
            HybridPreparedCandidate(candidate.canonicalChannelId, names)
        }
        .toList()

    companion object {
        const val DEFAULT_NORMALIZATION_CACHE_MAX: Int = 4_096
        private const val DIGIT_MISMATCH_CAP: Double = 0.55
        private const val DIGIT_MISSING_CAP: Double = 0.88
        private const val STRICT_TOKEN_CONTAINMENT_CAP: Double = 0.90

        internal fun normalizeForHybrid(raw: String): String = normalizeHybridUncached(raw)

        internal fun scoreHybridNormalized(a: String, b: String): Double = scoreHybridNormalized(
            a,
            b,
            digitRuns(a),
            digitRuns(b),
            tokenSet(a),
            tokenSet(b),
        )

        private fun scoreHybridNormalized(
            a: String,
            b: String,
            aDigits: List<String>,
            bDigits: List<String>,
            aTokens: Set<String>,
            bTokens: Set<String>,
        ): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            if (a == b) return 1.0

            var score = max(jaroWinkler(a, b), tokenDice(aTokens, bTokens))
            if (aDigits != bDigits) {
                score = min(
                    score,
                    if (aDigits.isNotEmpty() && bDigits.isNotEmpty()) DIGIT_MISMATCH_CAP else DIGIT_MISSING_CAP,
                )
            }
            if (aTokens != bTokens && (aTokens.containsAll(bTokens) || bTokens.containsAll(aTokens))) {
                score = min(score, STRICT_TOKEN_CONTAINMENT_CAP)
            }
            return score
        }
    }
}

/** OwnTV_Core EpgMatcher behavior, pinned to main@630e9c09, as a benchmark reference only. */
class OwnTvReferenceEpgMatcher(
    candidates: List<EpgBenchmarkCandidate>,
    providerSourceId: String,
    normalizationCacheMaxEntries: Int = 4_096,
) {
    private val exact = ExactEvidenceIndex(candidates, providerSourceId, idOnly = true)
    private val normalizer = BoundedNormalizer(normalizationCacheMaxEntries, ::normalizeOwnTvUncached)
    private val prepared = candidates
        .asSequence()
        .filter { it.providerSourceId == providerSourceId }
        .mapIndexed { index, candidate ->
            OwnTvPreparedCandidate(
                canonicalChannelId = candidate.canonicalChannelId,
                stableOrder = index,
                normalizedNames = listOfNotNull(candidate.tvgName, candidate.rawName)
                    .distinct()
                    .map(normalizer::normalize),
                normalizedId = candidate.tvgId?.let(normalizer::normalize).orEmpty(),
            )
        }
        .toList()

    fun match(query: EpgBenchmarkQuery): EpgBenchmarkMatch {
        exact.match(query)?.let { return it }
        val target = normalizer.normalize(query.epgDisplayName)
        if (target.isEmpty()) return unresolved()
        val targetDigits = digitRuns(target)

        val bestByCanonical = LinkedHashMap<String, Pair<Double, Int>>()
        prepared.forEach { candidate ->
            var score = 0.0
            candidate.normalizedNames.forEach { normalized ->
                score = max(score, scoreOwnTvNormalized(target, normalized, targetDigits, digitRuns(normalized)))
            }
            if (candidate.normalizedId.isNotEmpty()) {
                score = max(
                    score,
                    scoreOwnTvNormalized(target, candidate.normalizedId, targetDigits, digitRuns(candidate.normalizedId)),
                )
            }
            val previous = bestByCanonical[candidate.canonicalChannelId]
            if (previous == null || score > previous.first) {
                bestByCanonical[candidate.canonicalChannelId] = score to candidate.stableOrder
            }
        }

        val ranking = bestByCanonical.entries
            .sortedWith(compareBy<Map.Entry<String, Pair<Double, Int>>> { it.value.second })
            .sortedByDescending { it.value.first }
            .map { EpgRankedCandidate(it.key, it.value.first) }

        val top = ranking.firstOrNull() ?: return unresolved()
        return when {
            top.score >= AUTO_THRESHOLD -> EpgBenchmarkMatch(
                decision = EpgBenchmarkDecision.AUTO,
                reason = EpgBenchmarkReason.OWNTV_REFERENCE_AUTO,
                canonicalChannelId = top.canonicalChannelId,
                ranking = ranking,
            )
            top.score >= REVIEW_THRESHOLD -> EpgBenchmarkMatch(
                decision = EpgBenchmarkDecision.REVIEW,
                reason = EpgBenchmarkReason.OWNTV_REFERENCE_REVIEW,
                ranking = ranking,
            )
            else -> unresolved(ranking)
        }
    }

    fun normalizationCacheEntries(): Int = normalizer.size()

    companion object {
        const val AUTO_THRESHOLD: Double = 0.92
        const val REVIEW_THRESHOLD: Double = 0.74
    }
}

private class ExactEvidenceIndex(
    candidates: List<EpgBenchmarkCandidate>,
    providerSourceId: String,
    private val idOnly: Boolean = false,
) {
    private val byId = buildIndex(candidates, providerSourceId, { it.tvgId }, ::normalizeProviderId)
    private val byTvgName = if (idOnly) emptyMap() else
        buildIndex(candidates, providerSourceId, { it.tvgName }, ::normalizeDisplayName)
    private val byRawName = if (idOnly) emptyMap() else
        buildIndex(candidates, providerSourceId, { it.rawName }, ::normalizeDisplayName)

    fun match(query: EpgBenchmarkQuery): EpgBenchmarkMatch? {
        normalizeProviderId(query.epgExternalId)?.let { key ->
            collapse(byId[key].orEmpty(), EpgBenchmarkReason.EXACT_ID)?.let { return it }
        }
        if (idOnly) return null
        normalizeDisplayName(query.epgDisplayName)?.let { key ->
            collapse(byTvgName[key].orEmpty(), EpgBenchmarkReason.EXACT_TVG_NAME)?.let { return it }
            collapse(byRawName[key].orEmpty(), EpgBenchmarkReason.EXACT_RAW_NAME)?.let { return it }
        }
        return null
    }

    private fun collapse(ids: Set<String>, reason: EpgBenchmarkReason): EpgBenchmarkMatch? = when (ids.size) {
        0 -> null
        1 -> EpgBenchmarkMatch(
            decision = EpgBenchmarkDecision.AUTO,
            reason = reason,
            canonicalChannelId = ids.first(),
            ranking = listOf(EpgRankedCandidate(ids.first(), 1.0)),
        )
        else -> EpgBenchmarkMatch(
            decision = EpgBenchmarkDecision.AMBIGUOUS,
            reason = reason,
            ranking = ids.map { EpgRankedCandidate(it, 1.0) },
        )
    }

    companion object {
        private fun buildIndex(
            candidates: List<EpgBenchmarkCandidate>,
            providerSourceId: String,
            value: (EpgBenchmarkCandidate) -> String?,
            normalize: (String?) -> String?,
        ): Map<String, Set<String>> {
            val result = LinkedHashMap<String, MutableSet<String>>()
            candidates.forEach { candidate ->
                if (candidate.providerSourceId != providerSourceId) return@forEach
                val key = normalize(value(candidate)) ?: return@forEach
                result.getOrPut(key) { linkedSetOf() } += candidate.canonicalChannelId
            }
            return result
        }
    }
}

private data class PreparedName(
    val normalized: String,
    val digitRuns: List<String>,
    val tokens: Set<String>,
)

private data class HybridPreparedCandidate(
    val canonicalChannelId: String,
    val names: List<PreparedName>,
)

private data class OwnTvPreparedCandidate(
    val canonicalChannelId: String,
    val stableOrder: Int,
    val normalizedNames: List<String>,
    val normalizedId: String,
)

private class BoundedNormalizer(
    private val maxEntries: Int,
    private val compute: (String) -> String,
) {
    init {
        require(maxEntries > 0)
    }

    private val cache = object : LinkedHashMap<String, String>(min(512, maxEntries), 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > maxEntries
    }

    fun normalize(raw: String): String = synchronized(cache) {
        cache[raw] ?: compute(raw).also { cache[raw] = it }
    }

    fun size(): Int = synchronized(cache) { cache.size }
}

private fun normalizeProviderId(value: String?): String? = value
    ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
    ?.trim(::isMatchWhitespace)
    ?.takeIf(String::isNotEmpty)

private fun normalizeDisplayName(value: String?): String? {
    if (value == null) return null
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    val collapsed = buildString(normalized.length) {
        var pendingSpace = false
        normalized.forEach { character ->
            if (isMatchWhitespace(character)) {
                if (isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }
    return collapsed.takeIf(String::isNotEmpty)?.lowercase(Locale.ROOT)
}

private fun isMatchWhitespace(character: Char): Boolean = character.isWhitespace() || Character.isSpaceChar(character)

private val BRACKETS = Regex("[\\[({][^\\])}]*[\\])}]")
private val CODEC = Regex("\\b(?:h[.\\s]?26[45]|x26[45])\\b", RegexOption.IGNORE_CASE)
private val SEPARATORS = Regex("[._\\-:|/+]")
private val NON_ALNUM = Regex("[^\\p{L}\\p{N} ]")
private val SPACES = Regex("\\s+")
private val DIGIT_RUN = Regex("\\p{N}+")

private val NOISE = setOf(
    "hd", "fhd", "uhd", "sd", "4k", "8k", "hq", "lq", "hevc", "h265", "h264", "x265", "x264",
    "fps", "raw", "vip", "backup", "feed", "alt", "1080p", "1080", "720p", "720", "480p", "540p",
    "2160p", "fullhd", "multi", "multisub", "latino",
)

private val NUMBER_WORDS = mapOf(
    "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
    "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
)

private val HYBRID_COUNTRY_ALIASES = mapOf(
    "us" to "us", "usa" to "us", "america" to "us",
    "uk" to "uk", "gb" to "uk", "england" to "uk",
    "fr" to "fr", "france" to "fr",
    "de" to "de", "germany" to "de", "deutschland" to "de",
    "ru" to "ru", "russia" to "ru",
    "gr" to "gr", "greece" to "gr",
    "jp" to "jp", "japan" to "jp",
    "cn" to "cn", "china" to "cn",
    "ae" to "ae", "uae" to "ae",
    "sa" to "sa", "arabia" to "sa",
    "es" to "es", "spain" to "es",
    "it" to "it", "italy" to "it",
    "ca" to "ca", "canada" to "ca",
    "ie" to "ie", "ireland" to "ie",
    "at" to "at", "austria" to "at",
    "ch" to "ch", "switzerland" to "ch",
    "nl" to "nl", "netherlands" to "nl",
    "pt" to "pt", "portugal" to "pt",
    "pl" to "pl", "poland" to "pl",
    "ua" to "ua", "ukraine" to "ua",
    "tr" to "tr", "turkey" to "tr",
    "br" to "br", "brazil" to "br",
    "mx" to "mx", "mexico" to "mx",
    "au" to "au", "australia" to "au",
    "nz" to "nz", "newzealand" to "nz",
)

private val OWNTV_COUNTRY = setOf(
    "us", "uk", "ca", "au", "nz", "ie", "za", "in", "pk", "de", "at", "ch", "fr", "es", "pt", "it",
    "nl", "be", "lu", "pl", "cz", "sk", "hu", "ro", "bg", "gr", "tr", "ru", "ua", "se", "no", "dk", "fi",
    "is", "ee", "lv", "lt", "hr", "rs", "si", "br", "mx", "ar", "cl", "co", "pe", "ve", "ae", "sa", "qa",
    "eg", "usa", "america", "canada", "australia", "ireland", "england", "scotland", "wales", "france", "germany",
    "deutschland", "austria", "switzerland", "spain", "espana", "italy", "italia", "portugal", "netherlands",
    "holland", "belgium", "poland", "polska", "czech", "czechia", "slovakia", "hungary", "romania", "bulgaria",
    "greece", "turkey", "turkiye", "russia", "ukraine", "sweden", "norway", "denmark", "finland", "iceland",
    "croatia", "serbia", "slovenia", "brazil", "brasil", "mexico", "argentina", "chile", "colombia", "peru",
    "venezuela", "egypt", "arabia", "arabic",
)

private fun normalizeHybridUncached(raw: String): String {
    var value = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    value = value.replace(CODEC, " ")
    value = value.replace(BRACKETS, " ")
    value = value.replace(SEPARATORS, " ")
    value = value.replace(NON_ALNUM, " ")
    val tokens = value.split(SPACES)
        .filter(String::isNotBlank)
        .map(::canonicalizeUnicodeDigits)
        .map { NUMBER_WORDS[it] ?: it }
        .filterNot { it in NOISE }
        .map { HYBRID_COUNTRY_ALIASES[it] ?: it }
    return tokens.joinToString(" ").trim()
}

private fun normalizeOwnTvUncached(raw: String): String {
    var value = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    value = value.replace(BRACKETS, " ")
    value = value.replace(SEPARATORS, " ")
    value = value.replace(NON_ALNUM, " ")
    val tokens = value.split(SPACES)
        .filter { it.isNotBlank() && it !in NOISE }
        .map { NUMBER_WORDS[it] ?: it }
        .toMutableList()

    fun canDropCountryAt(index: Int): Boolean = tokens.size > 1 && tokens[index] in OWNTV_COUNTRY &&
        tokens.filterIndexed { i, _ -> i != index }.any { token -> token.any(Char::isLetter) }

    if (tokens.isNotEmpty() && canDropCountryAt(0)) tokens.removeAt(0)
    if (tokens.isNotEmpty() && canDropCountryAt(tokens.lastIndex)) tokens.removeAt(tokens.lastIndex)
    return tokens.joinToString(" ").trim()
}

private fun canonicalizeUnicodeDigits(value: String): String = buildString(value.length) {
    value.forEach { character ->
        if (character.isDigit()) {
            val digit = Character.digit(character, 10)
            append(if (digit >= 0) Character.forDigit(digit, 10) else character)
        } else {
            append(character)
        }
    }
}

private fun digitRuns(value: String): List<String> = DIGIT_RUN.findAll(value)
    .map { match -> canonicalizeUnicodeDigits(match.value).trimStart('0').ifEmpty { "0" } }
    .toList()
    .sorted()

private fun tokenSet(value: String): Set<String> = value.split(' ').filter(String::isNotBlank).toSet()

private fun scoreOwnTvNormalized(
    a: String,
    b: String,
    aDigits: List<String>,
    bDigits: List<String>,
): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    if (a == b) return 1.0
    var score = max(jaroWinkler(a, b), tokenDice(tokenSet(a), tokenSet(b)))
    if (aDigits != bDigits) {
        score = min(score, if (aDigits.isNotEmpty() && bDigits.isNotEmpty()) 0.60 else 0.90)
    }
    return score
}

internal fun jaroWinkler(a: String, b: String): Double {
    if (a == b) return 1.0
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val jaro = jaro(a, b)
    var prefix = 0
    val maxPrefix = min(4, min(a.length, b.length))
    while (prefix < maxPrefix && a[prefix] == b[prefix]) prefix++
    return jaro + prefix * 0.1 * (1.0 - jaro)
}

private fun jaro(a: String, b: String): Double {
    val matchDistance = max(0, max(a.length, b.length) / 2 - 1)
    val aMatches = BooleanArray(a.length)
    val bMatches = BooleanArray(b.length)
    var matches = 0
    for (i in a.indices) {
        val start = max(0, i - matchDistance)
        val end = min(i + matchDistance + 1, b.length)
        for (j in start until end) {
            if (bMatches[j] || a[i] != b[j]) continue
            aMatches[i] = true
            bMatches[j] = true
            matches++
            break
        }
    }
    if (matches == 0) return 0.0

    var transpositions = 0
    var k = 0
    for (i in a.indices) {
        if (!aMatches[i]) continue
        while (!bMatches[k]) k++
        if (a[i] != b[k]) transpositions++
        k++
    }
    val matched = matches.toDouble()
    return (matched / a.length + matched / b.length + (matched - transpositions / 2.0) / matched) / 3.0
}

private fun tokenDice(a: Set<String>, b: Set<String>): Double {
    if (a.isEmpty() || b.isEmpty()) return 0.0
    val intersection = a.count { it in b }
    return 2.0 * intersection / (a.size + b.size)
}

private fun unresolved(ranking: List<EpgRankedCandidate> = emptyList()): EpgBenchmarkMatch = EpgBenchmarkMatch(
    decision = EpgBenchmarkDecision.UNRESOLVED,
    reason = EpgBenchmarkReason.NO_MATCH,
    ranking = ranking,
)
