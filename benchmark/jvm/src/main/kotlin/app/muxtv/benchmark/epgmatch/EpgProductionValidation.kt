package app.muxtv.benchmark.epgmatch

import app.muxtv.benchmark.epg.EpgBenchmarkCandidate
import app.muxtv.benchmark.epg.EpgBenchmarkMatch
import app.muxtv.benchmark.epg.EpgBenchmarkQuery
import app.muxtv.benchmark.epg.EpgRankedCandidate
import app.muxtv.benchmark.epg.HybridEpgMatcher
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.pow

enum class EpgProductionDatasetPartition {
    CALIBRATION,
    HOLDOUT,
}

enum class EpgProductionThresholdSelectionSource {
    CALIBRATION_BREAKPOINT_SWEEP,
}

enum class EpgProductionReason {
    EXACT_ID,
    EXACT_TVG_NAME,
    EXACT_RAW_NAME,
    NORMALIZED_ALIAS_EXACT,
    NORMALIZED_ALIAS_AMBIGUOUS,
    FUZZY_AUTO,
    FUZZY_REVIEW,
    NO_MATCH,
    CANDIDATE_BUDGET_EXCEEDED,
}

enum class EpgProductionHoldoutDisposition {
    PASS,
    REVIEW_ONLY_DEFER,
    REJECT_AUTOMATIC_FUZZY,
}

object EpgProductionPolicyContract {
    const val VALIDATION_POLICY_VERSION: Int = 1
    const val MAX_FUZZY_CANDIDATES: Int = 64
    const val MAX_NORMALIZATION_CACHE_ENTRIES: Int = 512

    const val MIN_CALIBRATION_AUTO_RECALL: Double = 0.50
    const val MIN_HOLDOUT_EXACT_MISS_RECOVERY: Double = 0.50
    const val MIN_CALIBRATION_FUZZY_AUTOMATIC_DECISIONS: Int = 1
    const val MIN_AUTOMATIC_DECISION_EXPOSURE: Int = 1
    const val MIN_FUZZY_AUTOMATIC_DECISION_EXPOSURE: Int = 1

    const val MANUAL_OVERRIDE_PRECEDENCE: Boolean = true
    const val FUZZY_CAN_OVERRIDE_MANUAL_BINDING: Boolean = false
    const val FUZZY_CAN_MUTATE_CANONICAL_IDENTITY: Boolean = false
    const val FUZZY_CAN_CROSS_PROVIDER_BOUNDARY: Boolean = false
    val CANDIDATE_OVERFLOW_DECISION: EpgBenchmarkDecision = EpgBenchmarkDecision.UNRESOLVED

    val RISK_BUCKETS: Set<String> = linkedSetOf(
        "near-duplicate",
        "numeric-sibling",
        "timeshift",
        "plus-1",
        "plus-2",
        "regional",
        "unicode-digits",
        "cyrillic",
        "greek",
        "cjk",
        "nfkc",
        "quality",
        "ambiguous",
        "duplicate",
        "isolation",
        "hard-negative",
    )
}

data class EpgProductionMatch(
    val decision: EpgBenchmarkDecision,
    val reason: EpgProductionReason,
    val canonicalChannelId: String?,
    val ranking: List<EpgRankedCandidate>,
)

data class EpgProductionDecisionProvenance(
    val policyVersion: Int,
    val corpusSha256: String,
    val thresholdSelectionDigestSha256: String,
    val reason: EpgProductionReason,
    val sourceBucket: String,
    val familyId: String,
)

private data class EpgProductionCaseMetadata(
    val sourceBucket: String,
    val familyId: String,
)

class EpgProductionValidationCorpus internal constructor(
    val partition: EpgProductionDatasetPartition,
    override val cases: List<EpgLabeledCase>,
    val seedPartitionSha256: String,
    val sourceBuckets: Set<String>,
    val familyIds: Set<String>,
    private val metadataByCaseId: Map<String, EpgProductionCaseMetadata>,
    override val sha256: String,
    override val manifestSha256: String,
) : EpgLabeledCorpus {
    internal fun metadata(caseId: String): EpgProductionCaseMetadata =
        requireNotNull(metadataByCaseId[caseId]) { "missing production validation metadata for $caseId" }
}

private data class EpgProductionSeed(
    val partition: EpgProductionDatasetPartition,
    val sourceBucket: String,
    val familyId: String,
    val canonicalId: String,
    val canonicalName: String,
    val alias: String,
    val tags: Set<String>,
    val upstreamRef: String,
)

object EpgProductionValidationCorpora {
    private val loaded: Map<EpgProductionDatasetPartition, EpgProductionValidationCorpus> by lazy(::load)

    val calibration: EpgProductionValidationCorpus
        get() = requireNotNull(loaded[EpgProductionDatasetPartition.CALIBRATION])

