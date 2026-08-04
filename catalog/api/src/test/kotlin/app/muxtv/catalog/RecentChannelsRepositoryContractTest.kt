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
    fun `write result distinguishes applied duplicate and unavailable target`() {
        assertThat(RecentChannelWriteResult.entries).containsExactly(
            RecentChannelWriteResult.Applied,
            RecentChannelWriteResult.IgnoredOlderOrDuplicate,
            RecentChannelWriteResult.TargetUnavailable,
        ).inOrder()
    }

    private fun channelSummary(): PlayableChannelSummary = PlayableChannelSummary(
        id = "channel-main",
        displayName = "Channel",
        logoUrl = null,
        groupName = null,
        isFavorite = false,
    )
}
