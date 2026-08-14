package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackSessionIdentityTest {
    @Test
    fun `catalog identity preserves playback keys`() {
        val identity = PlaybackSessionIdentity.Catalog("profile-main", "channel-news")

        assertThat(identity.profileId).isEqualTo("profile-main")
        assertThat(identity.channelId).isEqualTo("channel-news")
    }

    @Test
    fun `external identity carries only an opaque session id`() {
        val identity = PlaybackSessionIdentity.External("session-opaque-id")

        assertThat(identity.sessionId).isEqualTo("session-opaque-id")
        assertThat(identity.toString()).doesNotContain("session-opaque-id")
    }

    @Test
    fun `catalog toString redacts identity fields`() {
        val identity = PlaybackSessionIdentity.Catalog("profile-secret", "channel-secret")

        assertThat(identity.toString()).doesNotContain("profile-secret")
        assertThat(identity.toString()).doesNotContain("channel-secret")
        assertThat(identity.toString()).contains("<redacted>")
    }

    @Test
    fun `blank identities are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSessionIdentity.External("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackSessionIdentity.Catalog("", "channel-news")
        }
    }
}
