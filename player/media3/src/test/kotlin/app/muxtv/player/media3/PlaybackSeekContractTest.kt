package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSeekContractTest {
    @Test
    fun `relative request round trips with opaque generation`() {
        val request = PlaybackSeekRequest.Relative(
            token = PlaybackSeekToken(mediaId = "channel-1", generation = 7L),
            direction = PlaybackSeekController.DIRECTION_FORWARD,
        )

        val parsed = MuxTvPlaybackSessionContract.parseSeekArgs(
            MuxTvPlaybackSessionContract.seekArgs(request),
        )

        assertThat(parsed).isEqualTo(request)
    }

    @Test
    fun `absolute request round trips through same contract`() {
        val request = PlaybackSeekRequest.Absolute(
            token = PlaybackSeekToken(mediaId = "channel-1", generation = 8L),
            targetMs = 42_000L,
        )

        val parsed = MuxTvPlaybackSessionContract.parseSeekArgs(
            MuxTvPlaybackSessionContract.seekArgs(request),
        )

        assertThat(parsed).isEqualTo(request)
    }

    @Test
    fun `seek args reject unknown fields`() {
        val args = MuxTvPlaybackSessionContract.seekArgs(
            PlaybackSeekRequest.Relative(
                token = PlaybackSeekToken(mediaId = "channel-1", generation = 9L),
                direction = PlaybackSeekController.DIRECTION_BACKWARD,
            ),
        ).apply {
            putString("unexpected", "value")
        }

        assertThat(MuxTvPlaybackSessionContract.parseSeekArgs(args)).isNull()
    }

    @Test
    fun `accepted result round trips authoritative target`() {
        val expected = PlaybackSeekResult.Accepted(
            targetMs = 30_000L,
            direction = PlaybackSeekController.DIRECTION_FORWARD,
        )

        val parsed = MuxTvPlaybackSessionContract.parseSeekResult(
            MuxTvPlaybackSessionContract.seekSessionResult(expected),
        )

        assertThat(parsed).isEqualTo(expected)
    }

    @Test
    fun `policy rejection stays typed inside successful transport`() {
        val expected = PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.STALE_PLAYBACK)
        val sessionResult = MuxTvPlaybackSessionContract.seekSessionResult(expected)

        assertThat(sessionResult.resultCode).isEqualTo(androidx.media3.session.SessionResult.RESULT_SUCCESS)
        assertThat(MuxTvPlaybackSessionContract.parseSeekResult(sessionResult)).isEqualTo(expected)
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
