package app.muxtv.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ChannelsFilter {
    ALL,
    FAVORITES,
    RECENT,
}

internal sealed interface ChannelsUiState {
    data object Loading : ChannelsUiState
    data object Empty : ChannelsUiState
    data object Failed : ChannelsUiState

    data class Content(
        val rows: List<ChannelRowProjection>,
    ) : ChannelsUiState
}

internal class ChannelsViewModel(
    private val playbackCatalog: PlaybackCatalog,
    private val recentChannelsRepository: RecentChannelsRepository,
    private val epgGuideRepository: EpgGuideRepository,
    private val playbackSessionStateSource: PlaybackSessionStateSource,
    private val profileId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<ChannelsUiState>(ChannelsUiState.Loading)
    val uiState: StateFlow<ChannelsUiState> = mutableUiState.asStateFlow()

    private val mutableFilter = MutableStateFlow(ChannelsFilter.ALL)
    val filter: StateFlow<ChannelsFilter> = mutableFilter.asStateFlow()

    private val guideReloadMutex = Mutex()
    private var currentChannels: List<PlayableChannelSummary> = emptyList()
    private var currentGuide: List<ChannelNowNext> = emptyList()
    private var currentPlaybackSessionState: PlaybackSessionState = PlaybackSessionState.Idle
    private var guideGeneration: Long = 0
    private var catalogObserverJob: Job? = null
    private var guideObserverJob: Job? = null
    private var boundaryJob: Job? = null

    init {
        require(profileId.isNotBlank())
        observeChannels(ChannelsFilter.ALL)
        observePlaybackSession()
    }

    fun setFilter(filter: ChannelsFilter) {
        if (mutableFilter.value == filter) return
        mutableFilter.value = filter
        observeChannels(filter)
    }

    private fun observeChannels(filter: ChannelsFilter) {
        catalogObserverJob?.cancel()
        invalidateGuideGeneration()
        currentChannels = emptyList()
        currentGuide = emptyList()
        mutableUiState.value = ChannelsUiState.Loading
        catalogObserverJob = viewModelScope.launch {
            channelFlow(filter)
                .catch {
                    invalidateGuideGeneration()
                    currentChannels = emptyList()
                    currentGuide = emptyList()
                    mutableUiState.value = ChannelsUiState.Failed
                }
                .collect { channels ->
                    acceptChannels(channels)
                }
        }
    }

    private fun channelFlow(filter: ChannelsFilter): Flow<List<PlayableChannelSummary>> = when (filter) {
        ChannelsFilter.ALL -> playbackCatalog.observeChannels(
            ChannelQuery(
                profileId = profileId,
                favoritesOnly = false,
                limit = CHANNEL_LIMIT,
            ),
        )

        ChannelsFilter.FAVORITES -> playbackCatalog.observeChannels(
            ChannelQuery(
                profileId = profileId,
                favoritesOnly = true,
                limit = CHANNEL_LIMIT,
            ),
        )

        ChannelsFilter.RECENT -> recentChannelsRepository.observeRecent(
            RecentChannelsQuery(profileId = profileId),
        ).map { recent -> recent.map { item -> item.channel } }
    }

    private fun observePlaybackSession() {
        viewModelScope.launch {
            playbackSessionStateSource.playbackSessionState.collect { state ->
                currentPlaybackSessionState = state
                if (currentChannels.isNotEmpty()) publishRows()
            }
        }
    }

    private fun acceptChannels(channels: List<PlayableChannelSummary>) {
        val previousIds = currentChannels.mapTo(linkedSetOf(), PlayableChannelSummary::channelId)
        val newIds = channels.mapTo(linkedSetOf(), PlayableChannelSummary::channelId)
        currentChannels = channels

        if (channels.isEmpty()) {
            invalidateGuideGeneration()
            currentGuide = emptyList()
            mutableUiState.value = ChannelsUiState.Empty
            return
        }

        if (previousIds != newIds) {
            invalidateGuideGeneration()
            currentGuide = emptyList()
            startGuideObserver(
                generation = guideGeneration,
                channelIds = channels.map(PlayableChannelSummary::channelId),
            )
        }

        publishRows()
    }

    private fun invalidateGuideGeneration() {
        guideGeneration++
        guideObserverJob?.cancel()
        guideObserverJob = null
        boundaryJob?.cancel()
        boundaryJob = null
    }

    private fun startGuideObserver(
        generation: Long,
        channelIds: List<String>,
    ) {
        guideObserverJob = viewModelScope.launch {
            epgGuideRepository.observeDataChanges()
                .onStart { emit(Unit) }
                .conflate()
                .catch {
                    if (isCurrentGuideRequest(generation, channelIds)) {
                        currentGuide = emptyList()
                        boundaryJob?.cancel()
                        boundaryJob = null
                        publishRows()
                    }
                }
                .collect {
                    reloadGuide(generation, channelIds)
                }
        }
    }

    private suspend fun reloadGuide(
        generation: Long,
        channelIds: List<String>,
    ) {
        guideReloadMutex.withLock {
            if (!isCurrentGuideRequest(generation, channelIds)) return

            val guide = try {
                epgGuideRepository.getNowNext(
                    NowNextQuery(
                        profileId = profileId,
                        canonicalChannelIds = channelIds,
                        nowEpochMillis = nowEpochMillis(),
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }

            if (!isCurrentGuideRequest(generation, channelIds)) return
            currentGuide = guide
            val rows = publishRows()
            scheduleNextBoundary(generation, channelIds, rows)
        }
    }

    private fun publishRows(): List<ChannelRowProjection> {
        val channels = currentChannels
        if (channels.isEmpty()) {
            mutableUiState.value = ChannelsUiState.Empty
            return emptyList()
        }

        val rows = projectChannelRows(
            channels = channels,
            guide = currentGuide,
            playbackSessionState = currentPlaybackSessionState,
        )
        mutableUiState.value = ChannelsUiState.Content(rows = rows)
        return rows
    }

    private fun scheduleNextBoundary(
        generation: Long,
        channelIds: List<String>,
        rows: List<ChannelRowProjection>,
    ) {
        boundaryJob?.cancel()
        boundaryJob = null

        val now = nowEpochMillis()
        val boundary = earliestFutureGuideBoundary(
            rows = rows,
            nowEpochMillis = now,
        ) ?: return

        boundaryJob = viewModelScope.launch {
            val waitMillis = (boundary - nowEpochMillis()).coerceAtLeast(0)
            if (waitMillis > 0) delay(waitMillis)
            boundaryJob = null
            reloadGuide(generation, channelIds)
        }
    }

    private fun isCurrentGuideRequest(
        generation: Long,
        channelIds: List<String>,
    ): Boolean =
        generation == guideGeneration &&
            currentChannels.mapTo(linkedSetOf(), PlayableChannelSummary::channelId) == channelIds.toSet()

    private companion object {
        const val CHANNEL_LIMIT = NowNextQuery.MAX_CHANNEL_IDS
    }
}
