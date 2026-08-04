package app.muxtv.database

import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomRecentChannelsRepository(
    private val dao: RecentChannelsDao,
) : RecentChannelsRepository {
    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> =
        dao.observeRecent(
            profileId = query.profileId,
            limit = query.limit,
        ).map { rows -> rows.map(RecentChannelRow::toRecentChannel) }

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())
        require(successfulAtEpochMillis >= 0L)

        return when (
            dao.recordSuccessfulPlayback(
                profileId = profileId,
                channelId = channelId,
                successfulAtEpochMillis = successfulAtEpochMillis,
                retentionLimit = RecentChannelsQuery.MAX_LIMIT,
            )
        ) {
            RecentWriteResult.Applied -> RecentChannelWriteResult.Applied
            RecentWriteResult.IgnoredOlderOrDuplicate ->
                RecentChannelWriteResult.IgnoredOlderOrDuplicate
            RecentWriteResult.ProfileUnavailable -> RecentChannelWriteResult.ProfileUnavailable
        }
    }
}

private fun RecentChannelRow.toRecentChannel(): RecentChannel = RecentChannel(
    channel = PlayableChannelSummary(
        channelId = channelId,
        displayName = displayName,
        logoUrl = logoUrl,
        groupTitle = groupTitle,
        channelNumber = channelNumber,
        isFavorite = isFavorite,
        variantCount = variantCount,
    ),
    lastSuccessfulPlaybackAtEpochMillis = lastSuccessfulPlaybackAtEpochMillis,
)
