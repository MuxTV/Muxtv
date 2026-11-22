package app.muxtv.benchmark.competitive

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.Random
import kotlin.math.ceil
import kotlin.math.roundToLong

private val REPOSITORY_PATTERN = Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
private val SHA1_PATTERN = Regex("[0-9a-f]{40}")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val TOKEN_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val METRIC_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val EVIDENCE_PATH_PATTERN = Regex("[A-Za-z0-9._/-]{1,256}")
private val WINDOWS_ABSOLUTE_PATH_PATTERN = Regex("^[A-Za-z]:.*")
private val SENSITIVE_NAME_PATTERN = Regex("(?i)(authorization|cookie|password|passwd|secret|token|credential|api[-_]?key)")
private const val MAX_SAFE_FIELD_LENGTH = 256
private const val MAX_METADATA_FIELDS = 32
private const val MAX_VARIANTS = 4
private const val MAX_METRICS = 32
private const val MAX_SAMPLES_PER_METRIC = 100_000
private const val MAX_EVIDENCE_REFS = 64

enum class CompetitiveVariant { A, B, C, D }

enum class CompetitiveDriverKind { JMH, MACROBENCHMARK, EXTERNAL }

enum class CompetitiveExecutionPhase { CORRECTNESS, WARMUP, MEASURED }

data class RepositoryPin(
    val repository: String,
    val sha: String,
) {
    init {
        require(repository.matches(REPOSITORY_PATTERN)) { "repository must be an owner/name slug" }
        require(sha.matches(SHA1_PATTERN)) { "repository sha must be an exact lowercase 40-hex commit" }
    }
}

data class CorpusProvenance(
    val manifestSha256: String,
    val contentSha256: String,
) {
    init {
        require(manifestSha256.matches(SHA256_PATTERN)) { "corpus manifest sha256 is invalid" }
        require(contentSha256.matches(SHA256_PATTERN)) { "corpus content sha256 is invalid" }
    }
}

data class CompetitiveEvidenceRef(val value: String) {
    init {
        require(value.matches(EVIDENCE_PATH_PATTERN)) { "evidence reference must be a bounded relative path" }
        require(!value.startsWith('/')) { "evidence reference must be relative" }
        require(!value.matches(WINDOWS_ABSOLUTE_PATH_PATTERN)) { "evidence reference must be relative" }
        require('\\' !in value && '?' !in value && '#' !in value && ':' !in value) {
            "evidence reference must not contain URI, query, fragment, or absolute-path syntax"
        }
        require(value.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "evidence reference must not traverse directories"
        }
        require(!SENSITIVE_NAME_PATTERN.containsMatchIn(value)) {
            "evidence reference must not encode a sensitive field name"
        }
    }
}

data class CompetitiveScenarioVariant(
    val id: CompetitiveVariant,
    val label: String,
    val source: RepositoryPin,
    val driverKind: CompetitiveDriverKind,
    val benchmarkRef: String,
) {
    init {
        require(label.isSafeField()) { "variant label is invalid" }
        require(benchmarkRef.isSafeField()) { "benchmark reference is invalid" }
        require(!SENSITIVE_NAME_PATTERN.containsMatchIn(benchmarkRef)) { "benchmark reference contains a sensitive field name" }
    }
}

class CompetitiveScenario(
    val schemaVersion: Int,
    val scenarioId: String,
    val seed: Long,
    val warmupRounds: Int,
    val measuredRounds: Int,
    val baselineVariant: CompetitiveVariant,
    val corpus: CorpusProvenance,
    variants: List<CompetitiveScenarioVariant>,
) {
    val variants: List<CompetitiveScenarioVariant> = variants.toList()

    init {
        require(schemaVersion == 1) { "unsupported scenario schema version" }
        require(scenarioId.matches(TOKEN_PATTERN)) { "scenario id is invalid" }
        require(warmupRounds in 0..100) { "warmup rounds are out of range" }
        require(measuredRounds in 1..10_000) { "measured rounds are out of range" }
        require(this.variants.size in 2..MAX_VARIANTS) { "scenario must contain two to four variants" }
        require(this.variants.map(CompetitiveScenarioVariant::id).distinct().size == this.variants.size) {
            "scenario variants must be unique"
        }
        require(this.variants.any { it.id == baselineVariant }) { "baseline variant must exist in scenario" }
    }
}

