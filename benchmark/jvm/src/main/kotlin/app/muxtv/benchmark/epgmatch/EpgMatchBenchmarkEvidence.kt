package app.muxtv.benchmark.epgmatch

import app.muxtv.benchmark.competitive.CompetitiveCorrectness
import app.muxtv.benchmark.competitive.CompetitiveDriverKind
import app.muxtv.benchmark.competitive.CompetitiveScenario
import app.muxtv.benchmark.competitive.CompetitiveScenarioVariant
import app.muxtv.benchmark.competitive.CompetitiveVariant
import app.muxtv.benchmark.competitive.CorpusProvenance
import app.muxtv.benchmark.competitive.RepositoryPin
import app.muxtv.benchmark.epg.EpgBenchmarkCandidate
import app.muxtv.benchmark.epg.EpgBenchmarkMatch
import app.muxtv.benchmark.epg.EpgBenchmarkQuery
import app.muxtv.benchmark.epg.EpgRankedCandidate
import app.muxtv.benchmark.epg.HybridEpgMatcher
import app.muxtv.benchmark.epg.HybridThresholds
import app.muxtv.benchmark.epg.MuxBaselineEpgMatcher
import app.muxtv.benchmark.epg.OwnTvReferenceEpgMatcher
import java.security.MessageDigest
import java.util.Locale

typealias EpgBenchmarkDecision = app.muxtv.benchmark.epg.EpgBenchmarkDecision
typealias EpgBenchmarkReason = app.muxtv.benchmark.epg.EpgBenchmarkReason

enum class EpgBenchmarkVariant {
    A_CURRENT_EXACT,
    B_HYBRID,
    C_OWNTV_REFERENCE,
}

data class EpgLabeledCase(
    val id: String,
    val tags: Set<String>,
    val query: EpgBenchmarkQuery,
    val candidates: List<EpgBenchmarkCandidate>,
    val expectedCanonicalIds: Set<String>,
    val autoAllowed: Boolean,
    val reviewExpected: Boolean,
)

interface EpgLabeledCorpus {
    val cases: List<EpgLabeledCase>
    val sha256: String
    val manifestSha256: String
}

object EpgMatchAdversarialCorpus : EpgLabeledCorpus {
    private val loaded: LoadedCorpus by lazy(::loadCorpus)

    override val cases: List<EpgLabeledCase>
        get() = loaded.cases

    override val sha256: String
        get() = loaded.contentSha256

    override val manifestSha256: String
        get() = loaded.manifestSha256

    private fun loadCorpus(): LoadedCorpus {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/epg-matching-corpus-v1.tsv")) {
            "missing epg-matching-corpus-v1.tsv"
        }.use { it.readBytes() }
        val text = bytes.toString(Charsets.UTF_8)
        val rows = text.lineSequence()
            .filter(String::isNotBlank)
            .toList()
        require(rows.isNotEmpty()) { "EPG corpus is empty" }
        require(rows.first() == EXPECTED_HEADER) { "unexpected EPG corpus header" }

