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
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
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
    private var nowNextJob: Job? = null

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
            epgGuideRepository.observeDataChanges().collect {
                launchNowNextLoop(nowNextIds.value)
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
            launchNowNextLoop(normalized)
        }
    }

    /**
     * Forces an immediate refresh for the supplied bounded identity set.
     * Starting a new refresh cancels the previous owner so a stale completion
     * cannot overwrite a newer visible window.
     */
    fun refreshNowNext(channelIds: List<String>) {
        val normalized = channelIds.distinct().filter(String::isNotBlank)
            .take(NowNextQuery.MAX_CHANNEL_IDS)
        nowNextIds.value = normalized
        launchNowNextLoop(normalized)
    }

    private fun launchNowNextLoop(channelIds: List<String>) {
        nowNextJob?.cancel()
        if (channelIds.isEmpty()) {
            _nowNextById.value = emptyMap()
            nowNextJob = null
            return
        }
        nowNextJob = viewModelScope.launch {
            while (currentCoroutineContext().isActive) {
                val queryNow = nowEpochMillis()
                try {
                    val result = epgGuideRepository.getNowNext(
                        NowNextQuery(
                            profileId = profileId,
                            canonicalChannelIds = channelIds,
                            nowEpochMillis = queryNow,
                        ),
                    )
                    currentCoroutineContext().ensureActive()
                    _nowNextById.value = result.associateBy { it.canonicalChannelId }
                    delay(nextRefreshDelayMillis(result, queryNow))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _nowNextById.value = emptyMap()
                    delay(MAX_REFRESH_DELAY_MILLIS)
                }
            }
        }
    }

    private companion object {
        const val MIN_REFRESH_DELAY_MILLIS = 1_000L
        const val MAX_REFRESH_DELAY_MILLIS = 60_000L

        fun nextRefreshDelayMillis(result: List<ChannelNowNext>, nowEpochMillis: Long): Long {
            val nextBoundary = result.asSequence()
                .mapNotNull(ChannelNowNext::nextBoundaryEpochMillis)
                .filter { it > nowEpochMillis }
                .minOrNull()
            return if (nextBoundary == null) {
                MAX_REFRESH_DELAY_MILLIS
            } else {
                (nextBoundary - nowEpochMillis)
                    .coerceIn(MIN_REFRESH_DELAY_MILLIS, MAX_REFRESH_DELAY_MILLIS)
            }
        }
    }
}

private fun ChannelsFilter.toBrowseFilter(): ChannelBrowseFilter = when (this) {
    ChannelsFilter.ALL -> ChannelBrowseFilter.ALL
    ChannelsFilter.FAVORITES -> ChannelBrowseFilter.FAVORITES
    ChannelsFilter.RECENT -> ChannelBrowseFilter.RECENT
}
