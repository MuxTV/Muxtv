package app.muxtv.database.measurement

import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val C03_OUTPUT_NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.json")
private val C03_SOURCE_COMMIT_PATTERN = Regex("[0-9a-f]{40}")

internal data class C03ProductionRoomEvidenceArguments(
    val spec: C03ProductionRoomMeasurementSpec,
    val outputName: String,
) {
    init {
        require(outputName.matches(C03_OUTPUT_NAME_PATTERN))
    }

    companion object {
        fun parse(arguments: Bundle): C03ProductionRoomEvidenceArguments {
            val sourceCommit = arguments.requiredString(ARGUMENT_SOURCE_COMMIT)
            require(sourceCommit.matches(C03_SOURCE_COMMIT_PATTERN)) {
                "C03 production Room evidence source commit must be exact lowercase 40-hex."
            }
            val warmups = arguments.requiredInt(ARGUMENT_WARMUPS)
            val iterations = arguments.requiredInt(ARGUMENT_ITERATIONS)
            val entryCount = arguments.requiredInt(ARGUMENT_ENTRY_COUNT)
            val outputName = arguments.requiredString(ARGUMENT_OUTPUT_NAME)

            require(warmups == CANONICAL_WARMUPS) {
                "C03 production Room evidence warmup count must match the preregistered matrix."
            }
            require(iterations in MIN_CANONICAL_ITERATIONS..MAX_CANONICAL_ITERATIONS) {
                "C03 production Room evidence measured iteration count is unsupported."
            }
            require(entryCount == C03ProductionRoomMeasurementSpec.DEFAULT_ENTRY_COUNT) {
                "C03 production Room evidence entry count must match the preregistered matrix."
            }
            require(outputName.matches(C03_OUTPUT_NAME_PATTERN)) {
                "C03 production Room evidence output name is unsafe."
            }

            return C03ProductionRoomEvidenceArguments(
                spec = C03ProductionRoomMeasurementSpec(
                    sourceCommit = sourceCommit,
                    warmupIterations = warmups,
                    measuredIterations = iterations,
                    entryCount = entryCount,
                    scenarios = C03ProductionScenario.entries,
                ),
                outputName = outputName,
            )
        }

        private fun Bundle.requiredString(key: String): String =
            getString(key)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("C03 production Room evidence argument is missing.")

        private fun Bundle.requiredInt(key: String): Int =
            requiredString(key).toIntOrNull()
                ?: throw IllegalArgumentException("C03 production Room evidence numeric argument is invalid.")

        const val ARGUMENT_SOURCE_COMMIT = "c03ProductionRoomSourceCommit"
        const val ARGUMENT_WARMUPS = "c03ProductionRoomWarmups"
        const val ARGUMENT_ITERATIONS = "c03ProductionRoomIterations"
        const val ARGUMENT_ENTRY_COUNT = "c03ProductionRoomEntryCount"
        const val ARGUMENT_OUTPUT_NAME = "c03ProductionRoomOutputName"
        const val CANONICAL_WARMUPS = 1
        const val MIN_CANONICAL_ITERATIONS = 5
        const val MAX_CANONICAL_ITERATIONS = 10
    }
}