        val grouped = linkedMapOf<String, MutableList<CorpusRow>>()
        rows.drop(1).forEachIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == 14) { "invalid EPG corpus row ${index + 2}: expected 14 fields" }
            val row = CorpusRow(
                scenarioId = fields[0],
                tags = fields[1].split(',').filter(String::isNotBlank).toSet(),
                providerSource = fields[2],
                epgExternalId = fields[3].nullableField(),
                epgDisplayName = fields[4],
                candidateSource = fields[5],
                candidateId = fields[6],
                tvgId = fields[7].nullableField(),
                tvgName = fields[8].nullableField(),
                rawName = fields[9].nullableField(),
                expectedIds = fields[10].split(',').filter { it.isNotBlank() && it != "-" }.toSet(),
                autoAllowed = fields[11].toBooleanStrict(),
                reviewExpected = fields[12].toBooleanStrict(),
            )
            require(row.scenarioId.matches(TOKEN_PATTERN)) { "invalid scenario id ${row.scenarioId}" }
            require(row.tags.isNotEmpty()) { "scenario ${row.scenarioId} has no tags" }
            grouped.getOrPut(row.scenarioId) { mutableListOf() } += row
        }

        val cases = grouped.map { (scenarioId, scenarioRows) ->
            val first = scenarioRows.first()
            scenarioRows.forEach { row ->
                require(row.providerSource == first.providerSource) { "$scenarioId changes provider source" }
                require(row.epgExternalId == first.epgExternalId) { "$scenarioId changes EPG external id" }
                require(row.epgDisplayName == first.epgDisplayName) { "$scenarioId changes EPG display name" }
                require(row.expectedIds == first.expectedIds) { "$scenarioId changes expected IDs" }
                require(row.autoAllowed == first.autoAllowed) { "$scenarioId changes auto policy" }
                require(row.reviewExpected == first.reviewExpected) { "$scenarioId changes review policy" }
                require(row.tags == first.tags) { "$scenarioId changes tags" }
            }
            EpgLabeledCase(
                id = scenarioId,
                tags = first.tags,
                query = EpgBenchmarkQuery(
                    providerSourceId = first.providerSource,
                    epgExternalId = first.epgExternalId,
                    epgDisplayName = first.epgDisplayName,
                ),
                candidates = scenarioRows.map { row ->
                    EpgBenchmarkCandidate(
                        canonicalChannelId = row.candidateId,
                        providerSourceId = row.candidateSource,
                        tvgId = row.tvgId,
                        tvgName = row.tvgName,
                        rawName = row.rawName,
                    )
                },
                expectedCanonicalIds = first.expectedIds,
                autoAllowed = first.autoAllowed,
                reviewExpected = first.reviewExpected,
            )
        }
        require(cases.map(EpgLabeledCase::id).distinct().size == cases.size)

        val contentSha = sha256(bytes)
        val manifestSha = sha256(
            "epg-matching-corpus-v1.tsv\tschema=1\tcases=${cases.size}\tcontent=$contentSha"
                .toByteArray(Charsets.UTF_8),
        )
        return LoadedCorpus(cases, contentSha, manifestSha)
    }

    private const val EXPECTED_HEADER =
        "scenario_id\ttags\tprovider_source\tepg_external_id\tepg_display_name\tcandidate_source\t" +
            "candidate_id\ttvg_id\ttvg_name\traw_name\texpected_ids\tauto_allowed\treview_expected\tnote"
    private val TOKEN_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
}

data class EpgCaseOutcome(
    val caseId: String,
    val decision: EpgBenchmarkDecision,
    val reason: EpgBenchmarkReason,
    val canonicalChannelId: String?,
    val ranking: List<EpgRankedCandidate>,
) {
    val autoCanonicalId: String?
        get() = canonicalChannelId.takeIf { decision == EpgBenchmarkDecision.AUTO }
}

data class EpgBenchmarkMetrics(
    val totalCases: Int,
    val automaticMatches: Int,
    val correctAutomaticMatches: Int,
    val falseAutomaticMatches: Int,
    val autoPrecision: Double,
    val autoRecall: Double,
    val ambiguityReviewRate: Double,
    val meanReciprocalRank: Double,
    val top1HitRate: Double,
    val top3HitRate: Double,
    val unresolvedRate: Double,
)

data class EpgBenchmarkEvaluation(
    val variant: EpgBenchmarkVariant,
    val metrics: EpgBenchmarkMetrics,
    val outcomes: List<EpgCaseOutcome>,
    val digestSha256: String,
) {
    fun outcome(caseId: String): EpgCaseOutcome = outcomes.single { it.caseId == caseId }
}

data class EpgHybridThresholds(
    val autoThreshold: Double,
    val reviewThreshold: Double,
    val autoMargin: Double,
) {
    init {
        require(autoThreshold in 0.0..1.0)
        require(reviewThreshold in 0.0..autoThreshold)
        require(autoMargin in 0.0..1.0)
    }

    internal fun toMatcherThresholds(): HybridThresholds = HybridThresholds(
        autoThreshold = autoThreshold,
        reviewThreshold = reviewThreshold,
        ambiguityMargin = autoMargin,
    )
}

