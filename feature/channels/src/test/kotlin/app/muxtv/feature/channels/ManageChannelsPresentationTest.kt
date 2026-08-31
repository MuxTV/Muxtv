package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelManagementItem
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ManageChannelsPresentationTest {
    @Test
    fun customizedRowKeepsOriginalAndEffectiveValuesDistinct() {
        val row = buildManageChannelRow(
            ChannelManagementItem(
                channelId = "channel-1",
                canonicalDisplayName = "Discovery Channel HD",
                effectiveDisplayName = "Discovery",
                defaultChannelNumber = "501",
                customChannelNumber = 7,
                effectiveChannelNumber = "7",
                isFavorite = true,
                isHidden = true,
                variantCount = 2,
            ),
        )

        assertThat(row.channelId).isEqualTo("channel-1")
        assertThat(row.displayName).isEqualTo("Discovery")
        assertThat(row.originalDisplayName).isEqualTo("Discovery Channel HD")
        assertThat(row.channelNumber).isEqualTo("7")
        assertThat(row.defaultChannelNumber).isEqualTo("501")
        assertThat(row.customChannelNumber).isEqualTo(7)
        assertThat(row.hasCustomName).isTrue()
        assertThat(row.hasCustomNumber).isTrue()
        assertThat(row.isFavorite).isTrue()
        assertThat(row.isHidden).isTrue()
        assertThat(row.variantCount).isEqualTo(2)
    }

    @Test
    fun untouchedRowDoesNotPretendToBeCustomized() {
        val row = buildManageChannelRow(
            ChannelManagementItem(
                channelId = "channel-2",
                canonicalDisplayName = "News",
                effectiveDisplayName = "News",
                defaultChannelNumber = "12",
                customChannelNumber = null,
                effectiveChannelNumber = "12",
                isFavorite = false,
                isHidden = false,
                variantCount = 1,
            ),
        )

        assertThat(row.originalDisplayName).isNull()
        assertThat(row.customChannelNumber).isNull()
        assertThat(row.hasCustomName).isFalse()
        assertThat(row.hasCustomNumber).isFalse()
    }
}
