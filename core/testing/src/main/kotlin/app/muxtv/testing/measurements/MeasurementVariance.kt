package app.muxtv.testing.measurements

import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sqrt

private val SAFE_TOKEN_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
private val OPERATION_ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
private const val MAX_FIELD_LENGTH = 256
private const val MAX_WORKLOAD_FIELDS = 32
private const val MAX_OPERATIONS = 64
private const val MAX_SAMPLES_PER_OPERATION = 10_000

class MeasurementComparisonIdentity(
    val family: String,
    val schemaVersion: Int,
    val methodVersion: Int,
    val fixtureSha256: String,
    val apiLevel: Int?,
    val systemImage: String?,
    supportedAbis: List<String>,
    val configuredRamMb: Int?,
    val cpuCores: Int,
    val lowRamDevice: Boolean?,
    val memoryClassMb: Int?,
    val buildMode: String,
    workload: Map<String, String>,
) {
    val supportedAbis: List<String> = supportedAbis
        .map(String::trim)
        .distinct()
        .sorted()
    val workload: Map<String, String> = Collections.unmodifiableMap(
        workload.entries
            .sortedBy(Map.Entry<String, String>::key)
            .associateTo(LinkedHashMap()) { it.key to it.value },
    )
    val fingerprintSha256: String = calculateFingerprint()

    init {
        require(family.matches(SAFE_TOKEN_PATTERN))
        require(schemaVersion > 0)
        require(methodVersion > 0)
        require(fixtureSha256.matches(SHA_256_PATTERN))
        require(apiLevel == null || apiLevel >= 1)
        require(systemImage == null || systemImage.isSafeField())
        require(this.supportedAbis.isNotEmpty())
        require(this.supportedAbis.all { it.matches(SAFE_TOKEN_PATTERN) })
        require(configuredRamMb == null || configuredRamMb > 0)
        require(cpuCores > 0)
        require(memoryClassMb == null || memoryClassMb > 0)
        require(buildMode.matches(SAFE_TOKEN_PATTERN))
        require(this.workload.isNotEmpty())
        require(this.workload.size <= MAX_WORKLOAD_FIELDS)
        require(this.workload.all { (key, value) ->
            key.matches(SAFE_TOKEN_PATTERN) && value.isSafeField()
        })
    }

    private fun calculateFingerprint(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField("measurement-comparison-identity-v1")
        digest.updateField(family)
        digest.updateInt(schemaVersion)
        digest.updateInt(methodVersion)
        digest.updateField(fixtureSha256)
        digest.updateNullableInt(apiLevel)
        digest.updateNullableField(systemImage)
        digest.updateInt(supportedAbis.size)
        supportedAbis.forEach(digest::updateField)
        digest.updateNullableInt(configuredRamMb)
        digest.updateInt(cpuCores)
        digest.updateNullableBoolean(lowRamDevice)
        digest.updateNullableInt(memoryClassMb)
        digest.updateField(buildMode)
        digest.updateInt(workload.size)
        workload.forEach { (key, value) ->
            digest.updateField(key)
            digest.updateField(value)
        }
        return digest.digest().toHex()
    }

    override fun toString(): String =
        "MeasurementComparisonIdentity(family=$family, schemaVersion=$schemaVersion, " +
            "methodVersion=$methodVersion, fingerprintSha256=$fingerprintSha256)"
}

class MeasurementSeriesRun(
    val repetitionId: String,
    val identityFingerprintSha256: String,
    val thresholdApplied: Boolean,
    val failureCount: Int,
    operations: Map<String, List<Long>>,
) {
    val operations: Map<String, List<Long>> = Collections.unmodifiableMap(
        operations.entries
            .sortedBy(Map.Entry<String, List<Long>>::key)
            .associateTo(LinkedHashMap()) { (key, values) -> key to values.toList() },
    )

    init {
        require(repetitionId.matches(SAFE_TOKEN_PATTERN))
        require(identityFingerprintSha256.matches(SHA_256_PATTERN))
        require(failureCount >= 0)
        require(this.operations.isNotEmpty())
        require(this.operations.size <= MAX_OPERATIONS)
        require(this.operations.all { (operationId, samples) ->
            operationId.matches(OPERATION_ID_PATTERN) &&
                samples.isNotEmpty() &&
                samples.size <= MAX_SAMPLES_PER_OPERATION &&
                samples.all { it > 0L }
        })
    }

    override fun toString(): String =
        "MeasurementSeriesRun(identityFingerprintSha256=$identityFingerprintSha256, " +
            "thresholdApplied=$thresholdApplied, failureCount=$failureCount, " +
            "operationCount=${operations.size})"
}

