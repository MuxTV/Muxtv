package app.muxtv.database.measurement

import java.io.OutputStream

internal object CatalogDatabaseMeasurementJsonWriter {
    fun write(
        report: CatalogDatabaseMeasurementReport,
        output: OutputStream,
    ) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": ${report.schemaVersion},\n")
            append("  \"methodVersion\": ${report.methodVersion},\n")
            append("  \"buildMode\": \"debug-instrumentation\",\n")
            append("  \"thresholdApplied\": ${report.thresholdApplied},\n")
            append("  \"sourceCommit\": ").appendJsonString(report.sourceCommit).append(",\n")
            append("  \"runnerLabel\": ").appendJsonString(report.runnerLabel).append(",\n")
            append("  \"cacheState\": ").appendJsonString(report.cacheState).append(",\n")
            append("  \"workload\": {\n")
            append("    \"entryCount\": ${report.workload.entryCount},\n")
            append("    \"batchSize\": ${report.workload.batchSize},\n")
            append("    \"firstPageLimit\": ${report.workload.firstPageLimit},\n")
            append("    \"sourceOverviewCount\": ${report.workload.sourceOverviewCount},\n")
            append("    \"warmupIterations\": ${report.workload.warmupIterations},\n")
            append("    \"measuredIterations\": ${report.workload.measuredIterations}\n")
            append("  },\n")
            append("  \"fixture\": {\n")
            append("    \"entryCount\": ${report.fixture.entryCount},\n")
            append("    \"sha256\": ").appendJsonString(report.fixture.sha256).append("\n")
            append("  },\n")
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
            report.operations.forEachIndexed { operationIndex, operation ->
                appendOperation(operation)
                if (operationIndex != report.operations.lastIndex) append(',')
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

    private fun StringBuilder.appendOperation(operation: CatalogDatabaseOperationReport) {
        append("    {\n")
        append("      \"operationId\": ").appendJsonString(operation.operationId).append(",\n")
        append("      \"expectedResultCount\": ${operation.expectedResultCount},\n")
        append("      \"wallTimeNanos\": ")
        appendSummary(operation.wallTimeNanos, "      ")
        append(",\n")
        append("      \"databaseBytes\": ")
        appendSummary(operation.databaseBytes, "      ")
        append(",\n")
        append("      \"walBytes\": ")
        appendSummary(operation.walBytes, "      ")
        append(",\n")
        append("      \"shmBytes\": ")
        appendSummary(operation.shmBytes, "      ")
        append(",\n")
        append("      \"rawSamples\": [\n")
        operation.samples.forEachIndexed { sampleIndex, sample ->
            append("        {\n")
            append("          \"iteration\": ${sample.iteration},\n")
            append("          \"wallTimeNanos\": ${sample.wallTimeNanos},\n")
            append("          \"resultCount\": ${sample.resultCount},\n")
            append("          \"databaseBytes\": ${sample.databaseBytes},\n")
            append("          \"walBytes\": ${sample.walBytes},\n")
            append("          \"shmBytes\": ${sample.shmBytes}\n")
            append("        }")
            if (sampleIndex != operation.samples.lastIndex) append(',')
            append('\n')
        }
        append("      ]\n")
        append("    }")
    }

    private fun StringBuilder.appendSummary(
        summary: CatalogDatabaseMeasurementSummary,
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