class CompetitiveEnvironment(
    val schemaVersion: Int,
    val environmentId: String,
    val buildMode: String,
    val baselineProfileState: String,
    val networkProfile: String,
    runtime: Map<String, String>,
    device: Map<String, String>,
) {
    val runtime: Map<String, String> = runtime.toSortedMap().toMap()
    val device: Map<String, String> = device.toSortedMap().toMap()
    val fingerprintSha256: String

    init {
        require(schemaVersion == 1) { "unsupported environment schema version" }
        require(environmentId.matches(TOKEN_PATTERN)) { "environment id is invalid" }
        require(buildMode.matches(TOKEN_PATTERN)) { "build mode is invalid" }
        require(baselineProfileState.matches(TOKEN_PATTERN)) { "baseline profile state is invalid" }
        require(networkProfile.matches(TOKEN_PATTERN)) { "network profile is invalid" }
        require(this.runtime.isNotEmpty() && this.runtime.size <= MAX_METADATA_FIELDS) { "runtime metadata is invalid" }
        require(this.device.size <= MAX_METADATA_FIELDS) { "device metadata is invalid" }
        require(this.runtime.isSafeMetadata() && this.device.isSafeMetadata()) { "environment metadata is invalid" }
        fingerprintSha256 = calculateFingerprint()
    }

    private fun calculateFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField("muxtv-competitive-environment-v1")
        digest.updateInt(schemaVersion)
        digest.updateField(environmentId)
        digest.updateField(buildMode)
        digest.updateField(baselineProfileState)
        digest.updateField(networkProfile)
        digest.updateMap(runtime)
        digest.updateMap(device)
        return digest.digest().toHex()
    }
}

data class CompetitiveCorrectness(
    val passed: Boolean,
    val digestSha256: String,
    val resultCount: Long,
) {
    init {
        require(digestSha256.matches(SHA256_PATTERN)) { "correctness digest is invalid" }
        require(resultCount >= 0L) { "correctness result count must not be negative" }
    }
}

data class CompetitiveMetric(
    val metricId: String,
    val unit: String,
    val samples: List<Long>,
) {
    init {
        require(metricId.matches(METRIC_PATTERN)) { "metric id is invalid" }
        require(unit.matches(TOKEN_PATTERN)) { "metric unit is invalid" }
        require(samples.isNotEmpty() && samples.size <= MAX_SAMPLES_PER_METRIC) { "metric samples are invalid" }
        require(samples.all { it > 0L }) { "metric samples must be positive measured values" }
    }
}

data class CompetitiveVariantResult(
    val schemaVersion: Int,
    val scenarioId: String,
    val variant: CompetitiveVariant,
    val source: RepositoryPin,
    val corpus: CorpusProvenance,
    val environmentFingerprintSha256: String,
    val correctness: CompetitiveCorrectness,
    val metrics: List<CompetitiveMetric>,
    val evidenceRefs: List<CompetitiveEvidenceRef>,
    val redactionPassed: Boolean,
) {
    init {
        require(schemaVersion == 1) { "unsupported result schema version" }
        require(scenarioId.matches(TOKEN_PATTERN)) { "result scenario id is invalid" }
        require(environmentFingerprintSha256.matches(SHA256_PATTERN)) { "environment fingerprint is invalid" }
        require(metrics.isNotEmpty() && metrics.size <= MAX_METRICS) { "result metrics are invalid" }
        require(metrics.map(CompetitiveMetric::metricId).distinct().size == metrics.size) { "metric ids must be unique" }
        require(evidenceRefs.size <= MAX_EVIDENCE_REFS) { "too many evidence references" }
    }
}

