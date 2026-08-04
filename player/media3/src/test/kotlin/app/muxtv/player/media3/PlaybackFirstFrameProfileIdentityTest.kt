package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackFirstFrameProfileIdentityTest {
    @Test
    fun `session request preserves profile identity through bundle`() {
        val request = PlaybackSessionRequest(
            profileId = "profile-main",
            mediaId = "channel-a",
            variantId = "variant-a",
            locator = "https://example.invalid/live.m3u8",
        )

        val restored = PlaybackSessionRequest.fromBundle(request.toBundle())

        assertThat(restored).isNotNull()
        assertThat(restored!!.profileId).isEqualTo("profile-main")
        assertThat(restored.mediaId).isEqualTo("channel-a")
    }

    @Test
    fun `session request diagnostics redact profile identity`() {
        val request = PlaybackSessionRequest(
            profileId = "private-profile-id",
            mediaId = "channel-a",
            variantId = "variant-a",
            locator = "https://example.invalid/live.m3u8",
        )

        assertThat(request.toString()).doesNotContain("private-profile-id")
        assertThat(request.toString()).contains("profileId=<redacted>")
    }
}
