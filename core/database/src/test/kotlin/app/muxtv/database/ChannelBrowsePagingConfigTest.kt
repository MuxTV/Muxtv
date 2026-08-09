package app.muxtv.database

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChannelBrowsePagingConfigTest {
    @Test
    fun configKeepsOnlyBoundedLoadedWindow() {
        assertThat(CHANNEL_BROWSE_PAGING_CONFIG.pageSize).isEqualTo(64)
        assertThat(CHANNEL_BROWSE_PAGING_CONFIG.initialLoadSize).isEqualTo(64)
        assertThat(CHANNEL_BROWSE_PAGING_CONFIG.prefetchDistance).isEqualTo(16)
        assertThat(CHANNEL_BROWSE_PAGING_CONFIG.maxSize).isEqualTo(256)
        assertThat(CHANNEL_BROWSE_PAGING_CONFIG.enablePlaceholders).isFalse()
    }
}
