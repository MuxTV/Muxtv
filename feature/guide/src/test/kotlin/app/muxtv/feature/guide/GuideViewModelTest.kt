package app.muxtv.feature.guide

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.muxtv.catalog.ChannelGuideProgrammeWindow
import app.muxtv.catalog.GuideChannelWindow
import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProgrammeWindow
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlayableChannelSummary
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
class GuideViewModelTest {
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
    fun `initial load requests thirty channels and six hour programme window`() = runTest {
        val repository = FakeGuideWindowRepository(
            channelResponse = { channelWindow(channelCount = 2) },
            programmeResponse = { query -> completeProgrammes(query.canonicalChannelIds) },
        )
        createViewModel(repository, nowEpochMillis = { 10_000L })
        runCurrent()

        assertThat(repository.channelQueries).hasSize(1)
        assertThat(repository.channelQueries.single().limit)
            .isEqualTo(GuideChannelWindowQuery.DEFAULT_LIMIT)
        assertThat(repository.channelQueries.single().after).isNull()

        assertThat(repository.programmeQueries).hasSize(1)
        val query = repository.programmeQueries.single()
        assertThat(query.canonicalChannelIds).containsExactly("channel-1", "channel-2").inOrder()
        assertThat(query.fromEpochMillis).isEqualTo(10_000L)
        assertThat(query.toEpochMillis - query.fromEpochMillis)
            .isEqualTo(GuideViewportPolicy.DEFAULT_TIME_SPAN_MILLIS)
        assertThat(query.toEpochMillis - query.fromEpochMillis)
            .isAtMost(GuideProgrammeWindowQuery.MAX_SPAN_MILLIS)
    }

    @Test
    fun `truncated programme response is narrowed and never published as complete`() = runTest {
        val calls = AtomicInteger(0)
        val repository = FakeGuideWindowRepository(
            channelResponse = { channelWindow(channelCount = 4) },
            programmeResponse = { query ->
                if (calls.getAndIncrement() == 0) {
                    GuideProgrammeWindow(
                        channels = query.canonicalChannelIds.map { id ->
                            ChannelGuideProgrammeWindow(
                                canonicalChannelId = id,
                                state = GuideProjectionState.READY,
                                programmes = emptyList(),
                            )
                        },
                        isTruncated = true,
                    )
                } else {
                    completeProgrammes(query.canonicalChannelIds)
                }
            },
        )
        val viewModel = createViewModel(repository, nowEpochMillis = { 10_000L })
        runCurrent()

        assertThat(repository.programmeQueries).hasSize(2)
        assertThat(repository.programmeQueries[1].toEpochMillis)
            .isLessThan(repository.programmeQueries[0].toEpochMillis)
        val state = viewModel.uiState.value as GuideUiState.Content
        assertThat(state.rows).hasSize(4)
        assertThat(state.viewport.toEpochMillis - state.viewport.fromEpochMillis)
            .isEqualTo(repository.programmeQueries[1].toEpochMillis - 10_000L)
    }

    @Test
    fun `persistent truncation exposes incomplete state after bounded attempts`() = runTest {
        val repository = FakeGuideWindowRepository(
            channelResponse = { channelWindow(channelCount = 8) },
            programmeResponse = { query ->
                GuideProgrammeWindow(
                    channels = query.canonicalChannelIds.map { id ->
                        ChannelGuideProgrammeWindow(
                            canonicalChannelId = id,
                            state = GuideProjectionState.READY,
                            programmes = emptyList(),
                        )
                    },
                    isTruncated = true,
                )
            },
        )
        val viewModel = createViewModel(repository, nowEpochMillis = { 10_000L })
        runCurrent()

        assertThat(repository.programmeQueries.size)
            .isEqualTo(GuideViewportPolicy.MAX_PROGRAMME_ATTEMPTS)
        assertThat(viewModel.uiState.value).isEqualTo(GuideUiState.Incomplete)
    }

    @Test
    fun `stale generation cannot publish after a newer reload`() = runTest {
        val firstChannels = CompletableDeferred<GuideChannelWindow>()
        val requestIndex = AtomicInteger(0)
        val repository = FakeGuideWindowRepository(
            channelResponse = {
                if (requestIndex.getAndIncrement() == 0) {
                    firstChannels.await()
                } else {
                    channelWindow(channelCount = 1, prefix = "fresh")
                }
            },
            programmeResponse = { query -> completeProgrammes(query.canonicalChannelIds) },
        )
        val viewModel = createViewModel(repository, nowEpochMillis = { 10_000L })
        runCurrent()

        viewModel.reload()
        runCurrent()
        val fresh = viewModel.uiState.value as GuideUiState.Content
        assertThat(fresh.rows.single().channel.channelId).isEqualTo("fresh-1")

        firstChannels.complete(channelWindow(channelCount = 1, prefix = "stale"))
        runCurrent()

        val finalState = viewModel.uiState.value as GuideUiState.Content
        assertThat(finalState.rows.single().channel.channelId).isEqualTo("fresh-1")
    }

