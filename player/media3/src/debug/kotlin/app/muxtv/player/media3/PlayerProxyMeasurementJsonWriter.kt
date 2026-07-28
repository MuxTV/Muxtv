package app.muxtv.player.media3

import java.io.OutputStream

internal object PlayerProxyMeasurementJsonWriter {
    fun write(
        report: PlayerProxyMeasurementReport,
        output: OutputStream,
    ) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": ${report.schemaVersion},\n")
            append("  \"methodVersion\": ${report.methodVersion},\n")
            append("  \"buildMode\": ").appendJsonString(report.buildMode).append(",\n")
            append("  \"thresholdApplied\": ${report.thresholdApplied},\n")
            append("  \"sourceCommit\": ").appendJsonString(report.sourceCommit).append(",\n")
            append("  \"runnerLabel\": ").appendJsonString(report.runnerLabel).append(",\n")
            append("  \"workload\": {\n")
            append("    \"warmupSamples\": ${report.workload.warmupSamples},\n")
            append("    \"measuredSamples\": ${report.workload.measuredSamples},\n")
            append("    \"operationsPerSample\": ${report.workload.operationsPerSample}\n")
            append("  },\n")
            append("  \"requestProfileSha256\": ")
                .appendJsonString(report.requestProfileSha256)
                .append(",\n")
            append("  \"environment\": {\n")
            append("    \"manufacturer\": ").appendJsonString(report.environment.manufacturer).append(",\n")
            append("    \"model\": ").appendJsonString(report.environment.model).append(",\n")
            append("    \"fingerprint\": ").appendJsonString(report.environment.fingerprint).append(",\n")
            append("    \"apiLevel\": ${report.environment.apiLevel},\n")
            append("    \"supportedAbis\": [")
            report.environment.supportedAbis.forEachIndexed { index, abi ->
                if (index > 0) append(", ")
                appendJsonString(abi)
            }
            append("],\n")
            append("    \"lowRamDevice\": ${report.environment.lowRamDevice},\n")
            append("    \"memoryClassMb\": ${report.environment.memoryClassMb},\n")
            append("    \"availableProcessors\": ${report.environment.availableProcessors}\n")
            append("  },\n")
            append("  \"operations\": [\n")
            report.operations.forEachIndexed { index, operation ->
                appendOperation(operation)
                if (index != report.operations.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"failureCount\": ${report.failureCount},\n")
            append("  \"limitations\": [\n")
            report.limitations.forEachIndexed { index, limitation ->
                append("    ").appendJsonString(limitation)
                if (index != report.limitations.lastIndex) append(',')
                append('\n')
            }
            append("  ]\n")
            append("}\n")
        }
        output.write(json.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun StringBuilder.appendOperation(operation: PlayerProxyOperationReport) {
        append("    {\n")
        append("      \"operationId\": ").appendJsonString(operation.operationId).append(",\n")
        append("      \"expectedSuccessfulResultCount\": ${operation.expectedSuccessfulResultCount},\n")
        append("      \"batchWallTimeNanos\": ")
        appendSummary(operation.batchWallTimeNanos, "      ")
        append(",\n")
        append("      \"normalizedNanosPerOperation\": ")
        appendSummary(operation.normalizedNanosPerOperation, "      ")
        append(",\n")
        append("      \"rawSamples\": [\n")
        operation.samples.forEachIndexed { index, sample ->
            append("        {\n")
            append("          \"sampleIndex\": ${sample.sampleIndex},\n")
            append("          \"batchWallTimeNanos\": ${sample.batchWallTimeNanos},\n")
            append("          \"operationCount\": ${sample.operationCount},\n")
            append("          \"normalizedNanosPerOperation\": ${sample.normalizedNanosPerOperation},\n")
            append("          \"successfulResultCount\": ${sample.successfulResultCount}\n")
            append("        }")
            if (index != operation.samples.lastIndex) append(',')
            append('\n')
        }
        append("      ]\n")
        append("    }")
    }

    private fun StringBuilder.appendSummary(
        summary: PlayerProxyMeasurementSummary,
        indent: String,
    ) {
        append("{\n")
        append(indent).append("  \"sampleCount\": ${summary.sampleCount},\n")
        append(indent).append("  \"minimum\": ${summary.minimum},\n")
        append(indent).append("  \"p50\": ${summary.p50},\n")
        append(indent).append("  \"p90\": ${summary.p90},\n")
        append(indent).append("  \"p95\": ${summary.p95},\n")
        append(indent).append("  \"maximum\": ${summary.maximum}\n")
        append(indent).append('}')
    }

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
}
