package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackFirstFrameProfileIdentityTest {
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
