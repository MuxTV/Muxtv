package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackFirstFrameRecorderTest {
    @Test
    fun `records the same first-frame event in every observer`() {
        val first = mutableListOf<PlaybackFirstFrameEvent>()
        val second = mutableListOf<PlaybackFirstFrameEvent>()
        val recorder = PlaybackFirstFrameRecorder(
            observers = linkedSetOf(
                PlaybackFirstFrameObserver(first::add),
                PlaybackFirstFrameObserver(second::add),
            ),
        )
        val event = PlaybackFirstFrameEvent(
            channelId = "channel-a",
            activationElapsedMillis = 125L,
        )

        recorder.record(event)

        assertThat(first).containsExactly(event)
        assertThat(second).containsExactly(event)
    }

    @Test
    fun `observer failure does not suppress later observers`() {
        val delivered = mutableListOf<PlaybackFirstFrameEvent>()
        val recorder = PlaybackFirstFrameRecorder(
            observers = linkedSetOf(
                PlaybackFirstFrameObserver { throw IllegalStateException("synthetic observer failure") },
                PlaybackFirstFrameObserver(delivered::add),
            ),
        )
        val event = PlaybackFirstFrameEvent(
            channelId = "channel-a",
            activationElapsedMillis = 125L,
        )

        recorder.record(event)

        assertThat(delivered).containsExactly(event)
    }

    @Test
    fun `empty observer set accepts first-frame event`() {
        val recorder = PlaybackFirstFrameRecorder(emptySet())

        recorder.record(
            PlaybackFirstFrameEvent(
                channelId = "channel-a",
                activationElapsedMillis = 125L,
            ),
        )
    }
}
