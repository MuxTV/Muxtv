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
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

internal enum class ChannelsFilter {
    ALL,
    FAVORITES,
    RECENT,
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class ChannelsViewModel(
    channelBrowseRepository: ChannelBrowseRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    private val epgGuideRepository: EpgGuideRepository,
    private val profileId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableFilter = MutableStateFlow(ChannelsFilter.ALL)
    val filter: StateFlow<ChannelsFilter> = mutableFilter.asStateFlow()

    private val _nowNextById = MutableStateFlow<Map<String, ChannelNowNext>>(emptyMap())
    val nowNextById: StateFlow<Map<String, ChannelNowNext>> = _nowNextById.asStateFlow()

    private val nowNextIds = MutableStateFlow<List<String>>(emptyList())

    val rows: Flow<PagingData<ChannelRowUiModel>> = combine(
        mutableFilter.flatMapLatest { filter ->
            channelBrowseRepository.pages(
                ChannelBrowseQuery(
                    profileId = profileId,
                    filter = filter.toBrowseFilter(),
                ),
            )
        },
        _nowNextById,
        playbackSessionStateSource.playbackSessionState,
    ) { pagingData: PagingData<ChannelBrowseItem>, nowNext: Map<String, ChannelNowNext>, playback: PlaybackSessionState ->
        pagingData.map { item ->
            buildChannelRow(
                item = item,
                nowNext = nowNext[item.channelId],
                playback = playback,
                nowEpochMillis = nowEpochMillis(),
            )
        }
    }.cachedIn(viewModelScope)

    init {
        require(profileId.isNotBlank())
        viewModelScope.launch {
            while (true) {
                refreshNowNext(nowNextIds.value)
                delay(NOW_NEXT_REFRESH_MILLIS)
            }
        }
    }

    fun setFilter(filter: ChannelsFilter) {
        mutableFilter.value = filter
    }

    fun setNowNextIds(channelIds: List<String>) {
        val normalized = channelIds.distinct().filter(String::isNotBlank)
            .take(NowNextQuery.MAX_CHANNEL_IDS)
        if (normalized != nowNextIds.value) {
            nowNextIds.value = normalized
        }
    }

    fun refreshNowNext(channelIds: List<String>) {
        if (channelIds.isEmpty()) {
            _nowNextById.value = emptyMap()
            return
        }
        viewModelScope.launch {
            try {
                val query = NowNextQuery(
                    profileId = profileId,
                    canonicalChannelIds = channelIds,
                    nowEpochMillis = nowEpochMillis(),
                )
                _nowNextById.value = epgGuideRepository.getNowNext(query)
                    .associateBy { it.canonicalChannelId }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _nowNextById.value = emptyMap()
            }
        }
    }

    private companion object {
        const val NOW_NEXT_REFRESH_MILLIS = 60_000L
    }
}

private fun ChannelsFilter.toBrowseFilter(): ChannelBrowseFilter = when (this) {
    ChannelsFilter.ALL -> ChannelBrowseFilter.ALL
    ChannelsFilter.FAVORITES -> ChannelBrowseFilter.FAVORITES
    ChannelsFilter.RECENT -> ChannelBrowseFilter.RECENT
}
