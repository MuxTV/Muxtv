package app.muxtv.feature.channels

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.RecentChannel
import app.muxtv.catalog.RecentChannelWriteResult
import app.muxtv.catalog.RecentChannelsQuery
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
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
class ChannelsViewModelTest {
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
    fun `screen owner combines catalog rows with guide projection`() = runTest {
        val catalog = FakePlaybackCatalog(listOf(channel("channel-a", "Alpha")))
        val guide = FakeGuideRepository().apply { currentTitle = "Новости" }
        val viewModel = createViewModel(catalog, guide)

        val state = viewModel.uiState.value as ChannelsUiState.Content

        assertThat(state.rows).hasSize(1)
        assertThat(state.rows.single().channelId).isEqualTo("channel-a")
        assertThat(state.rows.single().currentTitle).isEqualTo("Новости")
        assertThat(guide.queryCount).isEqualTo(1)
    }

    @Test
    fun `ordinary guide failure degrades to channel only content`() = runTest {
        val catalog = FakePlaybackCatalog(listOf(channel("channel-a", "Alpha")))
        val guide = FakeGuideRepository().apply {
            failure = IllegalStateException("synthetic guide failure")
        }
        val viewModel = createViewModel(catalog, guide)

        val state = viewModel.uiState.value as ChannelsUiState.Content

        assertThat(state.rows.single().guideState).isEqualTo(GuideProjectionState.NO_GUIDE)
        assertThat(state.rows.single().currentTitle).isNull()
    }

    @Test
    fun `guide invalidation reloads the bounded projection`() = runTest {
        val catalog = FakePlaybackCatalog(listOf(channel("channel-a", "Alpha")))
        val guide = FakeGuideRepository().apply { currentTitle = "Первая программа" }
        val viewModel = createViewModel(catalog, guide)
        assertThat(
            (viewModel.uiState.value as ChannelsUiState.Content).rows.single().currentTitle,
        ).isEqualTo("Первая программа")

        guide.currentTitle = "Обновлённая программа"
        guide.changes.emit(Unit)

        assertThat(
            (viewModel.uiState.value as ChannelsUiState.Content).rows.single().currentTitle,
        ).isEqualTo("Обновлённая программа")
        assertThat(guide.queryCount).isEqualTo(2)
    }

    @Test
    fun `metadata and order changes reuse guide snapshot`() = runTest {
        val catalog = FakePlaybackCatalog(
            listOf(channel("channel-a", "Alpha"), channel("channel-b", "Beta")),
        )
        val guide = FakeGuideRepository().apply { currentTitle = "Программа" }
        val viewModel = createViewModel(catalog, guide)
        assertThat(guide.queryCount).isEqualTo(1)

        catalog.replaceChannels(
            listOf(channel("channel-b", "Beta updated"), channel("channel-a", "Alpha updated")),
        )

        val rows = (viewModel.uiState.value as ChannelsUiState.Content).rows
        assertThat(rows.map(ChannelRowProjection::channelId))
            .containsExactly("channel-b", "channel-a").inOrder()
        assertThat(rows.map { row -> row.channel.displayName })
            .containsExactly("Beta updated", "Alpha updated").inOrder()
        assertThat(rows.map(ChannelRowProjection::currentTitle))
            .containsExactly("Программа", "Программа").inOrder()
        assertThat(guide.queryCount).isEqualTo(1)
    }

    @Test
    fun `channel membership change reloads guide for the new bounded set`() = runTest {
        val catalog = FakePlaybackCatalog(listOf(channel("channel-a", "Alpha")))
        val guide = FakeGuideRepository().apply { currentTitle = "Программа" }
        val viewModel = createViewModel(catalog, guide)
        assertThat(guide.queryCount).isEqualTo(1)

        catalog.replaceChannels(
            listOf(channel("channel-a", "Alpha"), channel("channel-b", "Beta")),
        )

        val rows = (viewModel.uiState.value as ChannelsUiState.Content).rows
        assertThat(rows.map(ChannelRowProjection::channelId))
            .containsExactly("channel-a", "channel-b").inOrder()
        assertThat(guide.queryCount).isEqualTo(2)
    }