    val holdout: EpgProductionValidationCorpus
        get() = requireNotNull(loaded[EpgProductionDatasetPartition.HOLDOUT])

    private fun load(): Map<EpgProductionDatasetPartition, EpgProductionValidationCorpus> {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/epg-production-seeds-v1.tsv")) {
            "missing epg-production-seeds-v1.tsv"
        }.use { it.readBytes() }
        val rows = bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        require(rows.firstOrNull() == SEED_HEADER) { "unexpected production seed header" }

        val seeds = rows.drop(1).mapIndexed { index, line ->
            val fields = line.split('\t')
            require(fields.size == 8) { "invalid production seed row ${index + 2}: expected 8 fields" }
            val partition = when (fields[0]) {
                "calibration" -> EpgProductionDatasetPartition.CALIBRATION
                "holdout" -> EpgProductionDatasetPartition.HOLDOUT
                else -> error("unknown production seed partition ${fields[0]}")
            }
            val seed = EpgProductionSeed(
                partition = partition,
                sourceBucket = fields[1],
                familyId = fields[2],
                canonicalId = fields[3],
                canonicalName = fields[4],
                alias = fields[5],
                tags = fields[6].split(',').filter(String::isNotBlank).toSet(),
                upstreamRef = fields[7],
            )
            require(seed.sourceBucket.isNotBlank())
            require(seed.familyId.isNotBlank())
            require(seed.canonicalId.isNotBlank())
            require(seed.canonicalName.isNotBlank())
            require(seed.alias.isNotBlank())
            require(seed.tags.isNotEmpty())
            seed.serializedFields().forEach(::requireSanitized)
            seed
        }
        require(seeds.isNotEmpty())

        val byPartition = EpgProductionDatasetPartition.entries.associateWith { partition ->
            seeds.filter { it.partition == partition }
        }
        require(byPartition.values.all(List<EpgProductionSeed>::isNotEmpty))

        val calibrationSources = byPartition.getValue(EpgProductionDatasetPartition.CALIBRATION)
            .mapTo(linkedSetOf(), EpgProductionSeed::sourceBucket)
        val holdoutSources = byPartition.getValue(EpgProductionDatasetPartition.HOLDOUT)
            .mapTo(linkedSetOf(), EpgProductionSeed::sourceBucket)
        require(calibrationSources.intersect(holdoutSources).isEmpty()) {
            "calibration and holdout source buckets must be disjoint"
        }

        val calibrationFamilies = byPartition.getValue(EpgProductionDatasetPartition.CALIBRATION)
            .mapTo(linkedSetOf(), EpgProductionSeed::familyId)
        val holdoutFamilies = byPartition.getValue(EpgProductionDatasetPartition.HOLDOUT)
            .mapTo(linkedSetOf(), EpgProductionSeed::familyId)
        require(calibrationFamilies.intersect(holdoutFamilies).isEmpty()) {
            "calibration and holdout families must be disjoint"
        }

