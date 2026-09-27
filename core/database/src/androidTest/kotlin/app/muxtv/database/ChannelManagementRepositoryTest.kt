package app.muxtv.database

import androidx.paging.testing.asSnapshot
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.catalog.ChannelManagementQuery
import app.muxtv.catalog.ChannelManagementVisibility
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelManagementRepositoryTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        database.profileDao().insert(ProfileEntity(PROFILE_ID, "Primary", isPrimary = true))
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun managementPagingDoesNotObserveOrQueryGuide() = runTest {
        activateSource(
            sourceId = "source-a",
            providerChannelId = "provider-a",
            streamVariantId = "variant-a",
            channelNumber = "10",
        )
        val repository = RoomChannelBrowseRepository(
            dao = database.channelBrowseDao(),
            guideRepository = ExplodingGuideRepository,
            nowEpochMillis = { 1_000L },
        )

        val rows = repository.managementPages(query()).asSnapshot()

        assertThat(rows.map { it.channelId }).containsExactly(CANONICAL_CHANNEL_ID)
    }

    @Test
    fun managementPagingAggregatesActiveVariantsAcrossProvidersIntoOneCanonicalRow() = runTest {
        activateSource(
            sourceId = "source-a",
            providerChannelId = "provider-a",
            streamVariantId = "variant-a",
            channelNumber = "10",
        )
        activateSource(
            sourceId = "source-b",
            providerChannelId = "provider-b",
            streamVariantId = "variant-b",
            channelNumber = "20",
        )
        val repository = RoomChannelBrowseRepository(
            dao = database.channelBrowseDao(),
            guideRepository = ExplodingGuideRepository,
            nowEpochMillis = { 1_000L },
        )

        val rows = repository.managementPages(query()).asSnapshot()

        assertThat(rows).hasSize(1)
        assertThat(rows.single().channelId).isEqualTo(CANONICAL_CHANNEL_ID)
        assertThat(rows.single().variantCount).isEqualTo(2)
        assertThat(rows.single().defaultChannelNumber).isEqualTo("10")
    }

    private fun query() = ChannelManagementQuery(
        profileId = PROFILE_ID,
        visibility = ChannelManagementVisibility.ALL,
    )

    private suspend fun activateSource(
        sourceId: String,
        providerChannelId: String,
        streamVariantId: String,
        channelNumber: String,
    ) {
        revisionStore.upsertSource(SourceDefinition(sourceId, sourceId))
        revisionStore.beginRevision(
            sourceId = sourceId,
            revisionNumber = 1L,
            startedAtEpochMillis = 1_000L,
        )
        revisionStore.stageBatch(
            sourceId = sourceId,
            revisionNumber = 1L,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = providerChannelId,
                    providerKey = "provider:$providerChannelId",
                    rawName = "Canonical Channel",
                    canonicalChannelId = CANONICAL_CHANNEL_ID,
                    canonicalDisplayName = "Canonical Channel",
                    streamVariantId = streamVariantId,
                    locator = "https://example.invalid/$streamVariantId.m3u8",
                    channelNumber = channelNumber,
                ),
            ),
        )
        revisionStore.activate(
            sourceId = sourceId,
            revisionNumber = 1L,
            activatedAtEpochMillis = 1_500L,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
    }

    private object ExplodingGuideRepository : EpgGuideRepository {
        override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> =
            error("Management paging must not query guide data")

        override fun observeDataChanges(): Flow<Unit> =
            error("Management paging must not observe guide data")
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CANONICAL_CHANNEL_ID = "channel-shared"
    }
}