object EpgMatchEvaluator {
    fun evaluate(
        variant: EpgBenchmarkVariant,
        corpus: EpgLabeledCorpus,
        hybridThresholds: EpgHybridThresholds = EpgHybridThresholds(0.98, 0.86, 0.08),
    ): EpgBenchmarkEvaluation {
        val outcomes = corpus.cases.map { case ->
            val match = when (variant) {
                EpgBenchmarkVariant.A_CURRENT_EXACT -> MuxBaselineEpgMatcher(
                    candidates = case.candidates,
                    providerSourceId = case.query.providerSourceId,
                ).match(case.query)
                EpgBenchmarkVariant.B_HYBRID -> HybridEpgMatcher(
                    candidates = case.candidates,
                    providerSourceId = case.query.providerSourceId,
                    thresholds = hybridThresholds.toMatcherThresholds(),
                ).match(case.query)
                EpgBenchmarkVariant.C_OWNTV_REFERENCE -> OwnTvReferenceEpgMatcher(
                    candidates = case.candidates,
                    providerSourceId = case.query.providerSourceId,
                ).match(case.query)
            }
            match.toOutcome(case.id)
        }
        val metrics = calculateMetrics(corpus.cases, outcomes)
        return EpgBenchmarkEvaluation(
            variant = variant,
            metrics = metrics,
            outcomes = outcomes,
            digestSha256 = digestEvaluation(variant, metrics, outcomes),
        )
    }

    private fun calculateMetrics(
        cases: List<EpgLabeledCase>,
        outcomes: List<EpgCaseOutcome>,
    ): EpgBenchmarkMetrics {
        require(cases.size == outcomes.size)
        val paired = cases.zip(outcomes)
        val automatic = paired.filter { (_, outcome) -> outcome.decision == EpgBenchmarkDecision.AUTO }
        val correctAutomatic = automatic.count { (case, outcome) ->
            case.autoAllowed && outcome.canonicalChannelId in case.expectedCanonicalIds
        }
        val falseAutomatic = automatic.size - correctAutomatic
        val autoEligible = cases.count(EpgLabeledCase::autoAllowed)
        val rankingEligible = cases.count { it.expectedCanonicalIds.isNotEmpty() }
        val reciprocalRankSum = paired.sumOf { (case, outcome) ->
            if (case.expectedCanonicalIds.isEmpty()) {
                0.0
            } else {
                val index = outcome.ranking.indexOfFirst { it.canonicalChannelId in case.expectedCanonicalIds }
                if (index < 0) 0.0 else 1.0 / (index + 1).toDouble()
            }
        }
        val top1Hits = paired.count { (case, outcome) ->
            case.expectedCanonicalIds.isNotEmpty() &&
                outcome.ranking.take(1).any { it.canonicalChannelId in case.expectedCanonicalIds }
        }
        val top3Hits = paired.count { (case, outcome) ->
            case.expectedCanonicalIds.isNotEmpty() &&
                outcome.ranking.take(3).any { it.canonicalChannelId in case.expectedCanonicalIds }
        }
        val reviewOrAmbiguous = outcomes.count {
            it.decision == EpgBenchmarkDecision.REVIEW || it.decision == EpgBenchmarkDecision.AMBIGUOUS
        }
        val unresolved = outcomes.count { it.decision == EpgBenchmarkDecision.UNRESOLVED }
        return EpgBenchmarkMetrics(
            totalCases = cases.size,
            automaticMatches = automatic.size,
            correctAutomaticMatches = correctAutomatic,
            falseAutomaticMatches = falseAutomatic,
            autoPrecision = ratio(correctAutomatic, automatic.size, emptyValue = 1.0),
            autoRecall = ratio(correctAutomatic, autoEligible),
            ambiguityReviewRate = ratio(reviewOrAmbiguous, cases.size),
            meanReciprocalRank = if (rankingEligible == 0) 0.0 else reciprocalRankSum / rankingEligible,
            top1HitRate = ratio(top1Hits, rankingEligible),
            top3HitRate = ratio(top3Hits, rankingEligible),
            unresolvedRate = ratio(unresolved, cases.size),
        )
    }
}

enum class EpgThresholdSelectionSource { CORPUS_SWEEP }

data class EpgThresholdSweepResult(
    val autoThresholds: List<Double>,
    val reviewThresholds: List<Double>,
    val autoMargins: List<Double>,
    val selectedThresholds: EpgHybridThresholds,
    val selectedEvaluation: EpgBenchmarkEvaluation,
    val selectionSource: EpgThresholdSelectionSource,
    val referenceAutoThreshold: Double,
    val referenceReviewThreshold: Double,
    val evaluatedConfigurations: Int,
    val reportDigest: String,
)

