package app.muxtv.feature.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val epgGuideRepository: EpgGuideRepository,
    private val playbackSessionStateSource: PlaybackSessionStateSource,
    private val profileId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow<ChannelsUiState>(ChannelsUiState.Loading)
    val uiState: StateFlow<ChannelsUiState> = mutableUiState.asStateFlow()

    private val guideReloadMutex = Mutex()
    private var currentChannels: List<PlayableChannelSummary> = emptyList()
    private var currentGuide: List<ChannelNowNext> = emptyList()
    private var currentPlaybackSessionState: PlaybackSessionState = PlaybackSessionState.Idle
    private var guideGeneration: Long = 0
    private var guideObserverJob: Job? = null
    private var boundaryJob: Job? = null

    init {
        require(profileId.isNotBlank())
        observeChannels()
        observePlaybackSession()
    }

    private fun observeChannels() {
        viewModelScope.launch {
            playbackCatalog.observeChannels(
                ChannelQuery(
                    profileId = profileId,
                    limit = CHANNEL_LIMIT,
                ),
            )
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
