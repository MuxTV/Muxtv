package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSeekContractTest {
    @Test
    fun `relative request retains opaque generation and direction`() {
        val request = PlaybackSeekRequest.Relative(
            token = PlaybackSeekToken(mediaId = "channel-1", generation = 7L),
            direction = PlaybackSeekPolicy.DIRECTION_FORWARD,
        )

        assertThat(request.token.mediaId).isEqualTo("channel-1")
        assertThat(request.token.generation).isEqualTo(7L)
        assertThat(request.direction).isEqualTo(PlaybackSeekPolicy.DIRECTION_FORWARD)
    }

    @Test
    fun `absolute request retains target through same semantic contract`() {
        val request = PlaybackSeekRequest.Absolute(
            token = PlaybackSeekToken(mediaId = "channel-1", generation = 8L),
            targetMs = 42_000L,
        )

        assertThat(request.token.generation).isEqualTo(8L)
        assertThat(request.targetMs).isEqualTo(42_000L)
    }

    @Test
    fun `accepted result retains authoritative target`() {
        val result = PlaybackSeekResult.Accepted(
            targetMs = 30_000L,
            direction = PlaybackSeekPolicy.DIRECTION_FORWARD,
        )

        assertThat(result.targetMs).isEqualTo(30_000L)
        assertThat(result.direction).isEqualTo(PlaybackSeekPolicy.DIRECTION_FORWARD)
    }

    @Test
    fun `policy rejection remains typed independently from transport`() {
        val result = PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.STALE_PLAYBACK)

        assertThat(result.reason).isEqualTo(PlaybackSeekRejectReason.STALE_PLAYBACK)
    }

    @Test
    fun `seek token rejects blank media and non positive generation`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSeekToken(mediaId = " ", generation = 1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSeekToken(mediaId = "channel-1", generation = 0L)
        }
    }

    @Test
    fun `seek token rejects line breaks`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSeekToken(mediaId = "channel\n1", generation = 1L)
        }
    }

    @Test
    fun `relative and absolute requests reject invalid values`() {
        val token = PlaybackSeekToken(mediaId = "channel-1", generation = 1L)

        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSeekRequest.Relative(token = token, direction = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSeekRequest.Absolute(token = token, targetMs = -1L)
        }
    }
}
