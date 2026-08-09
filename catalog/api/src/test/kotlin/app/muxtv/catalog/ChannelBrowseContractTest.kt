package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ChannelBrowseContractTest {
    @Test
    fun queryRequiresProfileAndRedactsItFromDiagnostics() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelBrowseQuery(profileId = " ", filter = ChannelBrowseFilter.ALL)
        }

        val query = ChannelBrowseQuery(
            profileId = "private-profile-id",
            filter = ChannelBrowseFilter.FAVORITES,
        )

        assertThat(query.toString()).doesNotContain("private-profile-id")
        assertThat(query.toString()).contains("FAVORITES")
    }

    @Test
    fun browseItemContainsOnlySafeScreenProjection() {
        val item = ChannelBrowseItem(
            channelId = "channel-1",
            displayName = "Новости",
            channelNumber = "7",
            groupTitle = "Эфир",
            isFavorite = true,
            isCurrentPlayback = false,
            currentProgrammeTitle = "Сейчас",
            currentProgrammeEndEpochMillis = 2_000L,
            nextProgrammeTitle = "Далее",
            nextProgrammeStartEpochMillis = 2_000L,
            variantCount = 2,
            guideState = GuideProjectionState.READY,
        )

        assertThat(item.toString()).doesNotContain("Новости")
        assertThat(item.toString()).doesNotContain("Сейчас")
        assertThat(item.toString()).doesNotContain("Далее")
        assertThat(item.variantCount).isEqualTo(2)
    }

    @Test
    fun browseFiltersAreCompleteAndStable() {
        assertThat(ChannelBrowseFilter.entries)
            .containsExactly(
                ChannelBrowseFilter.ALL,
                ChannelBrowseFilter.FAVORITES,
                ChannelBrowseFilter.RECENT,
            )
            .inOrder()
    }
}
