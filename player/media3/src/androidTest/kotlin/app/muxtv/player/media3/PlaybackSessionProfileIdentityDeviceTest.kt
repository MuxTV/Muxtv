package app.muxtv.player.media3

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSessionProfileIdentityDeviceTest {
    @Test
    fun sessionRequestPreservesProfileIdentityThroughBundle() {
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
}
