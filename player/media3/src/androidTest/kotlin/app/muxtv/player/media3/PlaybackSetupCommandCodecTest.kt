package app.muxtv.player.media3

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSetupCommandCodecTest {
    @Test
    fun setupArgsRoundTripIdAndRequest() {
        val setupId = setupId("10000000-0000-0000-0000-000000000001")
        val request = request()

        val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(
            MuxTvPlaybackSessionContract.setupArgs(setupId, request),
        )

        assertThat(decoded).isEqualTo(PlaybackSetupCommand(setupId, request))
    }

    @Test
    fun cancelArgsRoundTripId() {
        val setupId = setupId("10000000-0000-0000-0000-000000000002")

        val decoded = MuxTvPlaybackSessionContract.parseCancelArgs(
            MuxTvPlaybackSessionContract.cancelArgs(setupId),
        )

        assertThat(decoded).isEqualTo(setupId)
    }

    @Test
    fun missingMalformedOrOversizedSetupIdIsRejected() {
        val requestBundle = request().toBundle()

        assertThat(MuxTvPlaybackSessionContract.parseSetupArgs(Bundle())).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseSetupArgs(
                Bundle().apply {
                    putString("setup_id", "contains whitespace")
                    putBundle("request", requestBundle)
                },
            ),
        ).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseSetupArgs(
                Bundle().apply {
                    putString("setup_id", "x".repeat(65))
                    putBundle("request", requestBundle)
                },
            ),
        ).isNull()
        assertThat(
            MuxTvPlaybackSessionContract.parseCancelArgs(
                Bundle().apply { putString("setup_id", "contains whitespace") },
            ),
        ).isNull()
    }

    @Test
    fun missingNestedRequestIsRejected() {
        val setupId = setupId("10000000-0000-0000-0000-000000000003")

        val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(
            MuxTvPlaybackSessionContract.cancelArgs(setupId),
        )

        assertThat(decoded).isNull()
    }

    @Test
    fun commandDiagnosticsDoNotExposeSetupIdLocatorOrHeaders() {
        val rawId = "10000000-0000-0000-0000-000000000004"
        val locator = "https://provider.invalid/live.m3u8?token=codec-secret"
        val headerValue = "Bearer codec-secret"
        val command = PlaybackSetupCommand(
            id = setupId(rawId),
            request = request(locator = locator, headerValue = headerValue),
        )

        val diagnostic = command.toString()

        assertThat(diagnostic).doesNotContain(rawId)
        assertThat(diagnostic).doesNotContain(locator)
        assertThat(diagnostic).doesNotContain(headerValue)
        assertThat(diagnostic).contains("<redacted>")
    }

    @Test
    fun cancelledResultUsesBinderSafeInvalidStateCode() {
        val result = MuxTvPlaybackSessionContract.cancelled()

        assertThat(result.resultCode)
            .isEqualTo(androidx.media3.session.SessionError.ERROR_INVALID_STATE)
    }

    private fun setupId(raw: String): PlaybackSetupId =
        requireNotNull(PlaybackSetupId.parse(raw))

    private fun request(
        locator: String = "https://provider.invalid/live.m3u8",
        headerValue: String = "Bearer test",
    ): PlaybackSessionRequest = PlaybackSessionRequest(
        mediaId = "channel-1",
        variantId = "variant-1",
        locator = locator,
        displayName = "Channel",
        requestHeaders = mapOf("Authorization" to headerValue),
    )
}
