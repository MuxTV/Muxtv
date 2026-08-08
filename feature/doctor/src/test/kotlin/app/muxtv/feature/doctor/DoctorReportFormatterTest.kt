package app.muxtv.feature.doctor

import app.muxtv.player.PlaybackFailureCategory
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DoctorReportFormatterTest {
    @Test
    fun `report is deterministic bounded and contains only typed fields`() {
        val report = DoctorReportFormatter.format(
            generatedAtEpochMillis = 2_000L,
            observations = listOf(
                PlaybackObservation(
                    kind = PlaybackObservationKind.ATTEMPT_FAILED,
                    failureCategory = PlaybackFailureCategory.HTTP_RESPONSE,
                    attemptNumber = 1,
                    attemptLimit = 3,
                    timestampEpochMillis = 1_000L,
                    httpStatusCode = 429,
                    media3ErrorCode = 2004,
                ),
                PlaybackObservation(
                    kind = PlaybackObservationKind.RECOVERY_SUCCEEDED,
                    attemptNumber = 2,
                    attemptLimit = 3,
                    timestampEpochMillis = 1_500L,
                ),
            ),
        )

        assertThat(report).isEqualTo(
            """
            MuxTV Doctor Report v1
            generated_at_epoch_ms=2000
            observation_count=2
            1|timestamp_epoch_ms=1000|kind=ATTEMPT_FAILED|attempt=1/3|failure=HTTP_RESPONSE|http_status=429|media3_error=2004
            2|timestamp_epoch_ms=1500|kind=RECOVERY_SUCCEEDED|attempt=2/3|failure=-|http_status=-|media3_error=-
            """.trimIndent(),
        )
        assertThat(report).doesNotContain("http://")
        assertThat(report).doesNotContain("Authorization")
        assertThat(report).doesNotContain("Cookie")
    }

    @Test
    fun `empty report remains useful and stable`() {
        assertThat(DoctorReportFormatter.format(7L, emptyList())).isEqualTo(
            """
            MuxTV Doctor Report v1
            generated_at_epoch_ms=7
            observation_count=0
            no_observations
            """.trimIndent(),
        )
    }

    @Test
    fun `report keeps only the latest bounded observations`() {
        val observations = (0L..64L).map { timestamp ->
            PlaybackObservation(
                kind = PlaybackObservationKind.ATTEMPT_STARTED,
                attemptNumber = 1,
                attemptLimit = 3,
                timestampEpochMillis = timestamp,
            )
        }

        val report = DoctorReportFormatter.format(100L, observations)

        assertThat(report).contains("observation_count=64")
        assertThat(report).doesNotContain("timestamp_epoch_ms=0|")
        assertThat(report).contains("timestamp_epoch_ms=64|")
    }
}