    @Test
    fun `favorites filter switches catalog query and guide membership`() = runTest {
        val catalog = FakePlaybackCatalog(
            listOf(
                channel("channel-a", "Alpha"),
                channel("channel-b", "Beta", isFavorite = true),
            ),
        )
        val guide = FakeGuideRepository()
        val viewModel = createViewModel(catalog, guide)
        assertThat((viewModel.uiState.value as ChannelsUiState.Content).rows).hasSize(2)
        assertThat(guide.queryCount).isEqualTo(1)

        viewModel.setFilter(ChannelsFilter.FAVORITES)
        runCurrent()

        assertThat(viewModel.filter.value).isEqualTo(ChannelsFilter.FAVORITES)
        val rows = (viewModel.uiState.value as ChannelsUiState.Content).rows
        assertThat(rows.map(ChannelRowProjection::channelId)).containsExactly("channel-b")
        assertThat(guide.queryCount).isEqualTo(2)
    }

    @Test
    fun `recent filter preserves repository newest-first order`() = runTest {
        val catalog = FakePlaybackCatalog(
            listOf(channel("channel-a", "Alpha"), channel("channel-b", "Beta")),
        )
        val recent = FakeRecentChannelsRepository(
            listOf(
                recent(channel("channel-b", "Beta"), 2_000L),
                recent(channel("channel-a", "Alpha"), 1_000L),
            ),
        )
        val guide = FakeGuideRepository()
        val viewModel = createViewModel(catalog, guide, recent = recent)

        viewModel.setFilter(ChannelsFilter.RECENT)
        runCurrent()

        assertThat(viewModel.filter.value).isEqualTo(ChannelsFilter.RECENT)
        val rows = (viewModel.uiState.value as ChannelsUiState.Content).rows
        assertThat(rows.map(ChannelRowProjection::channelId))
            .containsExactly("channel-b", "channel-a").inOrder()
        assertThat(recent.lastQuery?.profileId).isEqualTo("profile-main")
        assertThat(recent.lastQuery?.limit).isEqualTo(RecentChannelsQuery.DEFAULT_LIMIT)
        assertThat(guide.queryCount).isEqualTo(2)
    }

    @Test
    fun `empty filtered views can switch back to all channels`() = runTest {
        val catalog = FakePlaybackCatalog(listOf(channel("channel-a", "Alpha")))
        val recent = FakeRecentChannelsRepository(emptyList())
        val viewModel = createViewModel(catalog, FakeGuideRepository(), recent = recent)

        viewModel.setFilter(ChannelsFilter.FAVORITES)
        runCurrent()
        assertThat(viewModel.uiState.value).isEqualTo(ChannelsUiState.Empty)

        viewModel.setFilter(ChannelsFilter.ALL)
        runCurrent()
        assertThat((viewModel.uiState.value as ChannelsUiState.Content).rows).hasSize(1)

        viewModel.setFilter(ChannelsFilter.RECENT)
        runCurrent()
        assertThat(viewModel.uiState.value).isEqualTo(ChannelsUiState.Empty)

        viewModel.setFilter(ChannelsFilter.ALL)
        runCurrent()

        assertThat(viewModel.filter.value).isEqualTo(ChannelsFilter.ALL)
        val rows = (viewModel.uiState.value as ChannelsUiState.Content).rows
        assertThat(rows.map(ChannelRowProjection::channelId)).containsExactly("channel-a")
    }

    @Test
    fun `programme boundary reloads guide once when the boundary is reached`() = runTest {
        val catalog = FakePlaybackCatalog(listOf(channel("channel-a", "Alpha")))
        val guide = FakeGuideRepository().apply {
            currentTitle = "Первая программа"
            nextBoundaryEpochMillis = 1_100L
        }
        val viewModel = createViewModel(
            catalog = catalog,
            guide = guide,
            nowEpochMillis = { 1_000L + testScheduler.currentTime },
        )
        assertThat(guide.queryCount).isEqualTo(1)

        guide.currentTitle = "Вторая программа"
        advanceTimeBy(99L)
        runCurrent()
        assertThat(guide.queryCount).isEqualTo(1)

        advanceTimeBy(2L)
        runCurrent()

        assertThat(guide.queryCount).isEqualTo(2)
        assertThat(
            (viewModel.uiState.value as ChannelsUiState.Content).rows.single().currentTitle,
        ).isEqualTo("Вторая программа")
    }

