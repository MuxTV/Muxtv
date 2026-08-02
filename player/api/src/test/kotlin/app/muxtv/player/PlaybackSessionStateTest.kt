package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSessionStateTest {
    @Test
    fun `idle state has no active channel`() {
        assertThat(PlaybackSessionState.Idle.channelId).isNull()
        assertThat(PlaybackSessionState.Idle.phase).isEqualTo(PlaybackSessionPhase.IDLE)
        assertThat(PlaybackSessionState.Idle.isPlaying).isFalse()
        assertThat(PlaybackSessionState.Idle.hasActiveChannel).isFalse()
    }

    @Test
    fun `active state redacts canonical channel id`() {
        val state = PlaybackSessionState(
            channelId = "canonical-sensitive-channel",
            phase = PlaybackSessionPhase.READY,
            isPlaying = true,
        )

        assertThat(state.hasActiveChannel).isTrue()
        assertThat(state.toString()).doesNotContain("canonical-sensitive-channel")
        assertThat(state.toString()).contains("channelPresent=true")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `non idle state requires channel identity`() {
        PlaybackSessionState(
            channelId = null,
            phase = PlaybackSessionPhase.READY,
            isPlaying = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `idle state rejects stale channel identity`() {
        PlaybackSessionState(
            channelId = "channel-a",
            phase = PlaybackSessionPhase.IDLE,
            isPlaying = false,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `playing state must be ready`() {
        PlaybackSessionState(
            channelId = "channel-a",
            phase = PlaybackSessionPhase.BUFFERING,
            isPlaying = true,
        )
    }
}
