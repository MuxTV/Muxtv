package app.muxtv.player.media3

import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSetupCommandCodecTest {
    @Test
    fun `setup args round trip id and request`() {
        val setupId = setupId("10000000-0000-0000-0000-000000000001")
        val request = request()

        val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(
            MuxTvPlaybackSessionContract.setupArgs(setupId, request),
        )

        assertThat(decoded).isEqualTo(PlaybackSetupCommand(setupId, request))
    }

    @Test
    fun `cancel args round trip id`() {
        val setupId = setupId("10000000-0000-0000-0000-000000000002")

        val decoded = MuxTvPlaybackSessionContract.parseCancelArgs(
            MuxTvPlaybackSessionContract.cancelArgs(setupId),
        )

        assertThat(decoded).isEqualTo(setupId)
    }

    @Test
    fun `missing malformed or oversized setup id is rejected`() {
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
    fun `missing nested request is rejected`() {
        val setupId = setupId("10000000-0000-0000-0000-000000000003")

        val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(
            MuxTvPlaybackSessionContract.cancelArgs(setupId),
        )

        assertThat(decoded).isNull()
    }

    @Test
    fun `command diagnostics do not expose setup id locator or headers`() {
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
    fun `cancelled result uses stable informational code`() {
        val result = MuxTvPlaybackSessionContract.cancelled()

        assertThat(result.resultCode).isEqualTo(androidx.media3.session.SessionError.INFO_CANCELLED)
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
