package app.muxtv.database

import androidx.paging.PagingSource
import androidx.paging.testing.asSnapshot
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelBrowseFilter
import app.muxtv.catalog.ChannelBrowseQuery
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelBrowseRepositoryTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var repository: RoomChannelBrowseRepository

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        repository = RoomChannelBrowseRepository(
            dao = database.channelBrowseDao(),
            guideRepository = RoomEpgGuideRepository(database.epgGuideDao()),
            nowEpochMillis = { 1_000L },
        )
        database.profileDao().insert(ProfileEntity(PROFILE_ID, "Primary", isPrimary = true))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun pagesBeyondInitialSixtyFourWithoutCatalogLimit() = runTest {
        activateRevision(revisionNumber = 1L, channelCount = 65)

        val snapshot = repository.pages(query(ChannelBrowseFilter.ALL)).asSnapshot {
            scrollTo(64)
        }

        assertThat(snapshot).hasSize(65)
        assertThat(snapshot.last().channelId).isEqualTo("channel-00065")
        assertThat(snapshot.last().guideState.name).isEqualTo("NO_GUIDE")
    }

    @Test
    fun guideFailureDoesNotHidePlayableChannels() = runTest {
        activateRevision(revisionNumber = 1L, channelCount = 1)
        val failingRepository = RoomChannelBrowseRepository(
            dao = database.channelBrowseDao(),
            guideRepository = object : EpgGuideRepository {
                override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> =
                    error("guide unavailable")

                override fun observeDataChanges(): Flow<Unit> = flowOf(Unit)
            },
            nowEpochMillis = { 1_000L },
        )

        val rows = failingRepository.pages(query(ChannelBrowseFilter.ALL)).asSnapshot()

        assertThat(rows).hasSize(1)
        assertThat(rows.single().channelId).isEqualTo("channel-00001")
        assertThat(rows.single().guideState.name).isEqualTo("NO_GUIDE")
    }

    @Test
    fun favoriteAndRecentFiltersUseIndependentDeterministicOrders() = runTest {
        activateRevision(revisionNumber = 1L, channelCount = 3)
        database.catalogDao().insertOverlay(
            UserChannelOverlayEntity(
                profileId = PROFILE_ID,
                canonicalChannelId = "channel-00002",
                isFavorite = true,
            ),
        )
        val recent = RoomRecentChannelsRepository(database.recentChannelsDao())
        recent.recordSuccessfulPlayback(PROFILE_ID, "channel-00001", 1_000L)
        recent.recordSuccessfulPlayback(PROFILE_ID, "channel-00003", 3_000L)

        val favorites = repository.pages(query(ChannelBrowseFilter.FAVORITES)).asSnapshot()
        val recentRows = repository.pages(query(ChannelBrowseFilter.RECENT)).asSnapshot()

        assertThat(favorites.map { it.channelId }).containsExactly("channel-00002")
        assertThat(recentRows.map { it.channelId })
            .containsExactly("channel-00003", "channel-00001")
            .inOrder()
    }

    @Test
    fun activeRevisionInvalidatesExistingPagingSource() = runTest {
        activateRevision(revisionNumber = 1L, channelCount = 1)
        val source = database.channelBrowseDao().pageActiveChannels(PROFILE_ID, false)
        val first = source.load(refresh(loadSize = 64)) as PagingSource.LoadResult.Page
        assertThat(first.data.single().channelId).isEqualTo("channel-00001")

        activateRevision(revisionNumber = 2L, channelCount = 2)
        database.invalidationTracker.refresh("sources", "provider_channels")

        assertThat(source.invalid).isTrue()
        val replacement = database.channelBrowseDao().pageActiveChannels(PROFILE_ID, false)
        val second = replacement.load(refresh(loadSize = 64)) as PagingSource.LoadResult.Page
        assertThat(second.data.map { it.channelId })
            .containsExactly("channel-00001", "channel-00002")
            .inOrder()
    }

    private fun query(filter: ChannelBrowseFilter) = ChannelBrowseQuery(PROFILE_ID, filter)

    private fun refresh(
        key: Int? = null,
        loadSize: Int,
    ) = PagingSource.LoadParams.Refresh<Int>(
        key = key,
        loadSize = loadSize,
        placeholdersEnabled = false,
    )

    private suspend fun activateRevision(revisionNumber: Long, channelCount: Int) {
        if (revisionNumber == 1L) {
            revisionStore.upsertSource(SourceDefinition(SOURCE_ID, "Provider"))
        }
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            startedAtEpochMillis = revisionNumber * 1_000L,
        )
        (1..channelCount).chunked(500).forEach { indexes ->
            revisionStore.stageBatch(
                sourceId = SOURCE_ID,
                revisionNumber = revisionNumber,
                entries = indexes.map { index -> stagedEntry(revisionNumber, index) },
            )
        }
        revisionStore.activate(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = revisionNumber * 1_000L + 500L,
            statistics = SourceRevisionStatistics(
                parsedEntries = channelCount,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
    }

    private fun stagedEntry(revisionNumber: Long, index: Int): StagedCatalogEntry {
        val suffix = index.toString().padStart(5, '0')
        return StagedCatalogEntry(
            providerChannelId = "provider-$revisionNumber-$suffix",
            providerKey = "tvg:$suffix",
            rawName = "Channel $suffix",
            canonicalChannelId = "channel-$suffix",
            canonicalDisplayName = "Channel $suffix",
            streamVariantId = "variant-$revisionNumber-$suffix",
            locator = "https://example.invalid/$suffix.m3u8",
            channelNumber = index.toString(),
        )
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val SOURCE_ID = "source-main"
    }
}
