package app.muxtv.player.media3

import androidx.media3.session.SessionResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSeekCommandCodecTest {
    @Test
    fun relativeRequestRoundTripsWithOpaqueGeneration() {
        val request = PlaybackSeekRequest.Relative(
            token = PlaybackSeekToken(mediaId = "channel-1", generation = 7L),
            direction = PlaybackSeekPolicy.DIRECTION_FORWARD,
        )

        val encoded = MuxTvPlaybackSessionContract.seekArgs(request)
        val parsed = MuxTvPlaybackSessionContract.parseSeekArgs(encoded)

        assertThat(encoded.keySet()).containsExactly(
            "seek_kind",
            "seek_media_id",
            "seek_generation",
            "seek_direction",
        )
        assertThat(parsed).isEqualTo(request)
    }

    @Test
    fun absoluteRequestRoundTripsThroughSameCodec() {
        val request = PlaybackSeekRequest.Absolute(
            token = PlaybackSeekToken(mediaId = "channel-1", generation = 8L),
            targetMs = 42_000L,
        )

        val encoded = MuxTvPlaybackSessionContract.seekArgs(request)
        val parsed = MuxTvPlaybackSessionContract.parseSeekArgs(encoded)

        assertThat(encoded.keySet()).containsExactly(
            "seek_kind",
            "seek_media_id",
            "seek_generation",
            "seek_target_ms",
        )
        assertThat(parsed).isEqualTo(request)
    }

    @Test
    fun seekCodecRejectsUnknownFields() {
        val args = MuxTvPlaybackSessionContract.seekArgs(
            PlaybackSeekRequest.Relative(
                token = PlaybackSeekToken(mediaId = "channel-1", generation = 9L),
                direction = PlaybackSeekPolicy.DIRECTION_BACKWARD,
            ),
        ).apply {
            putString("unexpected", "value")
        }

        assertThat(MuxTvPlaybackSessionContract.parseSeekArgs(args)).isNull()
    }

    @Test
    fun acceptedResultRoundTripsAuthoritativeTarget() {
        val expected = PlaybackSeekResult.Accepted(
            targetMs = 30_000L,
            direction = PlaybackSeekPolicy.DIRECTION_FORWARD,
        )

        val sessionResult = MuxTvPlaybackSessionContract.seekSessionResult(expected)

        assertThat(sessionResult.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(MuxTvPlaybackSessionContract.parseSeekResult(sessionResult)).isEqualTo(expected)
    }

    @Test
    fun policyRejectionStaysTypedInsideSuccessfulTransport() {
        val expected = PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.STALE_PLAYBACK)
        val sessionResult = MuxTvPlaybackSessionContract.seekSessionResult(expected)

        assertThat(sessionResult.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
        assertThat(MuxTvPlaybackSessionContract.parseSeekResult(sessionResult)).isEqualTo(expected)
    }

    @Test
    fun mediaItemProjectsOnlyOpaqueSeekGeneration() {
        val item = request().toMediaItem(seekGeneration = 17L)
        val extras = requireNotNull(item.mediaMetadata.extras)

        assertThat(extras.keySet()).containsExactly(PLAYBACK_SEEK_GENERATION_EXTRA)
        assertThat(extras.getLong(PLAYBACK_SEEK_GENERATION_EXTRA)).isEqualTo(17L)
        assertThat(item.mediaId).isEqualTo("channel-1")
    }

    @Test
    fun ordinaryMediaItemProjectionKeepsExtrasAbsent() {
        assertThat(request().toMediaItem().mediaMetadata.extras).isNull()
    }

    private fun request() = PlaybackSessionRequest(
        profileId = "profile-1",
        mediaId = "channel-1",
        variantId = "variant-1",
        locator = "https://example.test/live.m3u8",
        insecureHttpApproved = false,
    )
}