class MeasurementVarianceOperationReport(
    val operationId: String,
    perRunMedians: List<Long>,
    val totalRawSampleCount: Int,
    val medianOfRunMedians: Long,
    val minimumRunMedian: Long,
    val maximumRunMedian: Long,
    val absoluteRange: Long,
    val percentageRangeBasisPoints: Long,
    val meanRunMedian: Double,
    val sampleStandardDeviation: Double,
    val coefficientOfVariationBasisPoints: Long,
    val worstObservedP95: Long,
) {
    val perRunMedians: List<Long> = perRunMedians.toList()

    init {
        require(operationId.matches(OPERATION_ID_PATTERN))
        require(this.perRunMedians.isNotEmpty())
        require(this.perRunMedians.all { it > 0L })
        require(totalRawSampleCount >= this.perRunMedians.size)
        require(minimumRunMedian > 0L)
        require(minimumRunMedian <= medianOfRunMedians)
        require(medianOfRunMedians <= maximumRunMedian)
        require(absoluteRange == maximumRunMedian - minimumRunMedian)
        require(percentageRangeBasisPoints >= 0L)
        require(meanRunMedian > 0.0)
        require(sampleStandardDeviation >= 0.0)
        require(coefficientOfVariationBasisPoints >= 0L)
        require(worstObservedP95 > 0L)
    }
}

class MeasurementVarianceReport(
    val schemaVersion: Int,
    val thresholdApplied: Boolean,
    val family: String,
    val identityFingerprintSha256: String,
    val seriesCount: Int,
    operations: List<MeasurementVarianceOperationReport>,
) {
    val operations: List<MeasurementVarianceOperationReport> = operations.toList()

    init {
        require(schemaVersion == 1)
        require(!thresholdApplied)
        require(family.matches(SAFE_TOKEN_PATTERN))
        require(identityFingerprintSha256.matches(SHA_256_PATTERN))
        require(seriesCount >= 2)
        require(this.operations.isNotEmpty())
        require(this.operations.map(MeasurementVarianceOperationReport::operationId).distinct().size ==
            this.operations.size)
    }

    override fun toString(): String =
        "MeasurementVarianceReport(schemaVersion=$schemaVersion, thresholdApplied=$thresholdApplied, " +
            "family=$family, identityFingerprintSha256=$identityFingerprintSha256, " +
            "seriesCount=$seriesCount, operationCount=${operations.size})"
}

object MeasurementVarianceAnalyzer {
    fun analyze(
        family: String,
        identityFingerprintSha256: String,
        runs: List<MeasurementSeriesRun>,
    ): MeasurementVarianceReport {
        require(family.matches(SAFE_TOKEN_PATTERN))
        require(identityFingerprintSha256.matches(SHA_256_PATTERN))
        require(runs.size >= 2)
        require(runs.map(MeasurementSeriesRun::repetitionId).distinct().size == runs.size)
        require(runs.all { it.identityFingerprintSha256 == identityFingerprintSha256 })
        require(runs.none(MeasurementSeriesRun::thresholdApplied))
        require(runs.all { it.failureCount == 0 })

        val expectedOperationIds = runs.first().operations.keys.toList()
        require(runs.all { it.operations.keys.toList() == expectedOperationIds })

        val operationReports = expectedOperationIds.map { operationId ->
            val samplesByRun = runs.map { it.operations.getValue(operationId) }
            val perRunMedians = samplesByRun.map { it.nearestRank(50) }
            val minimum = perRunMedians.min()
            val maximum = perRunMedians.max()
            val median = perRunMedians.nearestRank(50)
            val range = maximum - minimum
            val mean = perRunMedians.average()
            val sampleStandardDeviation = perRunMedians.sampleStandardDeviation(mean)
            val percentageRangeBasisPoints = if (median == 0L) {
                0L
            } else {
                (range.toDouble() / median.toDouble() * 10_000.0).roundToLong()
            }
            val coefficientOfVariationBasisPoints = if (mean == 0.0) {
                0L
            } else {
                (sampleStandardDeviation / mean * 10_000.0).roundToLong()
            }

            MeasurementVarianceOperationReport(
                operationId = operationId,
                perRunMedians = perRunMedians,
                totalRawSampleCount = samplesByRun.sumOf(List<Long>::size),
                medianOfRunMedians = median,
                minimumRunMedian = minimum,
                maximumRunMedian = maximum,
                absoluteRange = range,
                percentageRangeBasisPoints = percentageRangeBasisPoints,
                meanRunMedian = mean,
                sampleStandardDeviation = sampleStandardDeviation,
                coefficientOfVariationBasisPoints = coefficientOfVariationBasisPoints,
                worstObservedP95 = samplesByRun.maxOf { it.nearestRank(95) },
            )
        }

        return MeasurementVarianceReport(
            schemaVersion = 1,
            thresholdApplied = false,
            family = family,
            identityFingerprintSha256 = identityFingerprintSha256,
            seriesCount = runs.size,
            operations = operationReports,
        )
    }
}

