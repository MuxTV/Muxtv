package app.muxtv.feature.doctor

import app.muxtv.player.PlaybackObservation

object DoctorReportFormatter {
    fun format(
        generatedAtEpochMillis: Long,
        observations: List<PlaybackObservation>,
    ): String {
        require(generatedAtEpochMillis >= 0L)
        val firstIncludedIndex = (observations.size - MAX_OBSERVATIONS).coerceAtLeast(0)
        val boundedObservations = observations.subList(firstIncludedIndex, observations.size)
        return buildString {
            appendLine("MuxTV Doctor Report v1")
            appendLine("generated_at_epoch_ms=$generatedAtEpochMillis")
            append("observation_count=${boundedObservations.size}")
            if (boundedObservations.isEmpty()) {
                appendLine()
                append("no_observations")
                return@buildString
            }
            boundedObservations.forEachIndexed { index, observation ->
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
    }

    private const val MAX_OBSERVATIONS = 64
}
