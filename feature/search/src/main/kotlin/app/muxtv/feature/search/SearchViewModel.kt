package app.muxtv.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.ChannelSearchSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
internal class SearchViewModel(
    private val repository: ChannelSearchRepository,
    private val profileId: String,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val debounceMillis: Long = SEARCH_DEBOUNCE_MILLIS,
) : ViewModel() {
    private val mutableQueryText = MutableStateFlow("")
    val queryText: StateFlow<String> = mutableQueryText.asStateFlow()

    private val mutableUiState = MutableStateFlow<SearchUiState>(SearchUiState.Idle)
    val uiState: StateFlow<SearchUiState> = mutableUiState.asStateFlow()

    private val refreshGeneration = MutableStateFlow(0L)
    private var boundaryJob: Job? = null

    init {
        require(profileId.isNotBlank())
        require(debounceMillis >= 0)

        viewModelScope.launch {
            mutableQueryText
                .map(::normalizeForSearch)
                .distinctUntilChanged()
                .flatMapLatest(::observeNormalizedQuery)
                .collect(::accept)
        }
    }

    fun setQueryText(value: String) {
        if (mutableQueryText.value == value) return

        val previousNormalized = normalizeForSearch(mutableQueryText.value)
        val nextNormalized = normalizeForSearch(value)
        if (previousNormalized != nextNormalized) {
            cancelBoundaryRefresh()
            mutableUiState.value = if (nextNormalized.isEmpty()) {
                SearchUiState.Idle
            } else {
                SearchUiState.Loading
            }
        }

        mutableQueryText.value = value
    }

    fun retry() {
        if (normalizeForSearch(mutableQueryText.value).isEmpty()) return

        cancelBoundaryRefresh()
        mutableUiState.value = SearchUiState.Loading
        refreshGeneration.update { generation -> generation + 1L }
    }

    private fun observeNormalizedQuery(normalizedQuery: String): Flow<SearchEmission> {
        cancelBoundaryRefresh()
        if (normalizedQuery.isEmpty()) return flowOf(SearchEmission.Idle)

        val firstGeneration = refreshGeneration.value
        return refreshGeneration.flatMapLatest { generation ->
            flow {
                if (generation == firstGeneration && debounceMillis > 0) {
                    delay(debounceMillis)
                }
                emit(Unit)
            }.flatMapLatest {
                repository.observe(
                    ChannelSearchQuery(
                        profileId = profileId,
                        text = normalizedQuery,
                        nowEpochMillis = nowEpochMillis(),
                    ),
                )
                    .map<ChannelSearchSnapshot, SearchEmission> { snapshot ->
                        SearchEmission.Snapshot(
                            normalizedQuery = normalizedQuery,
                            snapshot = snapshot,
                        )
                    }
                    .onStart {
                        emit(SearchEmission.Loading(normalizedQuery))
                    }
                    .catch { error ->
                        if (error is CancellationException) throw error
                        emit(SearchEmission.Failed(normalizedQuery))
                    }
            }
        }
    }

    private fun accept(emission: SearchEmission) {
        when (emission) {
            SearchEmission.Idle -> {
                cancelBoundaryRefresh()
                mutableUiState.value = SearchUiState.Idle
            }

            is SearchEmission.Loading -> {
                if (!isCurrent(emission.normalizedQuery)) return
                if (mutableUiState.value !is SearchUiState.Content) {
                    mutableUiState.value = SearchUiState.Loading
                }
            }

            is SearchEmission.Failed -> {
                if (!isCurrent(emission.normalizedQuery)) return
                cancelBoundaryRefresh()
                mutableUiState.value = SearchUiState.Failed
            }

            is SearchEmission.Snapshot -> {
                if (!isCurrent(emission.normalizedQuery)) return
                val rows = emission.snapshot.results.map { result ->
                    result.toSearchRowProjection()
                }
                mutableUiState.value = if (rows.isEmpty()) {
                    SearchUiState.Empty
                } else {
                    SearchUiState.Content(
                        rows = rows,
                        isTruncated = emission.snapshot.isTruncated,
                    )
                }
                scheduleBoundaryRefresh(emission.snapshot.nextBoundaryEpochMillis)
            }
        }
    }

    private fun isCurrent(normalizedQuery: String): Boolean =
        normalizeForSearch(mutableQueryText.value) == normalizedQuery

    private fun scheduleBoundaryRefresh(nextBoundaryEpochMillis: Long?) {
        cancelBoundaryRefresh()
        val boundary = nextBoundaryEpochMillis ?: return
        val now = nowEpochMillis()
        if (boundary <= now) return

        boundaryJob = viewModelScope.launch {
            delay(boundary - now)
            boundaryJob = null
            refreshGeneration.update { generation -> generation + 1L }
        }
    }

    private fun cancelBoundaryRefresh() {
        boundaryJob?.cancel()
        boundaryJob = null
    }

    private fun normalizeForSearch(value: String): String =
        ChannelSearchQuery(
            profileId = profileId,
            text = value,
            nowEpochMillis = 0L,
        ).normalizedText

    private sealed interface SearchEmission {
        data object Idle : SearchEmission

        data class Loading(
            val normalizedQuery: String,
        ) : SearchEmission {
            override fun toString(): String = "Loading(query=<redacted>)"
        }

        data class Failed(
            val normalizedQuery: String,
        ) : SearchEmission {
            override fun toString(): String = "Failed(query=<redacted>)"
        }

        data class Snapshot(
            val normalizedQuery: String,
            val snapshot: ChannelSearchSnapshot,
        ) : SearchEmission {
            override fun toString(): String =
                "Snapshot(query=<redacted>, resultCount=${snapshot.results.size}, " +
                    "isTruncated=${snapshot.isTruncated}, " +
                    "boundaryPresent=${snapshot.nextBoundaryEpochMillis != null})"
        }
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 275L
    }
}
