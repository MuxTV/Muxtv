package app.muxtv.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CatalogIdentityTest {
    @Test
    fun `stream url is locator data and never channel identity`() {
        val channel = CanonicalChannel(CanonicalChannelId("channel-1"), "Новости")
        val oldVariant = StreamVariant(StreamVariantId("variant-1"), channel.id, "https://old.example/live.m3u8")
        val refreshed = oldVariant.copy(locator = "https://new.example/live.m3u8?token=2")

        assertThat(refreshed.id).isEqualTo(oldVariant.id)
        assertThat(refreshed.canonicalChannelId).isEqualTo(channel.id)
        assertThat(refreshed.locator).isNotEqualTo(oldVariant.locator)
    }
}
