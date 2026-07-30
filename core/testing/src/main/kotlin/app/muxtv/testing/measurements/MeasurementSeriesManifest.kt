package app.muxtv.testing.measurements

import java.io.OutputStream

internal data class MeasurementSeriesAuditInput(
    val reportName: String,
    val sha256: String,
)

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
    val inputs: List<MeasurementSeriesAuditInput> = inputs.toList()

    init {
        require(schemaVersion == 1)
        require(!thresholdApplied)
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
