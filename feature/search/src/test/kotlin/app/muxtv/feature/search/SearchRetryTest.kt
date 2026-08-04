package app.muxtv.feature.search

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.ChannelSearchResult
import app.muxtv.catalog.ChannelSearchSnapshot
import app.muxtv.catalog.PlayableChannelSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchRetryTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val store = ViewModelStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `same normalized query can be retried after failure`() = runTest {
        var attempts = 0
        val repository = RecordingRepository { query ->
            attempts++
            if (attempts == 1) {
                flow { throw IllegalStateException("synthetic private failure") }
            } else {
                flowOf(
                    ChannelSearchSnapshot(
                        results = listOf(result(query.normalizedText)),
                        isTruncated = false,
                        nextBoundaryEpochMillis = null,
                    ),
                )
            }
        }
        val viewModel = createViewModel(repository)

        viewModel.setQueryText("  Первый   канал ")
        runCurrent()
        assertThat(viewModel.uiState.value).isEqualTo(SearchUiState.Failed)

        viewModel.retry()
        runCurrent()

        assertThat(repository.queries.map(ChannelSearchQuery::normalizedText))
            .containsExactly("Первый канал", "Первый канал")
            .inOrder()
        assertThat(viewModel.uiState.value).isInstanceOf(SearchUiState.Content::class.java)
    }

    @Test
    fun `retry is a no-op for blank input`() = runTest {
        val repository = RecordingRepository { flowOf(ChannelSearchSnapshot.EMPTY) }
        val viewModel = createViewModel(repository)

        viewModel.retry()
        runCurrent()

        assertThat(repository.queries).isEmpty()
        assertThat(viewModel.uiState.value).isEqualTo(SearchUiState.Idle)
    }

    private fun createViewModel(repository: ChannelSearchRepository): SearchViewModel {
        val factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    repository = repository,
                    profileId = "profile-main",
                    nowEpochMillis = { 1_000L },
                    debounceMillis = 0L,
                )
            }
        }
        return ViewModelProvider.create(store, factory)[SearchViewModel::class]
    }
}

private class RecordingRepository(
    private val response: (ChannelSearchQuery) -> Flow<ChannelSearchSnapshot>,
) : ChannelSearchRepository {
    val queries = mutableListOf<ChannelSearchQuery>()

    override fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot> {
        queries += query
        return response(query)
    }
}

private fun result(name: String): ChannelSearchResult = ChannelSearchResult(
    channel = PlayableChannelSummary(
        channelId = "channel-retry",
        displayName = name,
        logoUrl = null,
        groupTitle = null,
        channelNumber = null,
        isFavorite = false,
        variantCount = 1,
    ),
    currentProgrammeTitle = null,
)
