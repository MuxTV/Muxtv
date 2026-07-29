package app.muxtv.player.media3

import app.muxtv.player.PlaybackRequest
import app.muxtv.player.StreamVariantId
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSessionRequestOwnershipTest {
    @Test
    fun `session request owns a stable snapshot of caller headers`() {
        val source = linkedMapOf("User-Agent" to "MuxTV/1")
        val request = PlaybackSessionRequest(
            mediaId = "channel-1",
            variantId = "variant-1",
            locator = "https://stream.example/live.m3u8",
            requestHeaders = source,
        )
        val equalRequest = request.copy()
        val hashCode = request.hashCode()

        source["User-Agent"] = "Changed"
        source["Authorization"] = "Bearer secret"

        assertThat(request.requestHeaders).containsExactly("User-Agent", "MuxTV/1")
        assertThat(request).isEqualTo(equalRequest)
        assertThat(request.hashCode()).isEqualTo(hashCode)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (request.requestHeaders as MutableMap<String, String>)["X-Test"] = "value"
        }
    }

    @Test
    fun `conversion preserves independent snapshots`() {
        val source = linkedMapOf("Referer" to "https://portal.example/player")
        val playbackRequest = PlaybackRequest(
            variantId = StreamVariantId("variant-1"),
            mediaId = "channel-1",
            locator = "https://stream.example/live.m3u8",
            requestHeaders = source,
        )
        val sessionRequest = playbackRequest.toPlaybackSessionRequest()

        source.clear()

        assertThat(playbackRequest.requestHeaders)
            .containsExactly("Referer", "https://portal.example/player")
        assertThat(sessionRequest.requestHeaders)
            .containsExactly("Referer", "https://portal.example/player")
    }

    @Test
    fun `copy snapshots replacement headers independently`() {
        val replacement = linkedMapOf("User-Agent" to "MuxTV/2")
        val request = PlaybackSessionRequest(
            mediaId = "channel-1",
            variantId = "variant-1",
            locator = "https://stream.example/live.m3u8",
        ).copy(requestHeaders = replacement)

        replacement.clear()

        assertThat(request.requestHeaders).containsExactly("User-Agent", "MuxTV/2")
    }
}
