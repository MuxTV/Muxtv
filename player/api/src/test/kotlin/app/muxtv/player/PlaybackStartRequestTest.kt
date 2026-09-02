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
        assertThat(request.intent).isEqualTo(PlaybackIntent.Live("channel-news"))
    }

    @Test
    fun `catchup programme request preserves provider neutral semantic intent`() {
        val intent = PlaybackIntent.CatchupProgram(
            channelId = "channel-catchup",
            programmeId = "programme-42",
            startEpochMillis = 1_800_000_000_000L,
            endEpochMillis = 1_800_003_600_000L,
        )

        val request = PlaybackStartRequest(
            profileId = "profile-main",
            intent = intent,
            preferredVariantId = "variant-archive",
        )

        assertThat(request.intent).isEqualTo(intent)
        assertThat(request.channelId).isEqualTo("channel-catchup")
        assertThat(request.preferredVariantId).isEqualTo("variant-archive")
        assertThat(request).isNotEqualTo(
            PlaybackStartRequest(
                profileId = "profile-main",
                channelId = "channel-catchup",
                preferredVariantId = "variant-archive",
            ),
        )
    }

    @Test
    fun `catchup position request preserves provider neutral semantic intent`() {
        val intent = PlaybackIntent.CatchupPosition(
            channelId = "channel-catchup",
            positionEpochMillis = 1_800_001_800_000L,
        )

        val request = PlaybackStartRequest(
            profileId = "profile-main",
            intent = intent,
        )

        assertThat(request.intent).isEqualTo(intent)
        assertThat(request.channelId).isEqualTo("channel-catchup")
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
            intent = PlaybackIntent.CatchupProgram(
                channelId = "channel-secret",
                programmeId = "programme-secret",
                startEpochMillis = 1_800_000_000_000L,
                endEpochMillis = 1_800_003_600_000L,
            ),
            preferredVariantId = "variant-secret",
        )

        assertThat(request.toString()).contains("profileId=<redacted>")
        assertThat(request.toString()).contains("channelId=<redacted>")
        assertThat(request.toString()).contains("intent=CatchupProgram")
        assertThat(request.toString()).contains("preferredVariantId=<redacted>")
        assertThat(request.toString()).doesNotContain("profile-secret")
        assertThat(request.toString()).doesNotContain("channel-secret")
        assertThat(request.toString()).doesNotContain("programme-secret")
        assertThat(request.toString()).doesNotContain("variant-secret")
        assertThat(request.toString()).doesNotContain("locator")
        assertThat(request.toString()).doesNotContain("headers")
        assertThat(request.toString()).doesNotContain("credentials")
    }
}