data class CompetitiveRunSlot(
    val phase: CompetitiveExecutionPhase,
    val round: Int,
    val ordinal: Int,
    val variant: CompetitiveVariant,
)

object CompetitiveExecutionPlanner {
    fun planCorrectness(scenario: CompetitiveScenario): List<CompetitiveRunSlot> =
        randomizedRound(
            scenario = scenario,
            phase = CompetitiveExecutionPhase.CORRECTNESS,
            round = 1,
            ordinalOffset = 0,
        )

    fun planPerformance(
        scenario: CompetitiveScenario,
        correctness: Map<CompetitiveVariant, Boolean>,
    ): List<CompetitiveRunSlot> {
        val expectedVariants = scenario.variants.map(CompetitiveScenarioVariant::id).toSet()
        require(correctness.keys == expectedVariants) { "correctness results must cover every scenario variant exactly" }
        require(correctness.values.all { it }) { "performance is forbidden when correctness fails" }

        val slots = ArrayList<CompetitiveRunSlot>(
            (scenario.warmupRounds + scenario.measuredRounds) * scenario.variants.size,
        )
        repeat(scenario.warmupRounds) { index ->
            slots += randomizedRound(
                scenario = scenario,
                phase = CompetitiveExecutionPhase.WARMUP,
                round = index + 1,
                ordinalOffset = slots.size,
            )
        }
        repeat(scenario.measuredRounds) { index ->
            slots += randomizedRound(
                scenario = scenario,
                phase = CompetitiveExecutionPhase.MEASURED,
                round = index + 1,
                ordinalOffset = slots.size,
            )
        }
        return slots.toList()
    }

    private fun randomizedRound(
        scenario: CompetitiveScenario,
        phase: CompetitiveExecutionPhase,
        round: Int,
        ordinalOffset: Int,
    ): List<CompetitiveRunSlot> {
        val variants = scenario.variants.map(CompetitiveScenarioVariant::id).toMutableList()
        val phaseSalt = when (phase) {
            CompetitiveExecutionPhase.CORRECTNESS -> 0x13579BDFL
            CompetitiveExecutionPhase.WARMUP -> 0x2468ACE0L
            CompetitiveExecutionPhase.MEASURED -> 0x5A17C9E3L
        }
        val roundSeed = scenario.seed xor phaseSalt xor (round.toLong() * -7046029254386353131L)
        Collections.shuffle(variants, Random(roundSeed))
        return variants.mapIndexed { index, variant ->
            CompetitiveRunSlot(
                phase = phase,
                round = round,
                ordinal = ordinalOffset + index,
                variant = variant,
            )
        }
    }
}

data class CompetitiveMetricReport(
    val metricId: String,
    val unit: String,
    val sampleCount: Int,
    val minimum: Long,
    val median: Long,
    val p90: Long,
    val p95: Long,
    val p99: Long,
    val maximum: Long,
    val deltaBasisPointsFromBaseline: Long,
)

data class CompetitiveVariantReport(
    val variant: CompetitiveVariant,
    val source: RepositoryPin,
    val metrics: List<CompetitiveMetricReport>,
)

data class CompetitiveReport(
    val schemaVersion: Int,
    val thresholdApplied: Boolean,
    val scenarioId: String,
    val seed: Long,
    val corpus: CorpusProvenance,
    val environmentFingerprintSha256: String,
    val baselineVariant: CompetitiveVariant,
    val variantReports: List<CompetitiveVariantReport>,
)