        return byPartition.mapValues { (partition, partitionSeeds) -> buildCorpus(partition, partitionSeeds) }
    }

    private fun buildCorpus(
        partition: EpgProductionDatasetPartition,
        seeds: List<EpgProductionSeed>,
    ): EpgProductionValidationCorpus {
        val cases = mutableListOf<EpgLabeledCase>()
        val metadata = linkedMapOf<String, EpgProductionCaseMetadata>()
        val grouped = seeds.groupByTo(linkedMapOf(), EpgProductionSeed::sourceBucket)

        fun addCase(
            case: EpgLabeledCase,
            sourceBucket: String,
            familyId: String,
        ) {
            require(case.id !in metadata) { "duplicate production validation case ${case.id}" }
            require(case.candidates.size in 8..EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES) {
                "case ${case.id} candidate count ${case.candidates.size} is outside production bounds"
            }
            cases += case
            metadata[case.id] = EpgProductionCaseMetadata(sourceBucket, familyId)
        }

        grouped.forEach { (sourceBucket, bucketSeeds) ->
            val baseCandidates = denseNeighborhood(bucketSeeds, sourceBucket)

            bucketSeeds.forEach { seed ->
                val hasAsciiDigit = seed.alias.any { it in '0'..'9' }
                val queryName = if (hasAsciiDigit) fullWidthAscii(seed.alias) else "${seed.alias} HD"
                val derivedTags = buildSet {
                    addAll(seed.tags)
                    add("near-duplicate")
                    if (hasAsciiDigit) {
                        add("unicode-digits")
                        add("nfkc")
                        add("numeric-sibling")
                    } else {
                        add("quality")
                    }
                }
                addCase(
                    EpgLabeledCase(
                        id = caseId(partition, sourceBucket, seed.canonicalId, "derived"),
                        tags = derivedTags,
                        query = EpgBenchmarkQuery(sourceBucket, null, queryName),
                        candidates = baseCandidates,
                        expectedCanonicalIds = setOf(seed.canonicalId),
                        autoAllowed = true,
                        reviewExpected = false,
                    ),
                    sourceBucket,
                    seed.familyId,
                )
            }

            val fuzzyAnchor = bucketSeeds.maxWithOrNull(
                compareBy<EpgProductionSeed> { it.alias.length }.thenBy { it.canonicalId },
            )!!
            addCase(
                EpgLabeledCase(
                    id = caseId(partition, sourceBucket, fuzzyAnchor.canonicalId, "fuzzy-anchor"),
                    tags = fuzzyAnchor.tags + setOf("near-duplicate", "fuzzy"),
                    query = EpgBenchmarkQuery(sourceBucket, null, "${fuzzyAnchor.alias} feedx"),
                    candidates = baseCandidates,
                    expectedCanonicalIds = setOf(fuzzyAnchor.canonicalId),
                    autoAllowed = true,
                    reviewExpected = false,
                ),
                sourceBucket,
                "${sourceBucket}-fuzzy-anchor",
            )

            val numericSeed = bucketSeeds.firstOrNull { seed -> seed.alias.any { it in '0'..'9' } }
            val hardNegativeQuery = numericSeed?.let { replaceFirstAsciiDigitRun(it.alias, "9999") }
                ?: "Unlisted ${sourceBucket.replace('-', ' ')} 997"
            addCase(
                EpgLabeledCase(
                    id = caseId(partition, sourceBucket, "hard-negative", "unseen"),
                    tags = setOf("hard-negative", "numeric-sibling", "near-duplicate"),
                    query = EpgBenchmarkQuery(sourceBucket, null, hardNegativeQuery),
                    candidates = baseCandidates,
                    expectedCanonicalIds = emptySet(),
                    autoAllowed = false,
                    reviewExpected = false,
                ),
                sourceBucket,
                "${sourceBucket}-hard-negative",
            )

            val duplicateName = "Shared Validation Feed ${sourceBucket.replace('-', ' ')}"
            val duplicateCandidates = denseNeighborhood(
                bucketSeeds,
                sourceBucket,
                extras = listOf(
                    EpgBenchmarkCandidate("${sourceBucket}-duplicate-a", sourceBucket, tvgName = duplicateName),
                    EpgBenchmarkCandidate("${sourceBucket}-duplicate-b", sourceBucket, tvgName = duplicateName),
                ),
            )
            addCase(
                EpgLabeledCase(
                    id = caseId(partition, sourceBucket, "duplicate", "ambiguous"),
                    tags = setOf("ambiguous", "duplicate", "near-duplicate"),
                    query = EpgBenchmarkQuery(sourceBucket, null, duplicateName),
                    candidates = duplicateCandidates,
                    expectedCanonicalIds = setOf(
                        "${sourceBucket}-duplicate-a",
                        "${sourceBucket}-duplicate-b",
                    ),
                    autoAllowed = false,
                    reviewExpected = true,
                ),
                sourceBucket,
                "${sourceBucket}-duplicate",
            )

            val isolationSeed = bucketSeeds.first()
            val isolationCandidates = denseNeighborhood(
                bucketSeeds,
                sourceBucket,
                extras = listOf(
                    EpgBenchmarkCandidate(
                        canonicalChannelId = "${sourceBucket}-foreign-decoy",
                        providerSourceId = "${sourceBucket}-foreign",
                        tvgName = isolationSeed.alias,
                        rawName = isolationSeed.canonicalName,
                    ),
                ),
            )
            addCase(
                EpgLabeledCase(
                    id = caseId(partition, sourceBucket, isolationSeed.canonicalId, "isolation"),
                    tags = isolationSeed.tags + setOf("isolation"),
                    query = EpgBenchmarkQuery(sourceBucket, null, isolationSeed.alias),
                    candidates = isolationCandidates,
                    expectedCanonicalIds = setOf(isolationSeed.canonicalId),
                    autoAllowed = true,
                    reviewExpected = false,
                ),
                sourceBucket,
                "${sourceBucket}-isolation",
            )

            val timeshiftSeed = bucketSeeds.maxWithOrNull(
                compareBy<EpgProductionSeed> { it.alias.length }.thenBy { it.canonicalId },
            )!!
            val plusOneId = "${timeshiftSeed.canonicalId}-timeshift-1"
            val plusTwoId = "${timeshiftSeed.canonicalId}-timeshift-2"
            val timeshiftCandidates = denseNeighborhood(
                bucketSeeds,
                sourceBucket,
                extras = listOf(
                    EpgBenchmarkCandidate(plusOneId, sourceBucket, tvgName = "${timeshiftSeed.alias} +1"),
                    EpgBenchmarkCandidate(plusTwoId, sourceBucket, tvgName = "${timeshiftSeed.alias} +2"),
                ),
            )
            addCase(
                EpgLabeledCase(
                    id = caseId(partition, sourceBucket, timeshiftSeed.canonicalId, "plus-1"),
                    tags = timeshiftSeed.tags + setOf("timeshift", "plus-1", "numeric-sibling", "quality"),
                    query = EpgBenchmarkQuery(sourceBucket, null, "${timeshiftSeed.alias} +1 HD"),
                    candidates = timeshiftCandidates,
                    expectedCanonicalIds = setOf(plusOneId),
                    autoAllowed = true,
                    reviewExpected = false,
                ),
                sourceBucket,
                "${sourceBucket}-timeshift",
            )
            addCase(
                EpgLabeledCase(
                    id = caseId(partition, sourceBucket, timeshiftSeed.canonicalId, "plus-2"),
                    tags = timeshiftSeed.tags + setOf("timeshift", "plus-2", "numeric-sibling", "quality"),
                    query = EpgBenchmarkQuery(sourceBucket, null, "${timeshiftSeed.alias} +2 HD"),
                    candidates = timeshiftCandidates,
                    expectedCanonicalIds = setOf(plusTwoId),
                    autoAllowed = true,
                    reviewExpected = false,
                ),
                sourceBucket,
                "${sourceBucket}-timeshift",
            )
        }

        val seedPartitionSha = sha256(
            seeds.joinToString("\n", postfix = "\n") { it.canonical() }.toByteArray(Charsets.UTF_8),
        )
        val corpusSha = sha256(
            cases.sortedBy(EpgLabeledCase::id)
                .joinToString("\n", postfix = "\n", transform = ::canonicalCase)
                .toByteArray(Charsets.UTF_8),
        )
        val manifestSha = sha256(
            "epg-production-validation-v1|partition=${partition.name}|seed=$seedPartitionSha|cases=${cases.size}|corpus=$corpusSha"
                .toByteArray(Charsets.UTF_8),
        )
        return EpgProductionValidationCorpus(
            partition = partition,
            cases = cases.toList(),
            seedPartitionSha256 = seedPartitionSha,
            sourceBuckets = seeds.mapTo(linkedSetOf(), EpgProductionSeed::sourceBucket),
            familyIds = metadata.values.mapTo(linkedSetOf(), EpgProductionCaseMetadata::familyId),
            metadataByCaseId = metadata.toMap(),
            sha256 = corpusSha,
            manifestSha256 = manifestSha,
        )
    }

    private fun denseNeighborhood(
        seeds: List<EpgProductionSeed>,
        sourceBucket: String,
        extras: List<EpgBenchmarkCandidate> = emptyList(),
    ): List<EpgBenchmarkCandidate> {
        val result = seeds.map { seed ->
            EpgBenchmarkCandidate(
                canonicalChannelId = seed.canonicalId,
                providerSourceId = sourceBucket,
                tvgId = seed.canonicalId,
                tvgName = seed.alias,
                rawName = seed.canonicalName,
            )
        }.toMutableList()
        result += extras
        var index = 0
        while (result.size < 8) {
            result += EpgBenchmarkCandidate(
                canonicalChannelId = "${sourceBucket}-bounded-decoy-$index",
                providerSourceId = sourceBucket,
                tvgName = "Validation Decoy ${sourceBucket.replace('-', ' ')} $index",
                rawName = "Validation Decoy $index",
            )
            index += 1
        }
        require(result.size <= EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES)
        return result
    }

    private fun caseId(
        partition: EpgProductionDatasetPartition,
        sourceBucket: String,
        subject: String,
        suffix: String,
    ): String = listOf(partition.name.lowercase(Locale.ROOT), sourceBucket, subject, suffix)
        .joinToString("-")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]"), "-")
        .replace(Regex("-+"), "-")
        .trim('-')

    private fun requireSanitized(value: String) {
        require(!value.contains("://")) { "production validation seeds must not contain URLs" }
        require(!value.contains("authorization", ignoreCase = true))
        require(!value.contains("cookie", ignoreCase = true))
        require(!value.contains("token=", ignoreCase = true))
    }

    private fun EpgProductionSeed.serializedFields(): List<String> = listOf(
        sourceBucket,
        familyId,
        canonicalId,
        canonicalName,
        alias,
        tags.sorted().joinToString(","),
        upstreamRef,
    )

    private fun EpgProductionSeed.canonical(): String = listOf(
        partition.name,
        sourceBucket,
        familyId,
        canonicalId,
        canonicalName,
        alias,
        tags.sorted().joinToString(","),
        upstreamRef,
    ).joinToString("|")

    private const val SEED_HEADER =
        "partition\tsource_bucket\tfamily_id\tcanonical_id\tcanonical_name\talias\ttags\tupstream_ref"
}

