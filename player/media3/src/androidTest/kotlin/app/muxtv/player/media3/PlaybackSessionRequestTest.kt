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
            profileId = PROFILE_ID,
            mediaId = "channel-1",
            variantId = "variant-1",
            locator = "https://stream.example/live.m3u8?token=secret",
            displayName = "News",
            artworkUri = "https://images.example/news.png",
            requestHeaders = mapOf(
                "User-Agent" to "Secret Agent",
                "Referer" to "https://portal.example/private",
            ),
            insecureHttpApproved = true,
        )

        val restored = PlaybackSessionRequest.fromBundle(original.toBundle())

        assertThat(restored).isEqualTo(original)
        assertThat(restored!!.profileId).isEqualTo(PROFILE_ID)
        assertThat(restored.insecureHttpApproved).isTrue()
        val text = restored.toString()
        assertThat(text).contains("profileId=<redacted>")
        assertThat(text).contains("locator=<redacted>")
        assertThat(text).contains("insecureHttpApproved=true")
        assertThat(text).doesNotContain(PROFILE_ID)
        assertThat(text).doesNotContain("token=secret")
        assertThat(text).doesNotContain("Secret Agent")
        assertThat(text).doesNotContain("portal.example")
    }

    @Test
    fun missingCleartextApprovalDefaultsToDenied() {
        val original = PlaybackSessionRequest(
            profileId = PROFILE_ID,
            mediaId = "channel-1",
            variantId = "variant-1",
            locator = "https://stream.example/live.m3u8",
        )

        assertThat(original.insecureHttpApproved).isFalse()
        assertThat(PlaybackSessionRequest.fromBundle(original.toBundle())!!.insecureHttpApproved)
            .isFalse()
    }

    @Test
    fun malformedBundleIsRejected() {
        assertThat(PlaybackSessionRequest.fromBundle(android.os.Bundle())).isNull()
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
    }
}
