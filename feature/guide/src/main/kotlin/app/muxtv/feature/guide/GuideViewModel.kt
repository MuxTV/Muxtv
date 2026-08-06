package app.muxtv.feature.guide

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muxtv.catalog.GuideChannelWindow
import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeWindow
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideWindowRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

internal class GuideViewModel(
    private val repository: GuideWindowRepository,
    private val profileId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableUiState = kotlinx.coroutines.flow.MutableStateFlow<GuideUiState>(GuideUiState.Loading)
    val uiState: kotlinx.coroutines.flow.StateFlow<GuideUiState> = mutableUiState

    private var generation: Long = 0L
    private var loadJob: Job? = null

    init {
        require(profileId.isNotBlank())

        viewModelScope.launch {
            repository.observeDataChanges()
                .conflate()
                .collect {
                    reload()
                }
        }
        reload()
    }

    fun reload() {
        generation += 1L
        val requestGeneration = generation
        loadJob?.cancel()
        if (mutableUiState.value !is GuideUiState.Content) {
            mutableUiState.value = GuideUiState.Loading
        }
        loadJob = viewModelScope.launch {
            try {
                loadGeneration(requestGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                publishIfCurrent(requestGeneration, GuideUiState.Failed)
            }
        }
    }

    private suspend fun loadGeneration(requestGeneration: Long) {
        val fromEpochMillis = nowEpochMillis()
        require(fromEpochMillis >= 0L)

        val channelWindow = repository.getChannelWindow(
            GuideChannelWindowQuery(
                profileId = profileId,
                limit = GuideViewportPolicy.CHANNEL_LIMIT,
            ),
        )
        if (!isCurrent(requestGeneration)) return
        if (channelWindow.channels.isEmpty()) {
            publishIfCurrent(requestGeneration, GuideUiState.Empty)
            return
        }

        val programmeLoad = loadCompleteProgrammeWindow(
            requestGeneration = requestGeneration,
            channelWindow = channelWindow,
            fromEpochMillis = fromEpochMillis,
        ) ?: return

        if (!isCurrent(requestGeneration)) return
        if (programmeLoad.window.isTruncated) {
            publishIfCurrent(requestGeneration, GuideUiState.Incomplete)
            return
        }

        val projections = programmeLoad.window.channels.associateBy { it.canonicalChannelId }
        val expectedIds = channelWindow.channels.map { it.channelId }
        if (projections.keys != expectedIds.toSet()) {
            publishIfCurrent(requestGeneration, GuideUiState.Incomplete)
            return
        }

        val rows = channelWindow.channels.map { channel ->
            val projection = requireNotNull(projections[channel.channelId])
            GuideRow(
                channel = channel,
                state = projection.state,
                programmes = projection.programmes,
            )
        }
        publishIfCurrent(
            requestGeneration,
            GuideUiState.Content(
                rows = rows,
                viewport = GuideViewport(
                    fromEpochMillis = fromEpochMillis,
                    toEpochMillis = programmeLoad.query.toEpochMillis,
                    hasMoreChannels = channelWindow.isTruncated,
                ),
            ),
        )
    }

    private suspend fun loadCompleteProgrammeWindow(
        requestGeneration: Long,
        channelWindow: GuideChannelWindow,
        fromEpochMillis: Long,
    ): ProgrammeLoad? {
        val channelIds = channelWindow.channels.map { it.channelId }
        for (attemptIndex in 0 until GuideViewportPolicy.MAX_PROGRAMME_ATTEMPTS) {
            if (!isCurrent(requestGeneration)) return null
            val query = GuideProgrammeWindowQuery(
                profileId = profileId,
                canonicalChannelIds = channelIds,
                fromEpochMillis = fromEpochMillis,
                toEpochMillis = fromEpochMillis + GuideViewportPolicy.timeSpanMillis(attemptIndex),
            )
            val window = repository.getProgrammeWindow(query)
            if (!isCurrent(requestGeneration)) return null
            if (!window.isTruncated) {
                return ProgrammeLoad(query = query, window = window)
            }
        }
        return ProgrammeLoad(
            query = GuideProgrammeWindowQuery(
                profileId = profileId,
                canonicalChannelIds = channelIds,
                fromEpochMillis = fromEpochMillis,
                toEpochMillis = fromEpochMillis +
                    GuideViewportPolicy.timeSpanMillis(GuideViewportPolicy.MAX_PROGRAMME_ATTEMPTS - 1),
            ),
            window = GuideProgrammeWindow(channels = emptyList(), isTruncated = true),
        )
    }

    private fun publishIfCurrent(
        requestGeneration: Long,
        state: GuideUiState,
    ) {
        if (isCurrent(requestGeneration)) {
            mutableUiState.value = state
        }
    }

    private fun isCurrent(requestGeneration: Long): Boolean = generation == requestGeneration

    private data class ProgrammeLoad(
        val query: GuideProgrammeWindowQuery,
        val window: GuideProgrammeWindow,
    )
}
