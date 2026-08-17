package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackSeekTokenProjectionTest {
    @Test
    fun `media item carries only the opaque seek generation extra`() {
        val item = request().toMediaItem(seekGeneration = 17L)
        val extras = requireNotNull(item.mediaMetadata.extras)

        assertThat(extras.keySet()).containsExactly(PLAYBACK_SEEK_GENERATION_EXTRA)
        assertThat(extras.getLong(PLAYBACK_SEEK_GENERATION_EXTRA)).isEqualTo(17L)
        assertThat(item.mediaId).isEqualTo("channel-1")
    }

    @Test
    fun `ordinary factory projection without authority generation keeps extras absent`() {
        val item = request().toMediaItem()

        assertThat(item.mediaMetadata.extras).isNull()
    }

    private fun request() = PlaybackSessionRequest(
        profileId = "profile-1",
        mediaId = "channel-1",
        variantId = "variant-1",
        locator = "https://example.test/live.m3u8",
        insecureHttpApproved = false,
    )
}