object CompetitiveAggregator {
    fun aggregate(
        scenario: CompetitiveScenario,
        environment: CompetitiveEnvironment,
        results: List<CompetitiveVariantResult>,
    ): CompetitiveReport {
        val expectedByVariant = scenario.variants.associateBy(CompetitiveScenarioVariant::id)
        require(results.size == expectedByVariant.size) { "exactly one result per scenario variant is required" }
        require(results.map(CompetitiveVariantResult::variant).distinct().size == results.size) {
            "duplicate variant results are invalid"
        }
        require(results.map(CompetitiveVariantResult::variant).toSet() == expectedByVariant.keys) {
            "result variants do not match scenario"
        }

        results.forEach { result ->
            val expected = expectedByVariant.getValue(result.variant)
            require(result.schemaVersion == 1) { "unsupported result schema version" }
            require(result.scenarioId == scenario.scenarioId) { "result scenario provenance mismatch" }
            require(result.source == expected.source) { "result source provenance mismatch" }
            require(result.corpus == scenario.corpus) { "result corpus provenance mismatch" }
            require(result.environmentFingerprintSha256 == environment.fingerprintSha256) {
                "result environment provenance mismatch"
            }
            require(result.redactionPassed) { "result redaction gate failed" }
            require(result.correctness.passed) { "result correctness gate failed" }
        }

        val baselineResult = results.single { it.variant == scenario.baselineVariant }
        val baselineMetrics = baselineResult.metrics.associateBy(CompetitiveMetric::metricId)
        require(baselineMetrics.isNotEmpty()) { "baseline metrics are required" }
        val expectedMetricIdentity = baselineResult.metrics.map { it.metricId to it.unit }.toSet()
        require(results.all { result ->
            result.metrics.map { it.metricId to it.unit }.toSet() == expectedMetricIdentity
        }) { "all variants must report the same metric ids and units" }

        val reports = scenario.variants.map { variantSpec ->
            val result = results.single { it.variant == variantSpec.id }
            val metricReports = result.metrics.sortedBy(CompetitiveMetric::metricId).map { metric ->
                val baseline = baselineMetrics.getValue(metric.metricId)
                val baselineMedian = baseline.samples.nearestRank(50)
                val median = metric.samples.nearestRank(50)
                CompetitiveMetricReport(
                    metricId = metric.metricId,
                    unit = metric.unit,
                    sampleCount = metric.samples.size,
                    minimum = metric.samples.min(),
                    median = median,
                    p90 = metric.samples.nearestRank(90),
                    p95 = metric.samples.nearestRank(95),
                    p99 = metric.samples.nearestRank(99),
                    maximum = metric.samples.max(),
                    deltaBasisPointsFromBaseline =
                        ((median.toDouble() - baselineMedian.toDouble()) / baselineMedian.toDouble() * 10_000.0)
                            .roundToLong(),
                )
            }
            CompetitiveVariantReport(
                variant = variantSpec.id,
                source = variantSpec.source,
                metrics = metricReports,
            )
        }

        return CompetitiveReport(
            schemaVersion = 1,
            thresholdApplied = false,
            scenarioId = scenario.scenarioId,
            seed = scenario.seed,
            corpus = scenario.corpus,
            environmentFingerprintSha256 = environment.fingerprintSha256,
            baselineVariant = scenario.baselineVariant,
            variantReports = reports,
        )
    }
}

private fun List<Long>.nearestRank(percentile: Int): Long {
    require(isNotEmpty())
    require(percentile in 1..100)
    val sorted = sorted()
    val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

private fun String.isSafeField(): Boolean =
    isNotBlank() &&
        length <= MAX_SAFE_FIELD_LENGTH &&
        none { it == '\r' || it == '\n' || it.code < 0x20 }

private fun Map<String, String>.isSafeMetadata(): Boolean =
    entries.all { (key, value) ->
        key.matches(TOKEN_PATTERN) &&
            !SENSITIVE_NAME_PATTERN.containsMatchIn(key) &&
            value.isSafeField() &&
            !SENSITIVE_NAME_PATTERN.containsMatchIn(value)
    }

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    updateInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.updateInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
}

private fun MessageDigest.updateMap(values: Map<String, String>) {
    updateInt(values.size)
    values.toSortedMap().forEach { (key, value) ->
        updateField(key)
        updateField(value)
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
