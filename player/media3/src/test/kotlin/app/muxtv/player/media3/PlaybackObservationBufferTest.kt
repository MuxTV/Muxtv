package app.muxtv.player.media3

import app.muxtv.player.PlaybackFailureCategory
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackObservationBufferTest {
    @Test
    fun `buffer retains only the newest bounded observations`() {
        val buffer = PlaybackObservationBuffer(capacity = 2)

        buffer.record(observation(1L))
        buffer.record(observation(2L))
        buffer.record(observation(3L))

        assertThat(buffer.snapshot().map { it.timestampEpochMillis })
            .containsExactly(2L, 3L)
            .inOrder()
    }

    private fun observation(timestamp: Long) = PlaybackObservation(
        kind = PlaybackObservationKind.ATTEMPT_FAILED,
        failureCategory = PlaybackFailureCategory.UNKNOWN,
        attemptNumber = 1,
        attemptLimit = 3,
        timestampEpochMillis = timestamp,
    )
}
