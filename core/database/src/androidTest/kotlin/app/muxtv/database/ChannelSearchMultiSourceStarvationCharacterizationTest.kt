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
class ChannelSearchMultiSourceStarvationCharacterizationTest {
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
    fun boundedCandidateStageCanStarveLaterSourceIdsBeforeRanking() = runTest {
        val candidates = dao.searchCandidates(
            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
            ftsExpression = "common*",
            nowEpochMillis = 0,
            fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
        )
        val candidateIds = candidates.map(ChannelSearchCandidateRow::canonicalChannelId)

        assertThat(candidates).hasSize(ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
        assertThat(candidateIds).containsNoneOf("b-0000", "c-0000")
        assertThat(candidateIds.all { it.startsWith("$SOURCE_A-") }).isTrue()
    }

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

    private companion object {
        const val SOURCE_A = "a"
        const val SOURCE_B = "b"
        const val SOURCE_C = "c"
        const val SOURCE_A_COUNT = 1_000
        const val SOURCE_B_COUNT = 20
        const val SOURCE_C_COUNT = 5
        const val REVISION = 1L
        const val STAGING_BATCH_SIZE = 500
    }
}