    @Test
    fun `data invalidation reloads the current bounded viewport`() = runTest {
        val repository = FakeGuideWindowRepository(
            channelResponse = { channelWindow(channelCount = 2) },
            programmeResponse = { query -> completeProgrammes(query.canonicalChannelIds) },
        )
        createViewModel(repository, nowEpochMillis = { 10_000L })
        runCurrent()
        assertThat(repository.channelQueries).hasSize(1)

        repository.invalidations.emit(Unit)
        runCurrent()

        assertThat(repository.channelQueries).hasSize(2)
        assertThat(repository.channelQueries.all { it.limit == GuideChannelWindowQuery.DEFAULT_LIMIT })
            .isTrue()
        assertThat(repository.programmeQueries).hasSize(2)
    }

    @Test
    fun `ready no guide and source conflict are preserved per channel`() = runTest {
        val repository = FakeGuideWindowRepository(
            channelResponse = { channelWindow(channelCount = 3) },
            programmeResponse = { query ->
                GuideProgrammeWindow(
                    channels = listOf(
                        programmeChannel(query.canonicalChannelIds[0], GuideProjectionState.READY),
                        programmeChannel(query.canonicalChannelIds[1], GuideProjectionState.NO_GUIDE),
                        programmeChannel(query.canonicalChannelIds[2], GuideProjectionState.SOURCE_CONFLICT),
                    ),
                    isTruncated = false,
                )
            },
        )
        val viewModel = createViewModel(repository, nowEpochMillis = { 10_000L })
        runCurrent()

        val state = viewModel.uiState.value as GuideUiState.Content
        assertThat(state.rows.map(GuideRow::state))
            .containsExactly(
                GuideProjectionState.READY,
                GuideProjectionState.NO_GUIDE,
                GuideProjectionState.SOURCE_CONFLICT,
            )
            .inOrder()
    }

    @Test
    fun `ordinary repository failure is secret free and retryable`() = runTest {
        var fail = true
        val repository = FakeGuideWindowRepository(
            channelResponse = {
                if (fail) throw IllegalStateException("https://secret.example/live?token=abc")
                channelWindow(channelCount = 1)
            },
            programmeResponse = { query -> completeProgrammes(query.canonicalChannelIds) },
        )
        val viewModel = createViewModel(repository, nowEpochMillis = { 10_000L })
        runCurrent()

        assertThat(viewModel.uiState.value).isEqualTo(GuideUiState.Failed)
        assertThat(viewModel.uiState.value.toString()).doesNotContain("secret.example")

        fail = false
        viewModel.reload()
        runCurrent()
        assertThat(viewModel.uiState.value).isInstanceOf(GuideUiState.Content::class.java)
    }

    private fun createViewModel(
        repository: GuideWindowRepository,
        nowEpochMillis: () -> Long,
    ): GuideViewModel {
        val store = ViewModelStore().also(viewModelStores::add)
        val factory = viewModelFactory {
            initializer {
                GuideViewModel(
                    repository = repository,
                    profileId = "profile-main",
                    nowEpochMillis = nowEpochMillis,
                )
            }
        }
        return ViewModelProvider.create(store, factory)[GuideViewModel::class]
    }
}

private class FakeGuideWindowRepository(
    private val channelResponse: suspend (GuideChannelWindowQuery) -> GuideChannelWindow,
    private val programmeResponse: suspend (GuideProgrammeWindowQuery) -> GuideProgrammeWindow,
) : GuideWindowRepository {
    val channelQueries = mutableListOf<GuideChannelWindowQuery>()
    val programmeQueries = mutableListOf<GuideProgrammeWindowQuery>()
    val invalidations = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    override suspend fun getChannelWindow(query: GuideChannelWindowQuery): GuideChannelWindow {
        channelQueries += query
        return channelResponse(query)
    }

    override suspend fun getProgrammeWindow(query: GuideProgrammeWindowQuery): GuideProgrammeWindow {
        programmeQueries += query
        return programmeResponse(query)
    }

    override fun observeDataChanges(): Flow<Unit> = invalidations
}

private fun channelWindow(
    channelCount: Int,
    prefix: String = "channel",
): GuideChannelWindow = GuideChannelWindow(
    channels = (1..channelCount).map { index ->
        PlayableChannelSummary(
            channelId = "$prefix-$index",
            displayName = "Channel $index",
            logoUrl = null,
            groupTitle = null,
            channelNumber = index.toString(),
            isFavorite = false,
            variantCount = 1,
        )
    },
    nextCursor = null,
    isTruncated = false,
)

private fun completeProgrammes(channelIds: List<String>): GuideProgrammeWindow =
    GuideProgrammeWindow(
        channels = channelIds.map { id -> programmeChannel(id, GuideProjectionState.READY) },
        isTruncated = false,
    )

private fun programmeChannel(
    channelId: String,
    state: GuideProjectionState,
): ChannelGuideProgrammeWindow = ChannelGuideProgrammeWindow(
    canonicalChannelId = channelId,
    state = state,
    programmes = if (state == GuideProjectionState.READY) {
        listOf(
            GuideProgrammeCell(
                key = GuideProgrammeKey(
                    epgSourceId = "epg-source",
                    epgRevisionNumber = 1,
                    sequenceNumber = channelId.hashCode().toLong().let(::kotlin.math.abs),
                ),
                startEpochMillis = 10_000L,
                endEpochMillis = 20_000L,
                title = "Programme",
            ),
        )
    } else {
        emptyList()
    },
)
