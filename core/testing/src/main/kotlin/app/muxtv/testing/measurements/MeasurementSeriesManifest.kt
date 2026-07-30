package app.muxtv.testing.measurements

import java.io.OutputStream
import java.util.Collections

private val SERIES_MANIFEST_FAMILY = Regex("[a-z0-9][a-z0-9-]{0,63}")
private val SERIES_MANIFEST_JSON_NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.json")
private val SERIES_MANIFEST_SHA = Regex("[0-9a-f]{64}")

internal data class MeasurementSeriesAuditInput(
    val reportName: String,
    val sha256: String,
) {
    init {
        require(reportName.matches(SERIES_MANIFEST_JSON_NAME))
        require(!reportName.contains(".."))
        require(sha256.matches(SERIES_MANIFEST_SHA))
    }
}

internal class MeasurementSeriesAuditManifest(
    val schemaVersion: Int,
    val thresholdApplied: Boolean,
    val family: String,
    val outputName: String,
    val varianceReportSha256: String,
    val identityFingerprintSha256: String,
    val seriesCount: Int,
    inputs: List<MeasurementSeriesAuditInput>,
) {
    val inputs: List<MeasurementSeriesAuditInput> =
        Collections.unmodifiableList(ArrayList(inputs))

    init {
        require(schemaVersion == 1)
        require(!thresholdApplied)
        require(family.matches(SERIES_MANIFEST_FAMILY))
        require(outputName.matches(SERIES_MANIFEST_JSON_NAME))
        require(!outputName.contains(".."))
        require(varianceReportSha256.matches(SERIES_MANIFEST_SHA))
        require(identityFingerprintSha256.matches(SERIES_MANIFEST_SHA))
        require(seriesCount in 2..20)
        require(this.inputs.size == seriesCount)
        require(this.inputs.map(MeasurementSeriesAuditInput::reportName).distinct().size == seriesCount)
        require(this.inputs.map(MeasurementSeriesAuditInput::sha256).distinct().size == seriesCount)
    }
}

internal object MeasurementSeriesAuditJsonWriter {
    fun write(manifest: MeasurementSeriesAuditManifest, output: OutputStream) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": ${manifest.schemaVersion},\n")
            append("  \"thresholdApplied\": ${manifest.thresholdApplied},\n")
            append("  \"family\": ").appendSeriesJsonString(manifest.family).append(",\n")
            append("  \"outputName\": ").appendSeriesJsonString(manifest.outputName).append(",\n")
            append("  \"varianceReportSha256\": ")
                .appendSeriesJsonString(manifest.varianceReportSha256)
                .append(",\n")
            append("  \"identityFingerprintSha256\": ")
                .appendSeriesJsonString(manifest.identityFingerprintSha256)
                .append(",\n")
            append("  \"seriesCount\": ${manifest.seriesCount},\n")
            append("  \"inputs\": [\n")
            manifest.inputs.forEachIndexed { index, input ->
                append("    {\n")
                append("      \"reportName\": ").appendSeriesJsonString(input.reportName).append(",\n")
                append("      \"sha256\": ").appendSeriesJsonString(input.sha256).append('\n')
                append("    }")
                if (index != manifest.inputs.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
        output.write(json.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}

private fun StringBuilder.appendSeriesJsonString(value: String): StringBuilder {
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
