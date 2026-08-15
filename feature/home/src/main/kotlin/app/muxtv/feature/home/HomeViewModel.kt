package app.muxtv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface HomeSourceState {
    data object Loading : HomeSourceState
    data object Present : HomeSourceState
    data object Empty : HomeSourceState
    data object Failed : HomeSourceState
}

class HomeViewModel(
    recentChannelsRepository: RecentChannelsRepository,
    private val epgGuideRepository: EpgGuideRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    hasSources: Flow<Boolean>,
    private val profileId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    val sourceState: StateFlow<HomeSourceState> = hasSources
        .map { present ->
            if (present) HomeSourceState.Present else HomeSourceState.Empty
        }
        .onStart { emit(HomeSourceState.Loading) }
        .catch { _: Throwable -> emit(HomeSourceState.Failed) }
        .stateIn(
            viewModelScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            HomeSourceState.Loading,
        )

    val playbackSessionState: StateFlow<PlaybackSessionState> =
        playbackSessionStateSource.playbackSessionState

    val recent: StateFlow<List<RecentChannel>> = recentChannelsRepository
        .observeRecent(RecentChannelsQuery(profileId = profileId, limit = HOME_RAIL_LIMIT))
        .catch { _: Throwable -> emit(emptyList()) }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    private val _nowNext = MutableStateFlow<Map<String, ChannelNowNext>>(emptyMap())
    val nowNext: StateFlow<Map<String, ChannelNowNext>> = _nowNext.asStateFlow()

    private val nowNextIds = MutableStateFlow<List<String>>(emptyList())
    private var nowNextJob: Job? = null

    init {
        require(profileId.isNotBlank())
        viewModelScope.launch {
            epgGuideRepository.observeDataChanges().collect {
                launchNowNextLoop(nowNextIds.value)
            }
        }
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
     * Forces an immediate bounded refresh. A new request cancels the previous
     * owner so stale work can never publish over a newer Home identity set.
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
            _nowNext.value = emptyMap()
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
                    _nowNext.value = result.associateBy { it.canonicalChannelId }
                    delay(nextRefreshDelayMillis(result, queryNow))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _nowNext.value = emptyMap()
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
