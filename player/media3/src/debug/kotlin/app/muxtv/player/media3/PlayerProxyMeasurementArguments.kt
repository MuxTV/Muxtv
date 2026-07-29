package app.muxtv.player.media3

import android.app.Instrumentation
import android.os.Bundle
import android.util.Base64
import java.io.ByteArrayOutputStream

internal data class PlayerProxyMeasurementArguments(
    val spec: PlayerProxyMeasurementSpec,
    val outputName: String,
) {
    init {
        require(OUTPUT_NAME_PATTERN.matches(outputName))
    }

    companion object {
        fun parse(arguments: Bundle): PlayerProxyMeasurementArguments {
            val sourceCommit = arguments.requiredString(ARGUMENT_SOURCE_COMMIT)
            val runnerLabel = arguments.requiredString(ARGUMENT_RUNNER_LABEL)
            val warmups = arguments.requiredInt(ARGUMENT_WARMUPS)
            val samples = arguments.requiredInt(ARGUMENT_SAMPLES)
            val operationsPerSample = arguments.requiredInt(ARGUMENT_OPERATIONS_PER_SAMPLE)
            val outputName = arguments.requiredString(ARGUMENT_OUTPUT_NAME)
            return PlayerProxyMeasurementArguments(
                spec = PlayerProxyMeasurementSpec(
                    sourceCommit = sourceCommit,
                    runnerLabel = runnerLabel,
                    workload = PlayerProxyMeasurementWorkload(
                        warmupSamples = warmups,
                        measuredSamples = samples,
                        operationsPerSample = operationsPerSample,
                    ),
                ),
                outputName = outputName,
            )
        }

        private fun Bundle.requiredString(key: String): String =
            getString(key)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("Player proxy measurement argument is missing.")

        private fun Bundle.requiredInt(key: String): Int =
            requiredString(key).toIntOrNull()
                ?: throw IllegalArgumentException("Player proxy measurement numeric argument is invalid.")

        const val ARGUMENT_SOURCE_COMMIT = "playerMeasurementSourceCommit"
        const val ARGUMENT_RUNNER_LABEL = "playerMeasurementRunnerLabel"
        const val ARGUMENT_WARMUPS = "playerMeasurementWarmups"
        const val ARGUMENT_SAMPLES = "playerMeasurementSamples"
        const val ARGUMENT_OPERATIONS_PER_SAMPLE = "playerMeasurementOperationsPerSample"
        const val ARGUMENT_OUTPUT_NAME = "playerMeasurementOutputName"
        const val DEFAULT_WARMUPS = 2
        const val DEFAULT_SAMPLES = 10
        const val DEFAULT_OPERATIONS_PER_SAMPLE = 1_000
        const val DEFAULT_OUTPUT_NAME = "player-proxy-measurement.json"

        private val OUTPUT_NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.json")
    }
}

internal object PlayerProxyMeasurementResultPublisher {
    fun publish(
        instrumentation: Instrumentation,
        report: PlayerProxyMeasurementReport,
    ): String {
        val output = ByteArrayOutputStream()
        PlayerProxyMeasurementJsonWriter.write(report, output)
        val encoded = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        instrumentation.addResults(
            Bundle().apply {
                putString(RESULT_REPORT_BASE64, encoded)
            },
        )
        return encoded
    }

    const val RESULT_REPORT_BASE64 = "playerProxyMeasurementReportBase64"
}
