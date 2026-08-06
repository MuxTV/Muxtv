package app.muxtv.feature.guide

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.muxtv.catalog.ChannelGuideProgrammeWindow
import app.muxtv.catalog.GuideChannelCursor
import app.muxtv.catalog.GuideChannelWindow
import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeWindow
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlayableChannelSummary
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GuidePagingTest {
    private val mainDispatcher = UnconfinedTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        stores.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `next page replaces bounded viewport using repository continuation cursor`() = runTest {
        val firstCursor = cursor("channel-30", 30)
        val repository = PagingRepository(
            pages = mapOf(
                null to page("first", firstCursor),
                firstCursor to page("second", null),
            ),
        )
        val viewModel = createViewModel(repository)
        runCurrent()

        viewModel.loadNextPage()
        runCurrent()

        assertThat(repository.channelQueries.map(GuideChannelWindowQuery::after))
            .containsExactly(null, firstCursor).inOrder()
        val state = viewModel.uiState.value as GuideUiState.Content
        assertThat(state.rows.map { it.channel.channelId }).containsExactly("second-1")
        assertThat(state.viewport.canGoPrevious).isTrue()
        assertThat(state.viewport.hasMoreChannels).isFalse()
    }

    @Test
    fun `previous page uses remembered bounded start cursor rather than appending rows`() = runTest {
        val firstCursor = cursor("channel-30", 30)
        val repository = PagingRepository(
            pages = mapOf(
                null to page("first", firstCursor),
                firstCursor to page("second", null),
            ),
        )
        val viewModel = createViewModel(repository)
        runCurrent()
        viewModel.loadNextPage()
        runCurrent()

        viewModel.loadPreviousPage()
        runCurrent()

        assertThat(repository.channelQueries.map(GuideChannelWindowQuery::after))
            .containsExactly(null, firstCursor, null).inOrder()
        val state = viewModel.uiState.value as GuideUiState.Content
        assertThat(state.rows.map { it.channel.channelId }).containsExactly("first-1")
        assertThat(state.viewport.canGoPrevious).isFalse()
    }

    @Test
    fun `invalidation reloads current page cursor instead of silently jumping to first`() = runTest {
        val firstCursor = cursor("channel-30", 30)
        val repository = PagingRepository(
            pages = mapOf(
                null to page("first", firstCursor),
                firstCursor to page("second", null),
            ),
        )
        val viewModel = createViewModel(repository)
        runCurrent()
        viewModel.loadNextPage()
        runCurrent()

        repository.invalidations.emit(Unit)
        runCurrent()

        assertThat(repository.channelQueries.map(GuideChannelWindowQuery::after))
            .containsExactly(null, firstCursor, firstCursor).inOrder()
    }

    @Test
    fun `reset to first page clears cursor history and restores first bounded window`() = runTest {
        val firstCursor = cursor("channel-30", 30)
        val repository = PagingRepository(
            pages = mapOf(
                null to page("first", firstCursor),
                firstCursor to page("second", null),
            ),
        )
        val viewModel = createViewModel(repository)
        runCurrent()
        viewModel.loadNextPage()
        runCurrent()

        viewModel.resetToFirstPage()
        runCurrent()

        val state = viewModel.uiState.value as GuideUiState.Content
        assertThat(state.rows.map { it.channel.channelId }).containsExactly("first-1")
        assertThat(state.viewport.canGoPrevious).isFalse()
        assertThat(repository.channelQueries.last().after).isNull()
    }

    private fun createViewModel(repository: GuideWindowRepository): GuideViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = viewModelFactory {
            initializer {
                GuideViewModel(
                    repository = repository,
                    profileId = "profile-main",
                    nowEpochMillis = { 10_000L },
                )
            }
        }
        return ViewModelProvider.create(store, factory)[GuideViewModel::class]
    }

    private fun cursor(id: String, number: Int): GuideChannelCursor = GuideChannelCursor(
        channelNumber = number,
        displayName = "Channel $number",
        canonicalChannelId = id,
    )

    private fun page(prefix: String, next: GuideChannelCursor?): GuideChannelWindow =
        GuideChannelWindow(
            channels = listOf(
                PlayableChannelSummary(
                    channelId = "$prefix-1",
                    displayName = "$prefix channel",
                    logoUrl = null,
                    groupTitle = null,
                    channelNumber = "1",
                    isFavorite = false,
                    variantCount = 1,
                ),
            ),
            nextCursor = next,
            isTruncated = next != null,
        )
}

private class PagingRepository(
    private val pages: Map<GuideChannelCursor?, GuideChannelWindow>,
) : GuideWindowRepository {
    val channelQueries = mutableListOf<GuideChannelWindowQuery>()
    val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override suspend fun getChannelWindow(query: GuideChannelWindowQuery): GuideChannelWindow {
        channelQueries += query
        return requireNotNull(pages[query.after])
    }

    override suspend fun getProgrammeWindow(query: GuideProgrammeWindowQuery): GuideProgrammeWindow =
        GuideProgrammeWindow(
            channels = query.canonicalChannelIds.map { id ->
                ChannelGuideProgrammeWindow(
                    canonicalChannelId = id,
                    state = GuideProjectionState.READY,
                    programmes = emptyList(),
                )
            },
            isTruncated = false,
        )

    override fun observeDataChanges(): Flow<Unit> = invalidations
}
