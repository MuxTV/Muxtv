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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val viewModelStores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        viewModelStores.forEach(ViewModelStore::clear)
        viewModelStores.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `blank input remains idle and never queries repository`() = runTest {
        val repository = FakeSearchRepository()
        val viewModel = createViewModel(repository)

        viewModel.setQueryText(" \t\n ")
        advanceTimeBy(500L)
        runCurrent()

        assertThat(viewModel.uiState.value).isEqualTo(SearchUiState.Idle)
        assertThat(repository.queries).isEmpty()
    }

    @Test
    fun `rapid typing starts only the final normalized query after debounce`() = runTest {
        val repository = FakeSearchRepository()
        val viewModel = createViewModel(repository)

        viewModel.setQueryText("Р")
        advanceTimeBy(100L)
        viewModel.setQueryText("  Россия   1 ")
        advanceTimeBy(274L)
        runCurrent()
        assertThat(repository.queries).isEmpty()

        advanceTimeBy(2L)
        runCurrent()

        assertThat(repository.queries.map(ChannelSearchQuery::normalizedText))
            .containsExactly("Россия 1")
    }

    @Test
    fun `new input cancels the previous repository collection immediately`() = runTest {
        val cancelledQueries = mutableListOf<String>()
        val repository = FakeSearchRepository { query ->
            flow {
                try {
                    awaitCancellation()
                } finally {
                    cancelledQueries += query.normalizedText
                }
            }
        }
        val viewModel = createViewModel(repository, debounceMillis = 0L)

        viewModel.setQueryText("Alpha")
        runCurrent()
        assertThat(repository.queries.map(ChannelSearchQuery::normalizedText))
            .containsExactly("Alpha")

        viewModel.setQueryText("Beta")
        runCurrent()

        assertThat(cancelledQueries).contains("Alpha")
        assertThat(repository.queries.map(ChannelSearchQuery::normalizedText))
            .containsExactly("Alpha", "Beta").inOrder()
    }

    @Test
    fun `snapshot projects bounded rows and truncation`() = runTest {
        val repository = FakeSearchRepository {
            flowOf(
                snapshot(
                    results = listOf(
                        result(
                            id = "channel-a",
                            number = "1",
                            name = "Россия 1",
                            group = "Новости",
                            favorite = true,
                            programme = "Вести",
                        ),
                    ),
                    truncated = true,
                ),
            )
        }
        val viewModel = createViewModel(repository, debounceMillis = 0L)

        viewModel.setQueryText("Россия")
        runCurrent()

        val state = viewModel.uiState.value as SearchUiState.Content
        assertThat(state.isTruncated).isTrue()
        assertThat(state.rows).hasSize(1)
        assertThat(state.rows.single().channelId).isEqualTo("channel-a")
        assertThat(state.rows.single().channelNumber).isEqualTo("1")
        assertThat(state.rows.single().displayName).isEqualTo("Россия 1")
        assertThat(state.rows.single().groupTitle).isEqualTo("Новости")
        assertThat(state.rows.single().isFavorite).isTrue()
        assertThat(state.rows.single().currentProgrammeTitle).isEqualTo("Вести")
    }

    @Test
    fun `empty snapshot and ordinary failure have explicit states`() = runTest {
        val repository = FakeSearchRepository { query ->
            when (query.normalizedText) {
                "empty" -> flowOf(ChannelSearchSnapshot.EMPTY)
                else -> flow { throw IllegalStateException("synthetic provider detail") }
            }
        }
        val viewModel = createViewModel(repository, debounceMillis = 0L)

        viewModel.setQueryText("empty")
        runCurrent()
        assertThat(viewModel.uiState.value).isEqualTo(SearchUiState.Empty)

        viewModel.setQueryText("failed")
        runCurrent()
        assertThat(viewModel.uiState.value).isEqualTo(SearchUiState.Failed)
    }

    @Test
    fun `programme boundary reissues the same query once with fresh time`() = runTest {
        var requestCount = 0
        val repository = FakeSearchRepository {
            val boundary = if (requestCount == 0) 1_100L else null
            requestCount++
            flowOf(
                snapshot(
                    results = listOf(result(id = "channel-a", name = "Alpha")),
                    nextBoundaryEpochMillis = boundary,
                ),
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            debounceMillis = 0L,
            nowEpochMillis = { 1_000L + testScheduler.currentTime },
        )

        viewModel.setQueryText("Alpha")
        runCurrent()
        assertThat(repository.queries).hasSize(1)
        assertThat(repository.queries.single().nowEpochMillis).isEqualTo(1_000L)

        advanceTimeBy(99L)
        runCurrent()
        assertThat(repository.queries).hasSize(1)

        advanceTimeBy(2L)
        runCurrent()

        assertThat(repository.queries).hasSize(2)
        assertThat(repository.queries.map(ChannelSearchQuery::normalizedText))
            .containsExactly("Alpha", "Alpha").inOrder()
        assertThat(repository.queries.last().nowEpochMillis).isEqualTo(1_101L)
    }

    @Test
    fun `changing query cancels the old programme boundary`() = runTest {
        val repository = FakeSearchRepository { query ->
            flowOf(
                snapshot(
                    results = listOf(result(id = query.normalizedText, name = query.normalizedText)),
                    nextBoundaryEpochMillis = if (query.normalizedText == "Alpha") 1_100L else null,
                ),
            )
        }
        val viewModel = createViewModel(
            repository = repository,
            debounceMillis = 0L,
            nowEpochMillis = { 1_000L + testScheduler.currentTime },
        )

        viewModel.setQueryText("Alpha")
        runCurrent()
        advanceTimeBy(50L)
        viewModel.setQueryText("Beta")
        runCurrent()
        advanceTimeBy(100L)
        runCurrent()

        assertThat(repository.queries.map(ChannelSearchQuery::normalizedText))
            .containsExactly("Alpha", "Beta").inOrder()
    }

    @Test
    fun `presentation diagnostics redact channel and programme text`() {
        val row = result(
            id = "secret-id",
            number = "77",
            name = "Секретный канал",
            group = "Секретная группа",
            programme = "Секретная программа",
        ).toSearchRowProjection()
        val state = SearchUiState.Content(rows = listOf(row), isTruncated = false)

        assertThat(row.toString()).doesNotContain("secret-id")
        assertThat(row.toString()).doesNotContain("Секретный")
        assertThat(state.toString()).doesNotContain("Секретный")
        assertThat(state.toString()).contains("resultCount=1")
    }

    private fun createViewModel(
        repository: ChannelSearchRepository,
        debounceMillis: Long = 275L,
        nowEpochMillis: () -> Long = { 1_000L },
    ): SearchViewModel {
        val store = ViewModelStore().also(viewModelStores::add)
        val factory = viewModelFactory {
            initializer {
                SearchViewModel(
                    repository = repository,
                    profileId = "profile-main",
                    nowEpochMillis = nowEpochMillis,
                    debounceMillis = debounceMillis,
                )
            }
        }
        return ViewModelProvider.create(store, factory)[SearchViewModel::class]
    }
}

private class FakeSearchRepository(
    private val response: (ChannelSearchQuery) -> Flow<ChannelSearchSnapshot> = {
        flowOf(ChannelSearchSnapshot.EMPTY)
    },
) : ChannelSearchRepository {
    val queries = mutableListOf<ChannelSearchQuery>()

    override fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot> {
        queries += query
        return response(query)
    }
}

private fun snapshot(
    results: List<ChannelSearchResult>,
    truncated: Boolean = false,
    nextBoundaryEpochMillis: Long? = null,
): ChannelSearchSnapshot = ChannelSearchSnapshot(
    results = results,
    isTruncated = truncated,
    nextBoundaryEpochMillis = nextBoundaryEpochMillis,
)

private fun result(
    id: String,
    name: String,
    number: String? = null,
    group: String? = null,
    favorite: Boolean = false,
    programme: String? = null,
): ChannelSearchResult = ChannelSearchResult(
    channel = PlayableChannelSummary(
        channelId = id,
        displayName = name,
        logoUrl = null,
        groupTitle = group,
        channelNumber = number,
        isFavorite = favorite,
        variantCount = 1,
    ),
    currentProgrammeTitle = programme,
)
