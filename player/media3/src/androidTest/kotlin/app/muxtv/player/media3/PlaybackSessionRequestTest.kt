package app.muxtv.player.media3

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSessionRequestTest {
    @Test
    fun bundleRoundTripPreservesValuesWithoutExposingThemInDiagnostics() {
        val original = PlaybackSessionRequest(
            mediaId = "channel-1",
            variantId = "variant-1",
            locator = "https://stream.example/live.m3u8?token=secret",
            displayName = "News",
            artworkUri = "https://images.example/news.png",
            requestHeaders = mapOf(
                "User-Agent" to "Secret Agent",
                "Referer" to "https://portal.example/private",
            ),
        )

        val restored = PlaybackSessionRequest.fromBundle(original.toBundle())

        assertThat(restored).isEqualTo(original)
        val text = restored.toString()
        assertThat(text).contains("locator=<redacted>")
        assertThat(text).doesNotContain("token=secret")
        assertThat(text).doesNotContain("Secret Agent")
        assertThat(text).doesNotContain("portal.example")
    }

    @Test
    fun malformedBundleIsRejected() {
        assertThat(PlaybackSessionRequest.fromBundle(android.os.Bundle())).isNull()
    }
}
