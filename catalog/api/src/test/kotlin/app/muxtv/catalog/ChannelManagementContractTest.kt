package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ChannelManagementContractTest {
    @Test
    fun queryRequiresProfileAndRedactsItFromDiagnostics() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelManagementQuery(
                profileId = " ",
                visibility = ChannelManagementVisibility.ALL,
            )
        }

        val query = ChannelManagementQuery(
            profileId = "private-profile-id",
            visibility = ChannelManagementVisibility.HIDDEN,
        )

        assertThat(query.toString()).doesNotContain("private-profile-id")
        assertThat(query.toString()).contains("HIDDEN")
    }

    @Test
    fun visibilityModesAreExplicitAndStable() {
        assertThat(ChannelManagementVisibility.entries)
            .containsExactly(
                ChannelManagementVisibility.ALL,
                ChannelManagementVisibility.VISIBLE,
                ChannelManagementVisibility.HIDDEN,
            )
            .inOrder()
    }

    @Test
    fun managementItemKeepsCanonicalAndEffectiveValuesDistinct() {
        val item = ChannelManagementItem(
            channelId = "channel-1",
            canonicalDisplayName = "Discovery Channel HD",
            effectiveDisplayName = "Discovery",
            defaultChannelNumber = "501",
            customChannelNumber = 7,
            effectiveChannelNumber = "7",
            isFavorite = true,
            isHidden = true,
            variantCount = 2,
        )

        assertThat(item.canonicalDisplayName).isEqualTo("Discovery Channel HD")
        assertThat(item.effectiveDisplayName).isEqualTo("Discovery")
        assertThat(item.defaultChannelNumber).isEqualTo("501")
        assertThat(item.customChannelNumber).isEqualTo(7)
        assertThat(item.effectiveChannelNumber).isEqualTo("7")
        assertThat(item.toString()).doesNotContain("Discovery Channel HD")
        assertThat(item.toString()).doesNotContain("Discovery")
    }
}
