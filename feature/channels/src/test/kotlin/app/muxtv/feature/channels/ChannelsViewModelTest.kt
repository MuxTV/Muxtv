package app.muxtv.feature.channels

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.PagingData
import app.muxtv.catalog.ChannelBrowseFilter
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelsViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun filterSwitchCreatesIndependentPagedQueries() = runBlocking {
        val repository = RecordingBrowseRepository()
        withViewModel(repository, FakePlaybackStateSource()) { viewModel ->
            val collection = launch { viewModel.rows.collect() }
            awaitQueryCount(repository, 1)
            viewModel.setFilter(ChannelsFilter.FAVORITES)
            awaitQueryCount(repository, 2)
            viewModel.setFilter(ChannelsFilter.RECENT)
            awaitQueryCount(repository, 3)
            collection.cancel()
        }

        assertThat(repository.queries.map { it.filter })
            .containsAtLeast(
                ChannelBrowseFilter.ALL,
                ChannelBrowseFilter.FAVORITES,
                ChannelBrowseFilter.RECENT,
            )
        assertThat(repository.queries.all { it.profileId == PROFILE_ID }).isTrue()
    }

    @Test
    fun playbackIdentityUpdatesPagedRowsWithoutMaterializingCatalog() = runBlocking {
        val item = item("all")
        assertThat(
            buildChannelRow(item, nowNext = null, playback = PlaybackSessionState.Idle, nowEpochMillis = 0L)
                .isCurrentPlayback,
        ).isFalse()
        assertThat(
            buildChannelRow(
                item = item,
                nowNext = null,
                playback = PlaybackSessionState(
                    channelId = "all",
                    phase = PlaybackSessionPhase.READY,
                    isPlaying = true,
                ),
                nowEpochMillis = 0L,
            ).isCurrentPlayback,
        ).isTrue()
    }

    @Test
    fun settingNowNextIdsRefreshesImmediately() = runBlocking {
        val epg = RecordingEpgGuideRepository()
        withViewModel(
            repository = RecordingBrowseRepository(),
            playback = FakePlaybackStateSource(),
            epg = epg,
        ) { viewModel ->
            viewModel.setNowNextIds(listOf("channel-a", "channel-b"))
            repeat(20) {
                if (epg.queries.isNotEmpty()) return@repeat
                yield()
            }

            assertThat(epg.queries).hasSize(1)
            assertThat(epg.queries.single().canonicalChannelIds)
                .containsExactly("channel-a", "channel-b").inOrder()
        }
    }

    @Test
    fun staleNowNextCompletionCannotOverwriteNewerIds() = runBlocking {
        val epg = DeferredEpgGuideRepository()
        withViewModel(
            repository = RecordingBrowseRepository(),
            playback = FakePlaybackStateSource(),
            epg = epg,
        ) { viewModel ->
            viewModel.refreshNowNext(listOf("old-channel"))
            awaitRequestCount(epg, 1)
            viewModel.refreshNowNext(listOf("new-channel"))
            awaitRequestCount(epg, 2)

            epg.complete("new-channel", nowNext("new-channel", 5_000L))
            yield()
            epg.complete("old-channel", nowNext("old-channel", 4_000L))
            yield()

            assertThat(viewModel.nowNextById.value.keys).containsExactly("new-channel")
        }
    }

    private suspend fun awaitQueryCount(repository: RecordingBrowseRepository, count: Int) {
        repeat(100) {
            if (repository.queries.size >= count) return
            yield()
        }
        error("Expected $count browse queries, got ${repository.queries.size}")
    }

    private suspend fun awaitRequestCount(repository: DeferredEpgGuideRepository, count: Int) {
        repeat(100) {
            if (repository.requestCount >= count) return
            yield()
        }
        error("Expected $count now/next requests, got ${repository.requestCount}")
    }

    private suspend fun withViewModel(
        repository: ChannelBrowseRepository,
        playback: PlaybackSessionStateSource,
        epg: EpgGuideRepository = NoNowNextEpgGuideRepository,
        block: suspend (ChannelsViewModel) -> Unit,
    ) {
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                ChannelsViewModel(
                    channelBrowseRepository = repository,
                    playbackSessionStateSource = playback,
                    epgGuideRepository = epg,
                    profileId = PROFILE_ID,
                    nowEpochMillis = { 1_000L },
                )
            }
        }
        val viewModel = ViewModelProvider.create(store, factory)[ChannelsViewModel::class]
        try {
            block(viewModel)
        } finally {
            store.clear()
        }
    }

    private class RecordingBrowseRepository : ChannelBrowseRepository {
        val queries = mutableListOf<ChannelBrowseQuery>()

        override fun pages(query: ChannelBrowseQuery): Flow<PagingData<ChannelBrowseItem>> {
            queries += query
            val id = when (query.filter) {
                ChannelBrowseFilter.ALL -> "all"
                ChannelBrowseFilter.FAVORITES -> "favorites"
                ChannelBrowseFilter.RECENT -> "recent"
            }
            return flowOf(PagingData.from(listOf(item(id))))
        }
    }

    private class FakePlaybackStateSource : PlaybackSessionStateSource {
        val state = MutableStateFlow(PlaybackSessionState.Idle)
        override val playbackSessionState: StateFlow<PlaybackSessionState> = state
    }

    private class RecordingEpgGuideRepository : EpgGuideRepository {
        val queries = CopyOnWriteArrayList<NowNextQuery>()

        override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> {
            queries += query
            return query.canonicalChannelIds.map { nowNext(it, 5_000L) }
        }

        override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
    }

    private class DeferredEpgGuideRepository : EpgGuideRepository {
        private val requests = CopyOnWriteArrayList<Pair<NowNextQuery, CompletableDeferred<List<ChannelNowNext>>>>()

        val requestCount: Int
            get() = requests.size

        override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> {
            val deferred = CompletableDeferred<List<ChannelNowNext>>()
            requests += query to deferred
            return deferred.await()
        }

        fun complete(channelId: String, result: ChannelNowNext) {
            val request = requests.first { (query, _) -> query.canonicalChannelIds == listOf(channelId) }
            request.second.complete(listOf(result))
        }

        override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
    }

    private object NoNowNextEpgGuideRepository : EpgGuideRepository {
        override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> = emptyList()

        override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
    }

    private companion object {
        const val PROFILE_ID = "profile-main"

        fun item(id: String) = ChannelBrowseItem(
            channelId = id,
            displayName = id,
            channelNumber = null,
            groupTitle = null,
            isFavorite = false,
            isCurrentPlayback = false,
            currentProgrammeTitle = null,
            currentProgrammeEndEpochMillis = null,
            nextProgrammeTitle = null,
            nextProgrammeStartEpochMillis = null,
            variantCount = 1,
            guideState = GuideProjectionState.NO_GUIDE,
        )

        fun nowNext(channelId: String, boundary: Long) = ChannelNowNext(
            canonicalChannelId = channelId,
            state = GuideProjectionState.READY,
            current = null,
            next = null,
            nextBoundaryEpochMillis = boundary,
        )
    }
}
