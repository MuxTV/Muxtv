package app.muxtv

import androidx.paging.PagingData
import app.muxtv.catalog.ChannelBrowseFilter
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapLatest

@OptIn(ExperimentalCoroutinesApi::class)
internal class TestChannelBrowseRepository(
    private val playbackCatalog: PlaybackCatalog,
    private val recentChannelsRepository: RecentChannelsRepository,
    private val epgGuideRepository: EpgGuideRepository,
) : ChannelBrowseRepository {
    override fun pages(query: ChannelBrowseQuery): Flow<PagingData<ChannelBrowseItem>> {
        val channels = when (query.filter) {
            ChannelBrowseFilter.ALL -> playbackCatalog.observeChannels(
                ChannelQuery(query.profileId, favoritesOnly = false, limit = 500),
            )

            ChannelBrowseFilter.FAVORITES -> playbackCatalog.observeChannels(
                ChannelQuery(query.profileId, favoritesOnly = true, limit = 500),
            )

            ChannelBrowseFilter.RECENT -> recentChannelsRepository.observeRecent(
                RecentChannelsQuery(query.profileId, limit = RecentChannelsQuery.MAX_LIMIT),
            ).mapLatest { rows -> rows.map { it.channel } }
        }
        return channels.mapLatest { rows ->
            val guide = if (rows.isEmpty()) {
                emptyList()
            } else {
                epgGuideRepository.getNowNext(
                    NowNextQuery(
                        profileId = query.profileId,
                        canonicalChannelIds = rows.map(PlayableChannelSummary::channelId),
                        nowEpochMillis = 1_000L,
                    ),
                )
            }.associateBy(ChannelNowNext::canonicalChannelId)
            PagingData.from(rows.map { row -> row.toBrowseItem(guide[row.channelId]) })
        }
    }
}

private fun PlayableChannelSummary.toBrowseItem(guide: ChannelNowNext?) = ChannelBrowseItem(
    channelId = channelId,
    displayName = displayName,
    channelNumber = channelNumber,
    groupTitle = groupTitle,
    isFavorite = isFavorite,
    isCurrentPlayback = false,
    currentProgrammeTitle = guide?.current?.title,
    currentProgrammeEndEpochMillis = guide?.current?.endEpochMillis,
    nextProgrammeTitle = guide?.next?.title,
    nextProgrammeStartEpochMillis = guide?.next?.startEpochMillis,
    variantCount = variantCount,
    guideState = guide?.state ?: GuideProjectionState.NO_GUIDE,
)
