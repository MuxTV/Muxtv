package app.muxtv.feature.doctor

import app.muxtv.player.DevicePlaybackProfileSummary
import app.muxtv.player.PlaybackObservation

object DoctorReportFormatter {
    fun format(
        generatedAtEpochMillis: Long,
        observations: List<PlaybackObservation>,
    ): String {
        require(generatedAtEpochMillis >= 0L)
        val boundedObservations = observations.latestBoundedObservations()
        return buildString {
            appendLine("MuxTV Doctor Report v1")
            appendLine("generated_at_epoch_ms=$generatedAtEpochMillis")
            appendObservations(boundedObservations)
        }
    }

    fun format(
        generatedAtEpochMillis: Long,
        observations: List<PlaybackObservation>,
        deviceSummary: DevicePlaybackProfileSummary?,
    ): String {
        require(generatedAtEpochMillis >= 0L)
        val boundedObservations = observations.latestBoundedObservations()
        return buildString {
            appendLine("MuxTV Doctor Report v2")
            appendLine("generated_at_epoch_ms=$generatedAtEpochMillis")
            appendDeviceSummary(deviceSummary)
            appendObservations(boundedObservations)
        }
    }

    private fun List<PlaybackObservation>.latestBoundedObservations(): List<PlaybackObservation> {
        val firstIncludedIndex = (size - MAX_OBSERVATIONS).coerceAtLeast(0)
        return subList(firstIncludedIndex, size)
    }

    private fun StringBuilder.appendDeviceSummary(summary: DevicePlaybackProfileSummary?) {
        if (summary == null) {
            appendLine("device_summary=unavailable")
            return
        }

        appendLine("device_summary=available")
        append("device_video_decoders=")
            .appendLine(
                summary.videoDecoders.joinToString(",") { capability ->
                    "${capability.codec.name}:${capability.hardwareAcceleration.name}"
                },
            )
        append("device_current_display=")
            .appendLine(
                summary.currentDisplayMode?.let { mode ->
                    "${mode.widthPixels}x${mode.heightPixels}@${mode.refreshRateMilliHz}mHz"
                } ?: "-",
            )
        appendLine("device_supported_display_mode_count=${summary.supportedDisplayModeCount}")
        append("device_hdr=").appendLine(summary.hdrTypes.joinToString(",") { type -> type.name })
        appendLine("device_low_ram=${summary.lowRamDevice}")
        appendLine("device_memory_class_mb=${summary.memoryClassMb}")
    }

    private fun StringBuilder.appendObservations(observations: List<PlaybackObservation>) {
        append("observation_count=${observations.size}")
        if (observations.isEmpty()) {
            appendLine()
            append("no_observations")
            return
        }
        observations.forEachIndexed { index, observation ->
            appendLine()
            append(index + 1)
            append("|timestamp_epoch_ms=").append(observation.timestampEpochMillis)
            append("|kind=").append(observation.kind.name)
            append("|attempt=").append(observation.attemptNumber)
                .append('/').append(observation.attemptLimit)
            append("|failure=").append(observation.failureCategory?.name ?: "-")
            append("|http_status=").append(observation.httpStatusCode ?: "-")
            append("|media3_error=").append(observation.media3ErrorCode ?: "-")
        }
    }

    private const val MAX_OBSERVATIONS = 64
}