object EpgMatchThresholdSweep {
    val AUTO_THRESHOLDS: List<Double> = listOf(0.90, 0.91, 0.92, 0.93, 0.94, 0.95, 0.96, 0.97, 0.98, 0.99)
    val REVIEW_THRESHOLDS: List<Double> = listOf(0.76, 0.82, 0.86, 0.90)
    val AUTO_MARGINS: List<Double> = listOf(0.02, 0.04, 0.06, 0.08, 0.10, 0.12)

    fun run(corpus: EpgLabeledCorpus): EpgThresholdSweepResult {
        val evaluated = buildList {
            AUTO_THRESHOLDS.forEach { auto ->
                REVIEW_THRESHOLDS.filter { it <= auto }.forEach { review ->
                    AUTO_MARGINS.forEach { margin ->
                        val thresholds = EpgHybridThresholds(auto, review, margin)
                        add(thresholds to EpgMatchEvaluator.evaluate(EpgBenchmarkVariant.B_HYBRID, corpus, thresholds))
                    }
                }
            }
        }
        val safe = evaluated.filter { (_, result) -> result.metrics.falseAutomaticMatches == 0 }
        require(safe.isNotEmpty()) { "no zero-false-auto hybrid threshold configuration exists" }
        val selected = safe.sortedWith(
            compareByDescending<Pair<EpgHybridThresholds, EpgBenchmarkEvaluation>> { it.second.metrics.autoRecall }
                .thenByDescending { it.second.metrics.meanReciprocalRank }
                .thenByDescending { it.second.metrics.top3HitRate }
                .thenBy { it.second.metrics.ambiguityReviewRate }
                .thenBy { it.second.metrics.unresolvedRate }
                .thenByDescending { it.first.autoThreshold }
                .thenByDescending { it.first.autoMargin }
                .thenByDescending { it.first.reviewThreshold },
        ).first()
        val reportDigest = sha256(
            buildString {
                append("c06-threshold-sweep-v1\n")
                append(corpus.sha256).append('\n')
                append("evaluated=").append(evaluated.size).append('\n')
                append("selected=").append(selected.first.canonical()).append('\n')
                append("metrics=").append(selected.second.metrics.canonical()).append('\n')
                append("evaluation=").append(selected.second.digestSha256).append('\n')
            }.toByteArray(Charsets.UTF_8),
        )
        return EpgThresholdSweepResult(
            autoThresholds = AUTO_THRESHOLDS,
            reviewThresholds = REVIEW_THRESHOLDS,
            autoMargins = AUTO_MARGINS,
            selectedThresholds = selected.first,
            selectedEvaluation = selected.second,
            selectionSource = EpgThresholdSelectionSource.CORPUS_SWEEP,
            referenceAutoThreshold = OwnTvReferenceEpgMatcher.AUTO_THRESHOLD,
            referenceReviewThreshold = OwnTvReferenceEpgMatcher.REVIEW_THRESHOLD,
            evaluatedConfigurations = evaluated.size,
            reportDigest = reportDigest,
        )
    }
}

object EpgCompetitiveContract {
    const val MUXTV_C01_MAIN_SHA = "1e3849788a66527d82667f4e293cdbd43fae3ed0"
    const val OWNTV_CORE_SHA = "630e9c09c80345279e248c336657645300208c09"