data class EpgProductionThresholdCalibrationResult(
    val selectionSource: EpgProductionThresholdSelectionSource,
    val selectionInputPartition: EpgProductionDatasetPartition,
    val calibrationSha256: String,
    val candidateAutoThresholds: List<Double>,
    val candidateAutoMargins: List<Double>,
    val selectedThresholds: EpgHybridThresholds,
    val selectedEvaluation: EpgBenchmarkEvaluation,
    val calibrationFuzzyAutomaticDecisions: Int,
    val evaluatedConfigurations: Int,
    val selectionDigestSha256: String,
)

object EpgProductionThresholdCalibration {
    fun calibrate(corpus: EpgProductionValidationCorpus): EpgProductionThresholdCalibrationResult {
        require(corpus.partition == EpgProductionDatasetPartition.CALIBRATION) {
            "production thresholds may only be selected from the calibration partition"
        }

        val probe = EpgMatchEvaluator.evaluate(
            EpgBenchmarkVariant.B_HYBRID,
            corpus,
            EpgHybridThresholds(autoThreshold = 1.0, reviewThreshold = 0.0, autoMargin = 1.0),
        )
        val topScores = probe.outcomes.mapNotNull { it.ranking.firstOrNull()?.score }
        require(topScores.isNotEmpty()) { "calibration corpus produced no ranked candidates" }
        val margins = probe.outcomes.mapNotNull { outcome ->
            val top = outcome.ranking.firstOrNull()?.score ?: return@mapNotNull null
            top - (outcome.ranking.getOrNull(1)?.score ?: 0.0)
        }
        val positiveTopScores = corpus.cases.zip(probe.outcomes)
            .filter { (case, _) -> case.autoAllowed }
            .mapNotNull { (_, outcome) -> outcome.ranking.firstOrNull()?.score }
            .sorted()
        require(positiveTopScores.isNotEmpty())

        val candidateAutoThresholds = scoreBreakpoints(topScores)
        val candidateAutoMargins = scoreBreakpoints(margins)
        val reviewAnchor = positiveTopScores[positiveTopScores.size / 2]

        val evaluated = buildList {
            candidateAutoThresholds.forEach { auto ->
                candidateAutoMargins.forEach { margin ->
                    val review = reviewAnchor.coerceAtMost(auto)
                    val thresholds = EpgHybridThresholds(auto, review, margin)
                    val evaluation = EpgMatchEvaluator.evaluate(
                        EpgBenchmarkVariant.B_HYBRID,
                        corpus,
                        thresholds,
                    )
                    add(
                        CalibrationCandidate(
                            thresholds = thresholds,
                            evaluation = evaluation,
                            fuzzyAutomaticDecisions = evaluation.outcomes.count {
                                it.reason == EpgBenchmarkReason.FUZZY_AUTO
                            },
                        ),
                    )
                }
            }
        }
        require(evaluated.size > 1)

        val safe = evaluated.filter { it.evaluation.metrics.falseAutomaticMatches == 0 }
        require(safe.isNotEmpty()) { "no zero-false-auto calibration configuration exists" }
        val qualified = safe.filter { candidate ->
            candidate.evaluation.metrics.autoRecall >= EpgProductionPolicyContract.MIN_CALIBRATION_AUTO_RECALL &&
                candidate.fuzzyAutomaticDecisions >=
                EpgProductionPolicyContract.MIN_CALIBRATION_FUZZY_AUTOMATIC_DECISIONS
        }
        val selected = (qualified.ifEmpty { safe }).sortedWith(
            compareByDescending<CalibrationCandidate> { it.evaluation.metrics.autoRecall }
                .thenByDescending { it.fuzzyAutomaticDecisions }
                .thenByDescending { it.thresholds.autoThreshold }
                .thenByDescending { it.thresholds.autoMargin }
                .thenByDescending { it.thresholds.reviewThreshold },
        ).first()

        val selectionDigest = sha256(
            buildString {
                appendLine("c359-production-threshold-selection-v1")
                appendLine("partition=${corpus.partition.name}")
                appendLine("calibration=${corpus.sha256}")
                appendLine("seed=${corpus.seedPartitionSha256}")
                appendLine("evaluated=${evaluated.size}")
                appendLine("thresholds=${selected.thresholds.canonical()}")
                appendLine("evaluation=${selected.evaluation.digestSha256}")
                appendLine("fuzzy=${selected.fuzzyAutomaticDecisions}")
            }.toByteArray(Charsets.UTF_8),
        )
        return EpgProductionThresholdCalibrationResult(
            selectionSource = EpgProductionThresholdSelectionSource.CALIBRATION_BREAKPOINT_SWEEP,
            selectionInputPartition = EpgProductionDatasetPartition.CALIBRATION,
            calibrationSha256 = corpus.sha256,
            candidateAutoThresholds = candidateAutoThresholds,
            candidateAutoMargins = candidateAutoMargins,
            selectedThresholds = selected.thresholds,
            selectedEvaluation = selected.evaluation,
            calibrationFuzzyAutomaticDecisions = selected.fuzzyAutomaticDecisions,
            evaluatedConfigurations = evaluated.size,
            selectionDigestSha256 = selectionDigest,
        )
    }

