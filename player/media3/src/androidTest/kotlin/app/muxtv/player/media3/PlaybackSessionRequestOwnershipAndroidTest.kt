package app.muxtv.player.media3

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.player.PlaybackRequest
import app.muxtv.player.StreamVariantId
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSessionRequestOwnershipAndroidTest {
    @Test
    fun BundleRoundTripUsesOwnedHeaderSnapshot() {
        val source = linkedMapOf("Referer" to "https://portal.example/player")
        val playbackRequest = PlaybackRequest(
            variantId = StreamVariantId("variant-1"),
            mediaId = "channel-1",
            locator = "https://stream.example/live.m3u8",
            requestHeaders = source,
        )
        val sessionRequest = playbackRequest.toPlaybackSessionRequest()
        val bundle = sessionRequest.toBundle()

        source.clear()
        val decoded = requireNotNull(PlaybackSessionRequest.fromBundle(bundle))

        assertThat(decoded.requestHeaders)
            .containsExactly("Referer", "https://portal.example/player")
    }

    @Test
    fun EmptyHeadersDoNotAllocateNestedBundleAndStillRoundTrip() {
        val request = PlaybackSessionRequest(
            mediaId = "channel-empty",
            variantId = "variant-empty",
            locator = "https://stream.example/empty.m3u8",
        )

        val bundle = request.toBundle()
        val decoded = requireNotNull(PlaybackSessionRequest.fromBundle(bundle))

        assertThat(bundle.containsKey("headers")).isFalse()
        assertThat(decoded.requestHeaders).isEmpty()
    }
}