package app.muxtv.feature.home

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    @Before
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun settingNowNextIdsRefreshesImmediatelyWithoutWaitingForPeriodicTick() = runBlocking {
        val epg = RecordingEpgGuideRepository()
        withViewModel(epg = epg) { viewModel ->
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
        withViewModel(epg = epg) { viewModel ->
            viewModel.refreshNowNext(listOf("old-channel"))
            awaitRequestCount(epg, 1)
            viewModel.refreshNowNext(listOf("new-channel"))
            awaitRequestCount(epg, 2)

            epg.complete(
                channelId = "new-channel",
                result = nowNext("new-channel", boundary = 5_000L),
            )
            yield()
            epg.complete(
                channelId = "old-channel",
                result = nowNext("old-channel", boundary = 4_000L),
            )
            yield()

            assertThat(viewModel.nowNext.value.keys).containsExactly("new-channel")
        }
    }

    @Test
    fun sourceTruthDistinguishesEmptyPresentAndFailure() = runBlocking {
        withViewModel(hasSources = flowOf(false)) { viewModel ->
            yield()
            assertThat(viewModel.sourceState.value).isEqualTo(HomeSourceState.Empty)
        }
        withViewModel(hasSources = flowOf(true)) { viewModel ->
            yield()
            assertThat(viewModel.sourceState.value).isEqualTo(HomeSourceState.Present)
        }
        withViewModel(
            hasSources = flow {
                throw IllegalStateException("database read failed")
            },
        ) { viewModel ->
            yield()
            assertThat(viewModel.sourceState.value).isEqualTo(HomeSourceState.Failed)
        }
    }

    private suspend fun withViewModel(
        epg: EpgGuideRepository = RecordingEpgGuideRepository(),
        hasSources: Flow<Boolean> = flowOf(true),
        block: suspend (HomeViewModel) -> Unit,
    ) {
        val store = ViewModelStore()
        val factory = viewModelFactory {
            initializer {
                HomeViewModel(
                    recentChannelsRepository = EmptyRecentRepository,
                    epgGuideRepository = epg,
                    playbackSessionStateSource = IdlePlaybackSource,
                    hasSources = hasSources,
                    profileId = PROFILE_ID,
                    nowEpochMillis = { 1_000L },
                )
            }
        }
        val viewModel = ViewModelProvider.create(store, factory)[HomeViewModel::class]
        try {
            block(viewModel)
        } finally {
            store.clear()
        }
    }

    private suspend fun awaitRequestCount(repository: DeferredEpgGuideRepository, expected: Int) {
        repeat(100) {
            if (repository.requestCount >= expected) return
            yield()
        }
        error("Expected $expected now/next requests, got ${repository.requestCount}")
    }

    private class RecordingEpgGuideRepository : EpgGuideRepository {
        val queries = CopyOnWriteArrayList<NowNextQuery>()

        override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> {
            queries += query
            return query.canonicalChannelIds.map { nowNext(it, boundary = 5_000L) }
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

    private object EmptyRecentRepository : RecentChannelsRepository {
        override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> = flowOf(emptyList())

        override suspend fun recordSuccessfulPlayback(
            profileId: String,
            channelId: String,
            successfulAtEpochMillis: Long,
        ): RecentChannelWriteResult = RecentChannelWriteResult.Applied
    }

    private object IdlePlaybackSource : PlaybackSessionStateSource {
        override val playbackSessionState: StateFlow<PlaybackSessionState> =
            MutableStateFlow(PlaybackSessionState.Idle)
    }

    private companion object {
        const val PROFILE_ID = "profile-main"

        fun nowNext(channelId: String, boundary: Long) = ChannelNowNext(
            canonicalChannelId = channelId,
            state = GuideProjectionState.READY,
            current = null,
            next = null,
            nextBoundaryEpochMillis = boundary,
        )
    }
}