    fun scenario(
        candidateSha: String,
        corpus: EpgLabeledCorpus = EpgMatchAdversarialCorpus,
    ): CompetitiveScenario = CompetitiveScenario(
        schemaVersion = 1,
        scenarioId = "c06-epg-matching",
        seed = 0xC06E_9A17L,
        warmupRounds = 5,
        measuredRounds = 5,
        baselineVariant = CompetitiveVariant.A,
        corpus = CorpusProvenance(
            manifestSha256 = corpus.manifestSha256,
            contentSha256 = corpus.sha256,
        ),
        variants = listOf(
            CompetitiveScenarioVariant(
                id = CompetitiveVariant.A,
                label = "MuxTV conservative exact",
                source = RepositoryPin("MuxTV/Muxtv", MUXTV_C01_MAIN_SHA),
                driverKind = CompetitiveDriverKind.JMH,
                benchmarkRef = "EpgMatchingBenchmark.variantA",
            ),
            CompetitiveScenarioVariant(
                id = CompetitiveVariant.B,
                label = "MuxTV hybrid candidate",
                source = RepositoryPin("MuxTV/Muxtv", candidateSha),
                driverKind = CompetitiveDriverKind.JMH,
                benchmarkRef = "EpgMatchingBenchmark.variantB",
            ),
            CompetitiveScenarioVariant(
                id = CompetitiveVariant.C,
                label = "OwnTV Core reference",
                source = RepositoryPin("ahXN00/OwnTV_Core", OWNTV_CORE_SHA),
                driverKind = CompetitiveDriverKind.JMH,
                benchmarkRef = "EpgMatchingBenchmark.variantC",
            ),
        ),
    )

    fun correctness(evaluation: EpgBenchmarkEvaluation): CompetitiveCorrectness = CompetitiveCorrectness(
        passed = evaluation.metrics.falseAutomaticMatches == 0,
        digestSha256 = evaluation.digestSha256,
        resultCount = evaluation.metrics.totalCases.toLong(),
    )
}

private data class CorpusRow(
    val scenarioId: String,
    val tags: Set<String>,
    val providerSource: String,
    val epgExternalId: String?,
    val epgDisplayName: String,
    val candidateSource: String,
    val candidateId: String,
    val tvgId: String?,
    val tvgName: String?,
    val rawName: String?,
    val expectedIds: Set<String>,
    val autoAllowed: Boolean,
    val reviewExpected: Boolean,
)

private data class LoadedCorpus(
    val cases: List<EpgLabeledCase>,
    val contentSha256: String,
    val manifestSha256: String,
)

private fun EpgBenchmarkMatch.toOutcome(caseId: String): EpgCaseOutcome = EpgCaseOutcome(
    caseId = caseId,
    decision = decision,
    reason = reason,
    canonicalChannelId = canonicalChannelId,
    ranking = ranking,
)

private fun String.nullableField(): String? = takeUnless { it == "-" || it.isBlank() }

private fun ratio(numerator: Int, denominator: Int, emptyValue: Double = 0.0): Double =
    if (denominator == 0) emptyValue else numerator.toDouble() / denominator.toDouble()

private fun digestEvaluation(
    variant: EpgBenchmarkVariant,
    metrics: EpgBenchmarkMetrics,
    outcomes: List<EpgCaseOutcome>,
): String = sha256(
    buildString {
        append("c06-evaluation-v1\n")
        append(variant.name).append('\n')
        append(metrics.canonical()).append('\n')
        outcomes.sortedBy(EpgCaseOutcome::caseId).forEach { outcome ->
            append(outcome.caseId).append('|')
            append(outcome.decision.name).append('|')
            append(outcome.reason.name).append('|')
            append(outcome.canonicalChannelId.orEmpty()).append('|')
            outcome.ranking.forEach { ranked ->
                append(ranked.canonicalChannelId).append('@')
                append(String.format(Locale.ROOT, "%.9f", ranked.score)).append(',')
            }
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8),
)

private fun EpgBenchmarkMetrics.canonical(): String = listOf(
    totalCases.toString(),
    automaticMatches.toString(),
    correctAutomaticMatches.toString(),
    falseAutomaticMatches.toString(),
    String.format(Locale.ROOT, "%.9f", autoPrecision),
    String.format(Locale.ROOT, "%.9f", autoRecall),
    String.format(Locale.ROOT, "%.9f", ambiguityReviewRate),
    String.format(Locale.ROOT, "%.9f", meanReciprocalRank),
    String.format(Locale.ROOT, "%.9f", top1HitRate),
    String.format(Locale.ROOT, "%.9f", top3HitRate),
    String.format(Locale.ROOT, "%.9f", unresolvedRate),
).joinToString("|")

private fun EpgHybridThresholds.canonical(): String = String.format(
    Locale.ROOT,
    "auto=%.4f|review=%.4f|margin=%.4f",
    autoThreshold,
    reviewThreshold,
    autoMargin,
)

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