    private data class CalibrationCandidate(
        val thresholds: EpgHybridThresholds,
        val evaluation: EpgBenchmarkEvaluation,
        val fuzzyAutomaticDecisions: Int,
    )

    private fun scoreBreakpoints(values: List<Double>): List<Double> = buildSet {
        values.forEach { raw ->
            val value = raw.coerceIn(0.0, 1.0)
            add(value)
            val stricter = Math.nextUp(value)
            if (stricter <= 1.0) add(stricter)
        }
    }.sorted()
}

object EpgProductionPolicy {
    fun evaluateCase(
        case: EpgLabeledCase,
        thresholds: EpgHybridThresholds,
    ): EpgProductionMatch {
        if (case.candidates.size > EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES) {
            return EpgProductionMatch(
                decision = EpgProductionPolicyContract.CANDIDATE_OVERFLOW_DECISION,
                reason = EpgProductionReason.CANDIDATE_BUDGET_EXCEEDED,
                canonicalChannelId = null,
                ranking = emptyList(),
            )
        }
        val match = HybridEpgMatcher(
            candidates = case.candidates,
            providerSourceId = case.query.providerSourceId,
            thresholds = thresholds.toMatcherThresholds(),
            normalizationCacheMaxEntries = EpgProductionPolicyContract.MAX_NORMALIZATION_CACHE_ENTRIES,
        ).match(case.query)
        return match.toProductionMatch()
    }
}