    @Test
    fun `playback session updates current channel without reloading guide`() = runTest {
        val catalog = FakePlaybackCatalog(
            listOf(channel("channel-a", "Alpha"), channel("channel-b", "Beta")),
        )
        val guide = FakeGuideRepository()
        val playback = FakePlaybackSessionStateSource()
        val viewModel = createViewModel(catalog, guide, playback = playback)
        assertThat(guide.queryCount).isEqualTo(1)

        playback.state.value = PlaybackSessionState(
            channelId = "channel-b",
            phase = PlaybackSessionPhase.READY,
            isPlaying = true,
        )

        val rows = (viewModel.uiState.value as ChannelsUiState.Content).rows
        assertThat(rows[0].isCurrentPlayback).isFalse()
        assertThat(rows[1].isCurrentPlayback).isTrue()
        assertThat(rows[1].isPlaying).isTrue()
        assertThat(guide.queryCount).isEqualTo(1)
    }

    private fun createViewModel(
        catalog: PlaybackCatalog,
        guide: EpgGuideRepository,
        recent: RecentChannelsRepository = FakeRecentChannelsRepository(emptyList()),
        playback: PlaybackSessionStateSource = FakePlaybackSessionStateSource(),
        nowEpochMillis: () -> Long = { 1_000L },
    ): ChannelsViewModel {
        val store = ViewModelStore().also(viewModelStores::add)
        val factory = viewModelFactory {
            initializer {
                ChannelsViewModel(
                    playbackCatalog = catalog,
                    recentChannelsRepository = recent,
                    epgGuideRepository = guide,
                    playbackSessionStateSource = playback,
                    profileId = "profile-main",
                    nowEpochMillis = nowEpochMillis,
                )
            }
        }
        return ViewModelProvider.create(store, factory)[ChannelsViewModel::class]
    }

    private fun channel(
        id: String,
        name: String,
        isFavorite: Boolean = false,
    ): PlayableChannelSummary = PlayableChannelSummary(
        channelId = id,
        displayName = name,
        logoUrl = null,
        groupTitle = null,
        channelNumber = null,
        isFavorite = isFavorite,
        variantCount = 1,
    )

    private fun recent(
        channel: PlayableChannelSummary,
        timestamp: Long,
    ): RecentChannel = RecentChannel(
        channel = channel,
        lastSuccessfulPlaybackAtEpochMillis = timestamp,
    )
}

private class FakePlaybackCatalog(
    initialChannels: List<PlayableChannelSummary>,
) : PlaybackCatalog {
    private val channels = MutableStateFlow(initialChannels)

    fun replaceChannels(value: List<PlayableChannelSummary>) {
        channels.value = value
    }

    override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
        channels.map { rows ->
            if (query.favoritesOnly) rows.filter(PlayableChannelSummary::isFavorite) else rows
        }

    override suspend fun getChannel(profileId: String, channelId: String): PlayableChannel? =
        error("Not used by ChannelsViewModelTest")

    override suspend fun resolveVariant(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
    ): PlaybackVariantResolution? = error("Not used by ChannelsViewModelTest")

    override suspend fun approveInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult = error("Not used by ChannelsViewModelTest")

    override suspend fun revokeInsecurePlayback(
        profileId: String,
        channelId: String,
        variantId: String,
    ): PlaybackAccessMutationResult = error("Not used by ChannelsViewModelTest")
}

private class FakeRecentChannelsRepository(
    initialRecent: List<RecentChannel>,
) : RecentChannelsRepository {
    private val recent = MutableStateFlow(initialRecent)
    var lastQuery: RecentChannelsQuery? = null
        private set

    override fun observeRecent(query: RecentChannelsQuery): Flow<List<RecentChannel>> {
        lastQuery = query
        return recent.map { rows -> rows.take(query.limit) }
    }

    override suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): RecentChannelWriteResult = error("Not used by ChannelsViewModelTest")
}

private class FakeGuideRepository : EpgGuideRepository {
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    var currentTitle: String? = null
    var nextBoundaryEpochMillis: Long? = null
    var failure: Exception? = null
    var queryCount: Int = 0
        private set

    override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> {
        queryCount++
        failure?.let { throw it }
        return query.canonicalChannelIds.map { channelId ->
            ChannelNowNext(
                canonicalChannelId = channelId,
                state = GuideProjectionState.READY,
                current = currentTitle?.let { title ->
                    GuideProgramme(
                        startEpochMillis = 900,
                        endEpochMillis = nextBoundaryEpochMillis ?: 1_100,
                        title = title,
                    )
                },
                next = null,
                nextBoundaryEpochMillis = nextBoundaryEpochMillis,
            )
        }
    }

    override fun observeDataChanges(): Flow<Unit> = changes
}

private class FakePlaybackSessionStateSource : PlaybackSessionStateSource {
    val state = MutableStateFlow(PlaybackSessionState.Idle)
    override val playbackSessionState: StateFlow<PlaybackSessionState> = state
}