object MeasurementVarianceJsonWriter {
    fun write(report: MeasurementVarianceReport, output: OutputStream) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": ${report.schemaVersion},\n")
            append("  \"thresholdApplied\": ${report.thresholdApplied},\n")
            append("  \"family\": ").appendJsonString(report.family).append(",\n")
            append("  \"identityFingerprintSha256\": ")
                .appendJsonString(report.identityFingerprintSha256)
                .append(",\n")
            append("  \"seriesCount\": ${report.seriesCount},\n")
            append("  \"operations\": [\n")
            report.operations.forEachIndexed { index, operation ->
                appendOperation(operation)
                if (index != report.operations.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
        output.write(json.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun StringBuilder.appendOperation(operation: MeasurementVarianceOperationReport) {
        append("    {\n")
        append("      \"operationId\": ").appendJsonString(operation.operationId).append(",\n")
        append("      \"totalRawSampleCount\": ${operation.totalRawSampleCount},\n")
        append("      \"perRunMedians\": [")
        operation.perRunMedians.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            append(value)
        }
        append("],\n")
        append("      \"medianOfRunMedians\": ${operation.medianOfRunMedians},\n")
        append("      \"minimumRunMedian\": ${operation.minimumRunMedian},\n")
        append("      \"maximumRunMedian\": ${operation.maximumRunMedian},\n")
        append("      \"absoluteRange\": ${operation.absoluteRange},\n")
        append("      \"percentageRangeBasisPoints\": ${operation.percentageRangeBasisPoints},\n")
        append("      \"meanRunMedian\": ${operation.meanRunMedian.canonicalDouble()},\n")
        append("      \"sampleStandardDeviation\": ${operation.sampleStandardDeviation.canonicalDouble()},\n")
        append("      \"coefficientOfVariationBasisPoints\": ${operation.coefficientOfVariationBasisPoints},\n")
        append("      \"worstObservedP95\": ${operation.worstObservedP95}\n")
        append("    }")
    }
}

private fun List<Long>.nearestRank(percentile: Int): Long {
    require(isNotEmpty())
    require(percentile in 1..100)
    val sorted = sorted()
    val rank = ceil(percentile / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
    return sorted[rank - 1]
}

private fun List<Long>.sampleStandardDeviation(mean: Double): Double {
    if (size < 2) return 0.0
    val squaredDifferenceSum = sumOf { value ->
        val difference = value.toDouble() - mean
        difference * difference
    }
    return sqrt(squaredDifferenceSum / (size - 1))
}

private fun String.isSafeField(): Boolean =
    isNotBlank() && length <= MAX_FIELD_LENGTH && none { it == '\r' || it == '\n' || it.code < 0x20 }

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    updateInt(bytes.size)
    update(bytes)
}

private fun MessageDigest.updateNullableField(value: String?) {
    if (value == null) {
        update(byteArrayOf(0))
    } else {
        update(byteArrayOf(1))
        updateField(value)
    }
}

private fun MessageDigest.updateInt(value: Int) {
    update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value).array())
}

private fun MessageDigest.updateNullableInt(value: Int?) {
    if (value == null) {
        update(byteArrayOf(0))
    } else {
        update(byteArrayOf(1))
        updateInt(value)
    }
}

private fun MessageDigest.updateNullableBoolean(value: Boolean?) {
    update(
        byteArrayOf(
            when (value) {
                null -> 0
                false -> 1
                true -> 2
            },
        ),
    )
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
}

private fun Double.canonicalDouble(): String = String.format(Locale.ROOT, "%.6f", this)

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    value.forEach { character ->
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character.code < 0x20) {
                append("\\u").append(character.code.toString(16).padStart(4, '0'))
            } else {
                append(character)
            }
        }
    }
    append('"')
    return this
}