data class EpgProductionHoldoutGateResult(
    val frozenC06: EpgBenchmarkEvaluation,
    val holdout: EpgBenchmarkEvaluation,
    val holdoutFalseAutoByRiskBucket: Map<String, Int>,
    val holdoutExactMissRecovery: Double,
    val automaticDecisionExposure: Int,
    val fuzzyAutomaticDecisionExposure: Int,
    val exactSemanticRegressions: Int,
    val manualOverridePrecedencePreserved: Boolean,
    val holdoutProvenance: Map<String, EpgProductionDecisionProvenance>,
    val zeroFailureUpperBound95Diagnostic: Double,
    val holdoutCompatibilityKeySha256: String,
    val disposition: EpgProductionHoldoutDisposition,
) {
    val passed: Boolean
        get() = disposition == EpgProductionHoldoutDisposition.PASS
}

object EpgProductionHoldoutGate {
    fun validate(
        calibration: EpgProductionThresholdCalibrationResult,
        frozenC06: EpgLabeledCorpus,
        holdout: EpgProductionValidationCorpus,
    ): EpgProductionHoldoutGateResult {
        require(calibration.selectionInputPartition == EpgProductionDatasetPartition.CALIBRATION)
        require(holdout.partition == EpgProductionDatasetPartition.HOLDOUT)

        val frozenEvaluation = EpgMatchEvaluator.evaluate(
            EpgBenchmarkVariant.B_HYBRID,
            frozenC06,
            calibration.selectedThresholds,
        )
        val holdoutEvaluation = EpgMatchEvaluator.evaluate(
            EpgBenchmarkVariant.B_HYBRID,
            holdout,
            calibration.selectedThresholds,
        )
        val holdoutBaseline = EpgMatchEvaluator.evaluate(
            EpgBenchmarkVariant.A_CURRENT_EXACT,
            holdout,
        )

        val paired = holdout.cases.zip(holdoutEvaluation.outcomes)
        val falseAutomaticIds = paired.filter { (case, outcome) ->
            outcome.decision == EpgBenchmarkDecision.AUTO &&
                !(case.autoAllowed && outcome.canonicalChannelId in case.expectedCanonicalIds)
        }.mapTo(hashSetOf()) { it.first.id }
        val falseAutoByBucket = EpgProductionPolicyContract.RISK_BUCKETS.associateWith { bucket ->
            holdout.cases.count { case -> bucket in case.tags && case.id in falseAutomaticIds }
        }

        val exactMissed = holdout.cases.zip(holdoutBaseline.outcomes).filter { (case, baseline) ->
            case.autoAllowed && !baseline.isCorrectAutomatic(case)
        }
        val recovered = exactMissed.count { (case, _) ->
            holdoutEvaluation.outcome(case.id).isCorrectAutomatic(case)
        }
        val exactMissRecovery = ratio(recovered, exactMissed.size)
        val fuzzyExposure = holdoutEvaluation.outcomes.count { it.reason == EpgBenchmarkReason.FUZZY_AUTO }
        val exactRegressions = exactSemanticRegressions(frozenC06, calibration.selectedThresholds) +
            exactSemanticRegressions(holdout, calibration.selectedThresholds)

        val provenance = holdout.cases.associate { case ->
            val outcome = holdoutEvaluation.outcome(case.id)
            val metadata = holdout.metadata(case.id)
            case.id to EpgProductionDecisionProvenance(
                policyVersion = EpgProductionPolicyContract.VALIDATION_POLICY_VERSION,
                corpusSha256 = holdout.sha256,
                thresholdSelectionDigestSha256 = calibration.selectionDigestSha256,
                reason = outcome.reason.toProductionReason(),
                sourceBucket = metadata.sourceBucket,
                familyId = metadata.familyId,
            )
        }

        val calibrationQualified =
            calibration.selectedEvaluation.metrics.falseAutomaticMatches == 0 &&
                calibration.selectedEvaluation.metrics.autoRecall >=
                EpgProductionPolicyContract.MIN_CALIBRATION_AUTO_RECALL &&
                calibration.calibrationFuzzyAutomaticDecisions >=
                EpgProductionPolicyContract.MIN_CALIBRATION_FUZZY_AUTOMATIC_DECISIONS
        val bounded = holdout.cases.all {
            it.candidates.size <= EpgProductionPolicyContract.MAX_FUZZY_CANDIDATES
        }
        val hardSafetyPassed =
            frozenEvaluation.metrics.falseAutomaticMatches == 0 &&
                holdoutEvaluation.metrics.falseAutomaticMatches == 0 &&
                falseAutoByBucket.values.all { it == 0 } &&
                exactRegressions == 0 &&
                EpgProductionPolicyContract.MANUAL_OVERRIDE_PRECEDENCE &&
                !EpgProductionPolicyContract.FUZZY_CAN_OVERRIDE_MANUAL_BINDING &&
                !EpgProductionPolicyContract.FUZZY_CAN_MUTATE_CANONICAL_IDENTITY &&
                !EpgProductionPolicyContract.FUZZY_CAN_CROSS_PROVIDER_BOUNDARY &&
                bounded
        val qualityPassed =
            exactMissRecovery >= EpgProductionPolicyContract.MIN_HOLDOUT_EXACT_MISS_RECOVERY &&
                holdoutEvaluation.metrics.automaticMatches >=
                EpgProductionPolicyContract.MIN_AUTOMATIC_DECISION_EXPOSURE &&
                fuzzyExposure >= EpgProductionPolicyContract.MIN_FUZZY_AUTOMATIC_DECISION_EXPOSURE

        val disposition = when {
            frozenEvaluation.metrics.falseAutomaticMatches > 0 ||
                holdoutEvaluation.metrics.falseAutomaticMatches > 0 ||
                exactRegressions > 0 -> EpgProductionHoldoutDisposition.REJECT_AUTOMATIC_FUZZY
            !calibrationQualified || !hardSafetyPassed || !qualityPassed ->
                EpgProductionHoldoutDisposition.REVIEW_ONLY_DEFER
            else -> EpgProductionHoldoutDisposition.PASS
        }

        val compatibilityKey = sha256(
            buildString {
                appendLine("c359-holdout-compatibility-v1")
                appendLine("policy=${EpgProductionPolicyContract.VALIDATION_POLICY_VERSION}")
                appendLine("holdout=${holdout.sha256}")
                appendLine("selection=${calibration.selectionDigestSha256}")
                appendLine("disposition=${disposition.name}")
                holdoutEvaluation.outcomes.sortedBy(EpgCaseOutcome::caseId).forEach { outcome ->
                    append(outcome.caseId).append('|')
                    append(outcome.decision.name).append('|')
                    append(outcome.reason.name).append('|')
                    append(outcome.canonicalChannelId.orEmpty()).append('\n')
                }
            }.toByteArray(Charsets.UTF_8),
        )

        return EpgProductionHoldoutGateResult(
            frozenC06 = frozenEvaluation,
            holdout = holdoutEvaluation,
            holdoutFalseAutoByRiskBucket = falseAutoByBucket,
            holdoutExactMissRecovery = exactMissRecovery,
            automaticDecisionExposure = holdoutEvaluation.metrics.automaticMatches,
            fuzzyAutomaticDecisionExposure = fuzzyExposure,
            exactSemanticRegressions = exactRegressions,
            manualOverridePrecedencePreserved = EpgProductionPolicyContract.MANUAL_OVERRIDE_PRECEDENCE,
            holdoutProvenance = provenance,
            zeroFailureUpperBound95Diagnostic = EpgProductionStatistics.zeroFailureUpperBound95(
                holdoutEvaluation.metrics.automaticMatches,
            ),
            holdoutCompatibilityKeySha256 = compatibilityKey,
            disposition = disposition,
        )
    }