internal object C03ProductionRoomMeasurementJsonWriter {
    fun write(
        report: C03ProductionRoomMeasurementReport,
        output: OutputStream,
    ) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": ${report.schemaVersion},\n")
            append("  \"methodVersion\": ").appendJsonString(report.methodVersion).append(",\n")
            append("  \"sourceCommit\": ").appendJsonString(report.sourceCommit).append(",\n")
            append("  \"corpusSha256\": ").appendJsonString(report.corpusSha256).append(",\n")
            append("  \"thresholdApplied\": ${report.thresholdApplied},\n")
            append("  \"baselineVariant\": ").appendJsonString(report.baselineVariant.name).append(",\n")
            append("  \"warmupIterations\": ${report.warmupIterations},\n")
            append("  \"measuredIterations\": ${report.measuredIterations},\n")
            append("  \"entryCount\": ${report.entryCount},\n")
            append("  \"batchSize\": ${report.batchSize},\n")
            append("  \"environment\": {\n")
            append("    \"apiLevel\": ${report.environment.apiLevel},\n")
            append("    \"manufacturer\": ").appendJsonString(report.environment.manufacturer).append(",\n")
            append("    \"model\": ").appendJsonString(report.environment.model).append(",\n")
            append("    \"availableProcessors\": ${report.environment.availableProcessors},\n")
            append("    \"fingerprintSha256\": ")
                .appendJsonString(report.environment.fingerprintSha256).append("\n")
            append("  },\n")
            append("  \"scenarios\": [\n")
            report.scenarios.forEachIndexed { scenarioIndex, scenario ->
                appendScenario(scenario)
                if (scenarioIndex != report.scenarios.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"repeatedRevisionStorage\": [\n")
            report.repeatedRevisionStorage.forEachIndexed { index, storage ->
                appendRepeatedStorage(storage)
                if (index != report.repeatedRevisionStorage.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"safety\": [\n")
            report.safety.forEachIndexed { index, safety ->
                appendSafety(safety)
                if (index != report.safety.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"redactionPassed\": ${report.redactionPassed},\n")
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

    private fun StringBuilder.appendScenario(scenario: C03ProductionRoomScenarioMeasurement) {
        append("    {\n")
        append("      \"scenarioId\": ").appendJsonString(scenario.scenarioId).append(",\n")
        append("      \"expectedCorrectnessDigestSha256\": ")
            .appendJsonString(scenario.expectedCorrectnessDigestSha256).append(",\n")
        append("      \"expectedCorrectnessCount\": ${scenario.expectedCorrectnessCount},\n")
        append("      \"executionSeed\": ${scenario.executionSeed},\n")
        append("      \"correctnessPassed\": ${scenario.correctnessPassed},\n")
        append("      \"executionOrder\": [\n")
        scenario.executionOrder.forEachIndexed { index, slot ->
            appendExecutionSlot(slot)
            if (index != scenario.executionOrder.lastIndex) append(',')
            append('\n')
        }
        append("      ],\n")
        append("      \"variants\": [\n")
        scenario.variants.forEachIndexed { index, variant ->
            appendVariant(variant)
            if (index != scenario.variants.lastIndex) append(',')
            append('\n')
        }
        append("      ]\n")
        append("    }")
    }

    private fun StringBuilder.appendExecutionSlot(slot: C03ProductionRoomExecutionSlot) {
        append("        {\n")
        append("          \"phase\": ").appendJsonString(slot.phase.name).append(",\n")
        append("          \"round\": ${slot.round},\n")
        append("          \"ordinal\": ${slot.ordinal},\n")
        append("          \"variant\": ").appendJsonString(slot.variant.name).append("\n")
        append("        }")
    }

    private fun StringBuilder.appendVariant(variant: C03ProductionRoomVariantMeasurement) {
        append("        {\n")
        append("          \"variant\": ").appendJsonString(variant.variant.name).append(",\n")
        append("          \"correctnessDigestSha256\": ")
            .appendJsonString(variant.correctnessDigestSha256).append(",\n")
        append("          \"correctnessCount\": ${variant.correctnessCount},\n")
        append("          \"logicalIdentityDigestSha256\": ")
            .appendJsonString(variant.logicalIdentityDigestSha256).append(",\n")
        append("          \"previousGoodDigestSha256\": ")
            .appendJsonString(variant.previousGoodDigestSha256).append(",\n")
        append("          \"writes\": ")
        appendWrites(variant.writes, "          ")
        append(",\n")
        append("          \"samples\": [\n")
        variant.samples.forEachIndexed { index, sample ->
            appendSample(sample)
            if (index != variant.samples.lastIndex) append(',')
            append('\n')
        }
        append("          ],\n")
        append("          \"stage\": ")
        appendDistribution(variant.stage, "          ")
        append(",\n")
        append("          \"publication\": ")
        appendDistribution(variant.publication, "          ")
        append(",\n")
        append("          \"cleanup\": ")
        appendDistribution(variant.cleanup, "          ")
        append(",\n")
        append("          \"browse\": ")
        appendDistribution(variant.browse, "          ")
        append(",\n")
        append("          \"providerLookup\": ")
        appendDistribution(variant.providerLookup, "          ")
        append(",\n")
        append("          \"search\": ")
        appendDistribution(variant.search, "          ")
        append(",\n")
        append("          \"queryPlans\": [\n")
        variant.queryPlans.forEachIndexed { index, plan ->
            appendQueryPlan(plan)
            if (index != variant.queryPlans.lastIndex) append(',')
            append('\n')
        }
        append("          ]\n")
        append("        }")
    }

    private fun StringBuilder.appendWrites(
        writes: C03ProductionRoomWriteCounts,
        indent: String,
    ) {
        append("{\n")
        append(indent).append("  \"providerOrPayloadWrites\": ${writes.providerOrPayloadWrites},\n")
        append(indent).append("  \"searchPayloadWrites\": ${writes.searchPayloadWrites},\n")
        append(indent).append("  \"searchDocumentWrites\": ${writes.searchDocumentWrites},\n")
        append(indent).append("  \"membershipWrites\": ${writes.membershipWrites},\n")
        append(indent).append("  \"revisionMetadataWrites\": ${writes.revisionMetadataWrites},\n")
        append(indent).append("  \"sourceMetadataWrites\": ${writes.sourceMetadataWrites},\n")
        append(indent).append("  \"cleanupDeletes\": ${writes.cleanupDeletes}\n")
        append(indent).append('}')
    }

    private fun StringBuilder.appendSample(sample: C03ProductionRoomTimingSample) {
        append("            {\n")
        append("              \"stageTotalNanos\": ${sample.stageTotalNanos},\n")
        append("              \"publicationNanos\": ${sample.publicationNanos},\n")
        append("              \"cleanupNanos\": ${sample.cleanupNanos},\n")
        append("              \"cancellationCleanupNanos\": ${sample.cancellationCleanupNanos},\n")
        append("              \"browseNanos\": ${sample.browseNanos},\n")
        append("              \"providerLookupNanos\": ${sample.providerLookupNanos},\n")
        append("              \"searchNanos\": ${sample.searchNanos},\n")
        append("              \"before\": ")
        appendFileState(sample.before, "              ")
        append(",\n")
        append("              \"afterStage\": ")
        appendFileState(sample.afterStage, "              ")
        append(",\n")
        append("              \"afterPublication\": ")
        appendFileState(sample.afterPublication, "              ")
        append(",\n")
        append("              \"afterCleanup\": ")
        appendFileState(sample.afterCleanup, "              ")
        append('\n')
        append("            }")
    }

    private fun StringBuilder.appendFileState(
        state: C03ProductionRoomFileState,
        indent: String,
    ) {
        append("{\n")
        append(indent).append("  \"databaseBytes\": ${state.databaseBytes},\n")
        append(indent).append("  \"walBytes\": ${state.walBytes},\n")
        append(indent).append("  \"shmBytes\": ${state.shmBytes},\n")
        append(indent).append("  \"totalBytes\": ${state.totalBytes}\n")
        append(indent).append('}')
    }

    private fun StringBuilder.appendDistribution(
        distribution: C03ProductionRoomDistribution,
        indent: String,
    ) {
        append("{\n")
        append(indent).append("  \"medianNanos\": ${distribution.medianNanos},\n")
        append(indent).append("  \"p90Nanos\": ${distribution.p90Nanos},\n")
        append(indent).append("  \"p95Nanos\": ${distribution.p95Nanos},\n")
        append(indent).append("  \"p99Nanos\": ${distribution.p99Nanos},\n")
        append(indent).append("  \"samples\": [")
        distribution.samples.forEachIndexed { index, value ->
            if (index > 0) append(", ")
            append(value)
        }
        append("]\n")
        append(indent).append('}')
    }

    private fun StringBuilder.appendQueryPlan(plan: C03ProductionRoomQueryPlan) {
        append("            {\n")
        append("              \"operation\": ").appendJsonString(plan.operation).append(",\n")
        append("              \"details\": [\n")
        plan.details.forEachIndexed { index, detail ->
            append("                ").appendJsonString(detail)
            if (index != plan.details.lastIndex) append(',')
            append('\n')
        }
        append("              ],\n")
        append("              \"indexed\": ${plan.indexed}\n")
        append("            }")
    }

    private fun StringBuilder.appendRepeatedStorage(storage: C03ProductionRoomRepeatedRevisionStorage) {
        append("    {\n")
        append("      \"variant\": ").appendJsonString(storage.variant.name).append(",\n")
        append("      \"revisionCount\": ${storage.revisionCount},\n")
        append("      \"before\": ")
        appendFileState(storage.before, "      ")
        append(",\n")
        append("      \"beforeCompaction\": ")
        appendFileState(storage.beforeCompaction, "      ")
        append(",\n")
        append("      \"afterCompaction\": ")
        appendFileState(storage.afterCompaction, "      ")
        append(",\n")
        append("      \"orphanPayloadRows\": ${storage.orphanPayloadRows},\n")
        append("      \"orphanSearchPayloadRows\": ${storage.orphanSearchPayloadRows},\n")
        append("      \"compactionNanos\": ${storage.compactionNanos},\n")
        append("      \"compactionDeletedRows\": ${storage.compactionDeletedRows}\n")
        append("    }")
    }

    private fun StringBuilder.appendSafety(safety: C03ProductionRoomSafetyMeasurement) {
        append("    {\n")
        append("      \"variant\": ").appendJsonString(safety.variant.name).append(",\n")
        append("      \"previousGoodPreserved\": ${safety.previousGoodPreserved},\n")
        append("      \"supersededRejected\": ${safety.supersededRejected},\n")
        append("      \"partialFailurePublished\": ${safety.partialFailurePublished},\n")
        append("      \"cancellationLatePublished\": ${safety.cancellationLatePublished},\n")
        append("      \"cleanupBounded\": ${safety.cleanupBounded},\n")
        append("      \"cancellationCleanupNanos\": ${safety.cancellationCleanupNanos}\n")
        append("    }")
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

internal object C03ProductionRoomMeasurementReportPublisher {
    fun publish(
        context: Context,
        report: C03ProductionRoomMeasurementReport,
        outputName: String,
    ): File {
        require(outputName.matches(C03_OUTPUT_NAME_PATTERN))
        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "C03 production Room evidence external storage is unavailable."
        }
        val directory = File(externalRoot, DIRECTORY_NAME)
        check(directory.isDirectory || directory.mkdirs()) {
            "C03 production Room evidence directory could not be created."
        }
        val target = File(directory, outputName)
        val staged = File.createTempFile(".c03-production-room-", ".tmp", directory)
        try {
            staged.outputStream().use { output ->
                C03ProductionRoomMeasurementJsonWriter.write(report, output)
            }
            try {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(target.isFile && target.length() > 0L) {
                "C03 production Room evidence report publication failed."
            }
            return target
        } catch (error: IOException) {
            throw IllegalStateException("C03 production Room evidence report publication failed.", error)
        } finally {
            staged.delete()
        }
    }

    const val DIRECTORY_NAME = "c03-production-room"
}
