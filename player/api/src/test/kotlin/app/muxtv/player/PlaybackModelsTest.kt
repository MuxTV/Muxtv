package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackModelsTest {
    @Test
    fun `legacy request construction remains valid`() {
        val request = PlaybackRequest(
            variantId = StreamVariantId("variant-1"),
            locator = "https://stream.example/live.m3u8",
        )

        assertThat(request.mediaId).isEqualTo("StreamVariantId(value=variant-1)")
        assertThat(request.requestHeaders).isEmpty()
    }

    @Test
    fun `request string redacts all user-controlled playback fields`() {
        val request = PlaybackRequest(
            variantId = StreamVariantId("variant-1"),
            locator = "https://stream.example/live.m3u8?token=secret",
            mediaId = "channel-1",
            displayName = "News",
            artworkUri = "https://images.example/news.png",
            requestHeaders = mapOf(
                "User-Agent" to "Secret Agent",
                "Referer" to "https://portal.example/private",
            ),
        )

        val text = request.toString()

        assertThat(text).contains("variantId=<redacted>")
        assertThat(text).contains("mediaId=<redacted>")
        assertThat(text).contains("locator=<redacted>")
        assertThat(text).contains("hasDisplayName=true")
        assertThat(text).contains("hasArtworkUri=true")
        assertThat(text).contains("headerCount=2")
        assertThat(text).doesNotContain("variant-1")
        assertThat(text).doesNotContain("channel-1")
        assertThat(text).doesNotContain("News")
        assertThat(text).doesNotContain("User-Agent")
        assertThat(text).doesNotContain("Referer")
        assertThat(text).doesNotContain("token=secret")
        assertThat(text).doesNotContain("Secret Agent")
        assertThat(text).doesNotContain("portal.example")
    }

    @Test
    fun `request rejects header injection`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackRequest(
                variantId = StreamVariantId("variant-1"),
                locator = "https://stream.example/live.m3u8",
                requestHeaders = mapOf("User-Agent\r\nX-Injected" to "value"),
            )
        }
    }
}