    private fun exactSemanticRegressions(
        corpus: EpgLabeledCorpus,
        thresholds: EpgHybridThresholds,
    ): Int {
        val baseline = EpgMatchEvaluator.evaluate(EpgBenchmarkVariant.A_CURRENT_EXACT, corpus)
        val hybrid = EpgMatchEvaluator.evaluate(EpgBenchmarkVariant.B_HYBRID, corpus, thresholds)
        val exactReasons = setOf(
            EpgBenchmarkReason.EXACT_ID,
            EpgBenchmarkReason.EXACT_TVG_NAME,
            EpgBenchmarkReason.EXACT_RAW_NAME,
        )
        return baseline.outcomes.zip(hybrid.outcomes).count { (before, after) ->
            before.reason in exactReasons &&
                (before.decision != after.decision ||
                    before.reason != after.reason ||
                    before.canonicalChannelId != after.canonicalChannelId)
        }
    }
}

object EpgProductionStatistics {
    fun zeroFailureUpperBound95(observations: Int): Double {
        if (observations <= 0) return 1.0
        return 1.0 - 0.05.pow(1.0 / observations.toDouble())
    }
}

private fun EpgBenchmarkMatch.toProductionMatch(): EpgProductionMatch = EpgProductionMatch(
    decision = decision,
    reason = reason.toProductionReason(),
    canonicalChannelId = canonicalChannelId,
    ranking = ranking,
)

