package app.muxtv.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class RecentChannelsRepositoryContractTest {
    @Test
    fun `query defaults to bounded recent window`() {
        val query = RecentChannelsQuery(profileId = "profile-main")

        assertThat(query.limit).isEqualTo(RecentChannelsQuery.DEFAULT_LIMIT)
        assertThat(RecentChannelsQuery.DEFAULT_LIMIT).isEqualTo(20)
        assertThat(RecentChannelsQuery.MAX_LIMIT).isEqualTo(50)
    }

    @Test
    fun `query rejects blank profile and out of range limits`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecentChannelsQuery(profileId = "   ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecentChannelsQuery(profileId = "profile-main", limit = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecentChannelsQuery(
                profileId = "profile-main",
                limit = RecentChannelsQuery.MAX_LIMIT + 1,
            )
        }
    }

    @Test
    fun `recent channel rejects negative success timestamp`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecentChannel(
                channel = channelSummary(),
                lastSuccessfulPlaybackAtEpochMillis = -1L,
            )
        }
    }

    @Test
    fun `query and result diagnostics redact identities and channel metadata`() {
        val queryText = RecentChannelsQuery(profileId = "private-profile-id").toString()
        val recentText = RecentChannel(
            channel = channelSummary(
                channelId = "private-channel-id",
                displayName = "Private Channel",
            ),
            lastSuccessfulPlaybackAtEpochMillis = 42_000L,
        ).toString()

        assertThat(queryText).doesNotContain("private-profile-id")
        assertThat(queryText).contains("profileId=<redacted>")
        assertThat(recentText).doesNotContain("private-channel-id")
        assertThat(recentText).doesNotContain("Private Channel")
        assertThat(recentText).contains("channelId=<redacted>")
        assertThat(recentText).contains("lastSuccessfulPlaybackAtEpochMillis=42000")
    }

    @Test
    fun `write result distinguishes applied duplicate and unavailable profile`() {
        assertThat(RecentChannelWriteResult.entries).containsExactly(
            RecentChannelWriteResult.Applied,
            RecentChannelWriteResult.IgnoredOlderOrDuplicate,
            RecentChannelWriteResult.ProfileUnavailable,
        ).inOrder()
    }

    private fun channelSummary(
        channelId: String = "channel-main",
        displayName: String = "Channel",
    ): PlayableChannelSummary = PlayableChannelSummary(
        channelId = channelId,
        displayName = displayName,
        logoUrl = null,
        groupTitle = null,
        channelNumber = null,
        isFavorite = false,
        variantCount = 1,
    )
}
