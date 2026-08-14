package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackObservationTest {
    @Test
    fun `observation surface contains only bounded typed fields`() {
        val observation = PlaybackObservation(
            kind = PlaybackObservationKind.ATTEMPT_FAILED,
            failureCategory = PlaybackFailureCategory.HTTP_RESPONSE,
            attemptNumber = 2,
            attemptLimit = 3,
            timestampEpochMillis = 42L,
            httpStatusCode = 429,
            media3ErrorCode = 2_004,
        )

        assertThat(observation.toString()).contains("HTTP_RESPONSE")
        assertThat(observation.toString()).doesNotContain("http://")
        assertThat(observation.toString()).doesNotContain("token")
    }

    @Test
    fun `observation rejects unbounded attempts and invalid status codes`() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(attemptNumber = 4, attemptLimit = 3)
        }
        assertThrows(IllegalArgumentException::class.java) {
            observation(httpStatusCode = 42)
        }
    }

    @Test
    fun `failure kinds require a category and nonfailure kinds reject one`() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(failureCategory = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            observation(
                kind = PlaybackObservationKind.RECOVERY_SUCCEEDED,
                failureCategory = PlaybackFailureCategory.UNKNOWN,
            )
        }
    }

    @Test
    fun `external playback failure requires a category like other failure kinds`() {
        assertThrows(IllegalArgumentException::class.java) {
            observation(
                kind = PlaybackObservationKind.EXTERNAL_PLAYBACK_FAILED,
                failureCategory = null,
            )
        }
    }

    @Test
    fun `external observations are accepted without a failure category`() {
        for (kind in listOf(
            PlaybackObservationKind.EXTERNAL_INTENT_ACCEPTED,
            PlaybackObservationKind.EXTERNAL_INTENT_REJECTED,
            PlaybackObservationKind.EXTERNAL_SETUP_STARTED,
            PlaybackObservationKind.EXTERNAL_FIRST_FRAME,
        )) {
            observation(kind = kind, failureCategory = null)
        }
    }

    private fun observation(
        kind: PlaybackObservationKind = PlaybackObservationKind.ATTEMPT_FAILED,
        failureCategory: PlaybackFailureCategory? = PlaybackFailureCategory.UNKNOWN,
        attemptNumber: Int = 1,
        attemptLimit: Int = 3,
        httpStatusCode: Int? = null,
    ) = PlaybackObservation(
        kind = kind,
        failureCategory = failureCategory,
        attemptNumber = attemptNumber,
        attemptLimit = attemptLimit,
        timestampEpochMillis = 1L,
        httpStatusCode = httpStatusCode,
        media3ErrorCode = null,
    )
}