private fun EpgBenchmarkReason.toProductionReason(): EpgProductionReason = when (this) {
    EpgBenchmarkReason.EXACT_ID -> EpgProductionReason.EXACT_ID
    EpgBenchmarkReason.EXACT_TVG_NAME -> EpgProductionReason.EXACT_TVG_NAME
    EpgBenchmarkReason.EXACT_RAW_NAME -> EpgProductionReason.EXACT_RAW_NAME
    EpgBenchmarkReason.NORMALIZED_ALIAS_EXACT -> EpgProductionReason.NORMALIZED_ALIAS_EXACT
    EpgBenchmarkReason.NORMALIZED_ALIAS_AMBIGUOUS -> EpgProductionReason.NORMALIZED_ALIAS_AMBIGUOUS
    EpgBenchmarkReason.FUZZY_AUTO -> EpgProductionReason.FUZZY_AUTO
    EpgBenchmarkReason.FUZZY_REVIEW -> EpgProductionReason.FUZZY_REVIEW
    EpgBenchmarkReason.NO_MATCH -> EpgProductionReason.NO_MATCH
    EpgBenchmarkReason.OWNTV_REFERENCE_AUTO,
    EpgBenchmarkReason.OWNTV_REFERENCE_REVIEW,
    -> error("OwnTV benchmark reasons are not production policy provenance")
}

private fun EpgCaseOutcome.isCorrectAutomatic(case: EpgLabeledCase): Boolean =
    decision == EpgBenchmarkDecision.AUTO &&
        case.autoAllowed &&
        canonicalChannelId in case.expectedCanonicalIds

private fun canonicalCase(case: EpgLabeledCase): String = buildString {
    append(case.id).append('|')
    append(case.tags.sorted().joinToString(",")).append('|')
    append(case.query.providerSourceId).append('|')
    append(case.query.epgExternalId.orEmpty()).append('|')
    append(case.query.epgDisplayName).append('|')
    append(case.expectedCanonicalIds.sorted().joinToString(",")).append('|')
    append(case.autoAllowed).append('|')
    append(case.reviewExpected).append('|')
    case.candidates.sortedWith(
        compareBy<EpgBenchmarkCandidate> { it.providerSourceId }.thenBy { it.canonicalChannelId },
    ).forEach { candidate ->
        append(candidate.providerSourceId).append(':')
        append(candidate.canonicalChannelId).append(':')
        append(candidate.tvgId.orEmpty()).append(':')
        append(candidate.tvgName.orEmpty()).append(':')
        append(candidate.rawName.orEmpty()).append(';')
    }
}

private fun fullWidthAscii(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            in '0'..'9', in 'A'..'Z', in 'a'..'z' -> append((character.code + 0xFEE0).toChar())
            else -> append(character)
        }
    }
}

private fun replaceFirstAsciiDigitRun(value: String, replacement: String): String =
    Regex("[0-9]+").replaceFirst(value, replacement)

private fun scoreBreakpoints(values: List<Double>): List<Double> = buildSet {
    values.forEach { raw ->
        val value = raw.coerceIn(0.0, 1.0)
        add(value)
        val stricter = Math.nextUp(value)
        if (stricter <= 1.0) add(stricter)
    }
}.sorted()

private fun EpgHybridThresholds.canonical(): String = String.format(
    Locale.ROOT,
    "auto=%.12f|review=%.12f|margin=%.12f",
    autoThreshold,
    reviewThreshold,
    autoMargin,
)

private fun ratio(numerator: Int, denominator: Int): Double =
    if (denominator == 0) 0.0 else numerator.toDouble() / denominator.toDouble()

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
