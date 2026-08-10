package app.muxtv.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import app.muxtv.catalog.ChannelBrowseFilter
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest

internal enum class ChannelsFilter {
    ALL,
    FAVORITES,
    RECENT,
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChannelsViewModel(
    channelBrowseRepository: ChannelBrowseRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    private val profileId: String,
) : ViewModel() {
    private val mutableFilter = MutableStateFlow(ChannelsFilter.ALL)
    val filter: StateFlow<ChannelsFilter> = mutableFilter.asStateFlow()

    val rows: Flow<PagingData<ChannelBrowseItem>> = mutableFilter
        .flatMapLatest { filter ->
            channelBrowseRepository.pages(
                ChannelBrowseQuery(
                    profileId = profileId,
                    filter = filter.toBrowseFilter(),
                ),
            )
        }
        .combine(playbackSessionStateSource.playbackSessionState) { pagingData, playback ->
            pagingData.map { item -> item.applyPlaybackState(playback) }
        }
        .cachedIn(viewModelScope)

    init {
        require(profileId.isNotBlank())
    }

    fun setFilter(filter: ChannelsFilter) {
        mutableFilter.value = filter
    }
}

private fun ChannelsFilter.toBrowseFilter(): ChannelBrowseFilter = when (this) {
    ChannelsFilter.ALL -> ChannelBrowseFilter.ALL
    ChannelsFilter.FAVORITES -> ChannelBrowseFilter.FAVORITES
    ChannelsFilter.RECENT -> ChannelBrowseFilter.RECENT
}

internal fun ChannelBrowseItem.applyPlaybackState(state: PlaybackSessionState): ChannelBrowseItem =
    copy(isCurrentPlayback = state.channelId == channelId)
