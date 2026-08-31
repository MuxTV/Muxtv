package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChannelSearchMultiSourceFairnessTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var sourceStore: SourceRevisionStore
    private lateinit var dao: ChannelSearchDao

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        DatabaseInitializer(database).initialize()
        sourceStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        dao = database.channelSearchDao()

        publishSource(SOURCE_A, SOURCE_A_COUNT)
        publishSource(SOURCE_B, SOURCE_B_COUNT)
        publishSource(SOURCE_C, SOURCE_C_COUNT)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun boundedCandidateStageRetainsRelevantLaterSourcesDeterministically() = runTest {
        val first = searchCandidates()
        val second = searchCandidates()
        val firstIds = first.map(ChannelSearchCandidateRow::canonicalChannelId)
        val secondIds = second.map(ChannelSearchCandidateRow::canonicalChannelId)

        assertThat(first).hasSize(ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
        assertThat(firstIds).containsAtLeast("b-0000", "c-0000")
        assertThat(firstIds).isEqualTo(secondIds)
    }

    @Test
    fun restrictedRequeryNeverAddsCandidatesOutsideRestriction() = runTest {
        val restricted = listOf("a-0900", "b-0000")
        val ids = dao.searchCandidates(
            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
            ftsExpression = "common*",
            nowEpochMillis = 0,
            fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
            restrictToCanonicalIds = restricted,
        ).map(ChannelSearchCandidateRow::canonicalChannelId)

        assertThat(ids).containsExactlyElementsIn(restricted)
    }

    @Test
    fun hiddenSourceCandidatesCannotConsumeDiversityReserve() = runTest {
        repeat(SOURCE_B_COUNT) { index ->
            database.catalogDao().insertOverlay(
                UserChannelOverlayEntity(
                    profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                    canonicalChannelId = canonicalId(SOURCE_B, index),
                    isHidden = true,
                ),
            )
        }

        val ids = searchCandidates().map(ChannelSearchCandidateRow::canonicalChannelId)

        assertThat(ids).contains("c-0000")
        assertThat(ids.none { it.startsWith("b-") }).isTrue()
        assertThat(ids).hasSize(ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
    }

    @Test
    fun canonicalRepresentedByMultipleSourcesStillAppearsOnce() = runTest {
        publishSharedCanonicalSource(
            sourceId = SOURCE_D,
            canonicalChannelId = "a-0000",
        )

        val ids = searchCandidates().map(ChannelSearchCandidateRow::canonicalChannelId)

        assertThat(ids.count { it == "a-0000" }).isEqualTo(1)
        assertThat(ids).hasSize(ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
    }

    private suspend fun searchCandidates(): List<ChannelSearchCandidateRow> = dao.searchCandidates(
        profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
        ftsExpression = "common*",
        nowEpochMillis = 0,
        fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
    )

    private suspend fun publishSource(sourceId: String, channelCount: Int) {
        sourceStore.upsertSource(SourceDefinition(sourceId, "Source $sourceId", credentialRef = null))
        sourceStore.beginRevision(sourceId, REVISION, startedAtEpochMillis = 10)

        (0 until channelCount)
            .map { index -> stagedEntry(sourceId, index) }
            .chunked(STAGING_BATCH_SIZE)
            .forEach { batch ->
                sourceStore.stageBatch(
                    sourceId = sourceId,
                    revisionNumber = REVISION,
                    entries = batch,
                )
            }

        activateSource(sourceId = sourceId, channelCount = channelCount)
    }

    private suspend fun publishSharedCanonicalSource(
        sourceId: String,
        canonicalChannelId: String,
    ) {
        sourceStore.upsertSource(SourceDefinition(sourceId, "Source $sourceId", credentialRef = null))
        sourceStore.beginRevision(sourceId, REVISION, startedAtEpochMillis = 10)
        sourceStore.stageBatch(
            sourceId = sourceId,
            revisionNumber = REVISION,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-$sourceId-shared",
                    providerKey = "provider-key-$sourceId-shared",
                    rawName = "Common Match $sourceId shared",
                    canonicalChannelId = canonicalChannelId,
                    canonicalDisplayName = "Common Match shared",
                    streamVariantId = "variant-$sourceId-shared",
                    locator = "https://example.invalid/$sourceId/shared",
                    tvgName = "Common Match shared",
                ),
            ),
        )
        activateSource(sourceId = sourceId, channelCount = 1)
    }

    private suspend fun activateSource(sourceId: String, channelCount: Int) {
        assertThat(
            sourceStore.activate(
                sourceId = sourceId,
                revisionNumber = REVISION,
                activatedAtEpochMillis = 20,
                statistics = SourceRevisionStatistics(
                    parsedEntries = channelCount,
                    skippedEntries = 0,
                    warningCount = 0,
                ),
            ),
        ).isInstanceOf(SourceRevisionActivationResult.Activated::class.java)
    }

    private fun stagedEntry(sourceId: String, index: Int): StagedCatalogEntry {
        val suffix = index.toString().padStart(4, '0')
        val canonicalId = "$sourceId-$suffix"
        val name = "Common Match $sourceId $suffix"
        return StagedCatalogEntry(
            providerChannelId = "provider-$sourceId-$suffix",
            providerKey = "provider-key-$sourceId-$suffix",
            rawName = name,
            canonicalChannelId = canonicalId,
            canonicalDisplayName = name,
            streamVariantId = "variant-$sourceId-$suffix",
            locator = "https://example.invalid/$sourceId/$suffix",
            tvgName = name,
        )
    }

    private fun canonicalId(sourceId: String, index: Int): String =
        "$sourceId-${index.toString().padStart(4, '0')}"

    private companion object {
        const val SOURCE_A = "a"
        const val SOURCE_B = "b"
        const val SOURCE_C = "c"
        const val SOURCE_D = "d"
        const val SOURCE_A_COUNT = 1_000
        const val SOURCE_B_COUNT = 20
        const val SOURCE_C_COUNT = 5
        const val REVISION = 1L
        const val STAGING_BATCH_SIZE = 500
    }
}
