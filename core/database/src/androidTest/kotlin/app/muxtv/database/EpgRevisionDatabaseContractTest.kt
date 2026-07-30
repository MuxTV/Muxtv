package app.muxtv.database

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgRevisionDatabaseContractTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var dao: EpgRevisionDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MuxTvDatabase::class.java,
        ).build()
        dao = database.epgRevisionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun stagingIsInvisibleAndActivationPublishesOneImmutableRevision() = runBlocking {
        dao.insertSource(
            EpgSourceEntity(
                id = SOURCE_ID,
                name = "Synthetic EPG",
                providerSourceId = null,
                accessRef = "opaque-access-ref",
                defaultZoneId = null,
            ),
        )
        dao.insertRevision(stagingRevision(1))
        dao.stageBatch(
            channels = listOf(channel(1, "news")),
            programmes = listOf(programme(1, 1, "news", 1_000, 2_000)),
        )

        assertThat(dao.activeProgrammes(SOURCE_ID, listOf("news"), 0, 10_000, 20)).isEmpty()

        val result = dao.activateRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 100,
            statistics = EpgRevisionStatistics(
                acceptedChannels = 1,
                acceptedProgrammes = 1,
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )

        assertThat(result).isEqualTo(
            EpgRevisionActivationResult.Activated(
                revisionNumber = 1,
                previousRevisionNumber = 0,
                programmeCount = 1,
            ),
        )
        assertThat(dao.activeRevision(SOURCE_ID)).isEqualTo(1)
        assertThat(dao.activeProgrammes(SOURCE_ID, listOf("news"), 0, 10_000, 20))
            .containsExactly(programme(1, 1, "news", 1_000, 2_000))
        assertThat(dao.revisionStatus(SOURCE_ID, 1)).isEqualTo(EpgRevisionEntity.STATUS_ACTIVE)
    }

    @Test
    fun emptyRevisionCannotReplacePreviousGoodGuide() = runBlocking {
        dao.insertSource(epgSource())
        dao.insertRevision(stagingRevision(1))
        dao.stageBatch(
            channels = listOf(channel(1, "news")),
            programmes = listOf(programme(1, 1, "news", 1_000, 2_000)),
        )
        assertThat(activate(1)).isInstanceOf(EpgRevisionActivationResult.Activated::class.java)

        dao.insertRevision(stagingRevision(2))
        val rejected = activate(2)

        assertThat(rejected).isEqualTo(EpgRevisionActivationResult.EmptyRevisionRejected)
        assertThat(dao.activeRevision(SOURCE_ID)).isEqualTo(1)
        assertThat(dao.revisionStatus(SOURCE_ID, 1)).isEqualTo(EpgRevisionEntity.STATUS_ACTIVE)
        assertThat(dao.revisionStatus(SOURCE_ID, 2)).isEqualTo(EpgRevisionEntity.STATUS_STAGING)
    }

    @Test
    fun thirdActivationRetainsOnlyCurrentAndPreviousGoodRevisions() = runBlocking {
        dao.insertSource(epgSource())
        for (revision in 1L..3L) {
            dao.insertRevision(stagingRevision(revision))
            dao.stageBatch(
                channels = listOf(channel(revision, "news")),
                programmes = listOf(
                    programme(
                        revisionNumber = revision,
                        sequenceNumber = 1,
                        externalChannelId = "news",
                        startEpochMillis = revision * 1_000,
                        stopEpochMillis = revision * 1_000 + 500,
                    ),
                ),
            )
            assertThat(activate(revision)).isInstanceOf(EpgRevisionActivationResult.Activated::class.java)
        }

        assertThat(dao.activeRevision(SOURCE_ID)).isEqualTo(3)
        assertThat(dao.revisionNumbers(SOURCE_ID)).containsExactly(2L, 3L).inOrder()
        assertThat(dao.revisionStatus(SOURCE_ID, 2)).isEqualTo(EpgRevisionEntity.STATUS_RETAINED)
        assertThat(dao.revisionStatus(SOURCE_ID, 3)).isEqualTo(EpgRevisionEntity.STATUS_ACTIVE)
        assertThat(dao.countRevisionProgrammes(SOURCE_ID, 1)).isEqualTo(0)
        assertThat(dao.countRevisionProgrammes(SOURCE_ID, 2)).isEqualTo(1)
        assertThat(dao.countRevisionProgrammes(SOURCE_ID, 3)).isEqualTo(1)
    }

    @Test
    fun discardDeletesOnlyRequestedStagingRevision() = runBlocking {
        dao.insertSource(epgSource())
        dao.insertRevision(stagingRevision(1))
        dao.stageBatch(
            channels = listOf(channel(1, "news")),
            programmes = listOf(programme(1, 1, "news", 1_000, 2_000)),
        )
        activate(1)

        dao.insertRevision(stagingRevision(2))
        dao.stageBatch(
            channels = listOf(channel(2, "news")),
            programmes = listOf(programme(2, 1, "news", 3_000, 4_000)),
        )
        dao.discardRevision(SOURCE_ID, 2)

        assertThat(dao.revisionNumbers(SOURCE_ID)).containsExactly(1L)
        assertThat(dao.activeRevision(SOURCE_ID)).isEqualTo(1)
        assertThat(dao.activeProgrammes(SOURCE_ID, listOf("news"), 0, 10_000, 20))
            .containsExactly(programme(1, 1, "news", 1_000, 2_000))
        Unit
    }

    private suspend fun activate(revisionNumber: Long): EpgRevisionActivationResult =
        dao.activateRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = revisionNumber * 100,
            statistics = EpgRevisionStatistics(
                acceptedChannels = 1,
                acceptedProgrammes = dao.countRevisionProgrammes(SOURCE_ID, revisionNumber),
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )

    private fun epgSource(): EpgSourceEntity = EpgSourceEntity(
        id = SOURCE_ID,
        name = "Synthetic EPG",
        providerSourceId = null,
        accessRef = "opaque-access-ref",
        defaultZoneId = null,
    )

    private fun stagingRevision(revisionNumber: Long): EpgRevisionEntity = EpgRevisionEntity(
        sourceId = SOURCE_ID,
        revisionNumber = revisionNumber,
        status = EpgRevisionEntity.STATUS_STAGING,
        startedAtEpochMillis = revisionNumber * 10,
    )

    private fun channel(revisionNumber: Long, externalId: String): EpgChannelEntity =
        EpgChannelEntity(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            externalId = externalId,
            primaryDisplayName = "Synthetic channel",
            primaryLanguage = "en",
            iconRef = null,
        )

    private fun programme(
        revisionNumber: Long,
        sequenceNumber: Long,
        externalChannelId: String,
        startEpochMillis: Long,
        stopEpochMillis: Long,
    ): EpgProgrammeEntity = EpgProgrammeEntity(
        sourceId = SOURCE_ID,
        revisionNumber = revisionNumber,
        sequenceNumber = sequenceNumber,
        externalChannelId = externalChannelId,
        startEpochMillis = startEpochMillis,
        stopEpochMillis = stopEpochMillis,
        primaryTitle = "Synthetic programme",
        primaryLanguage = "en",
        subtitle = null,
        description = null,
        category = null,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )

    private companion object {
        const val SOURCE_ID = "epg-source-1"
    }
}
