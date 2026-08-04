package app.muxtv.player.media3

import app.muxtv.player.PlaybackRequest
import app.muxtv.player.StreamVariantId
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val sessionRequest = playbackRequest.toPlaybackSessionRequest(PROFILE_ID)
        val bundle = sessionRequest.toBundle()

        source.clear()
        val decoded = requireNotNull(PlaybackSessionRequest.fromBundle(bundle))

        assertThat(decoded.profileId).isEqualTo(PROFILE_ID)
        assertThat(decoded.requestHeaders)
            .containsExactly("Referer", "https://portal.example/player")
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
    }
}
