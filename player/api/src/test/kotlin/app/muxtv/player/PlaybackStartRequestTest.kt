package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackStartRequestTest {
    @Test
    fun `request preserves only playback identities`() {
        val request = PlaybackStartRequest(
            profileId = "profile-main",
            channelId = "channel-news",
            preferredVariantId = "variant-primary",
        )

        assertThat(request.profileId).isEqualTo("profile-main")
        assertThat(request.channelId).isEqualTo("channel-news")
        assertThat(request.preferredVariantId).isEqualTo("variant-primary")
    }

    @Test
    fun `blank identities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackStartRequest(profileId = "", channelId = "channel-news")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackStartRequest(profileId = "profile-main", channelId = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackStartRequest(
                profileId = "profile-main",
                channelId = "channel-news",
                preferredVariantId = "\t",
            )
        }
    }

    @Test
    fun `toString redacts every identity and contains no secret bearing fields`() {
        val request = PlaybackStartRequest(
            profileId = "profile-secret",
            channelId = "channel-secret",
            preferredVariantId = "variant-secret",
        )

        assertThat(request.toString()).isEqualTo(
            "PlaybackStartRequest(" +
                "profileId=<redacted>, channelId=<redacted>, " +
                "preferredVariantId=<redacted>)",
        )
        assertThat(request.toString()).doesNotContain("profile-secret")
        assertThat(request.toString()).doesNotContain("channel-secret")
        assertThat(request.toString()).doesNotContain("variant-secret")
        assertThat(request.toString()).doesNotContain("locator")
        assertThat(request.toString()).doesNotContain("headers")
        assertThat(request.toString()).doesNotContain("credentials")
    }
}
