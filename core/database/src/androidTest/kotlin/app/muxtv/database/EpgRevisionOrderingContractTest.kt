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
class EpgRevisionOrderingContractTest {
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
    fun olderRevisionCannotReplaceNewerActiveGuide() = runBlocking {
        dao.insertSource(
            EpgSourceEntity(
                id = SOURCE_ID,
                name = "Synthetic EPG",
                providerSourceId = null,
                accessRef = null,
                defaultZoneId = null,
            ),
        )
        val older = dao.beginNextRevision(SOURCE_ID, startedAtEpochMillis = 10)
        val newer = dao.beginNextRevision(SOURCE_ID, startedAtEpochMillis = 20)
        dao.insertProgrammes(listOf(programme(older, 1, 1_000)))
        dao.insertProgrammes(listOf(programme(newer, 1, 2_000)))

        assertThat(
            dao.activateRevision(
                sourceId = SOURCE_ID,
                revisionNumber = newer,
                activatedAtEpochMillis = 30,
                statistics = statistics(),
            ),
        ).isEqualTo(
            EpgRevisionActivationResult.Activated(
                revisionNumber = newer,
                previousRevisionNumber = 0,
                programmeCount = 1,
            ),
        )

        assertThat(
            dao.activateRevision(
                sourceId = SOURCE_ID,
                revisionNumber = older,
                activatedAtEpochMillis = 40,
                statistics = statistics(),
            ),
        ).isEqualTo(EpgRevisionActivationResult.Superseded)
        assertThat(dao.activeRevision(SOURCE_ID)).isEqualTo(newer)
        assertThat(dao.revisionStatus(SOURCE_ID, newer)).isEqualTo(EpgRevisionEntity.STATUS_ACTIVE)
        assertThat(dao.revisionStatus(SOURCE_ID, older)).isNull()
        assertThat(dao.revisionNumbers(SOURCE_ID)).containsExactly(newer)
        Unit
    }

    private fun programme(
        revisionNumber: Long,
        sequenceNumber: Long,
        startEpochMillis: Long,
    ): EpgProgrammeEntity = EpgProgrammeEntity(
        sourceId = SOURCE_ID,
        revisionNumber = revisionNumber,
        sequenceNumber = sequenceNumber,
        externalChannelId = "channel-one",
        startEpochMillis = startEpochMillis,
        stopEpochMillis = startEpochMillis + 1_000,
        primaryTitle = "Programme",
        primaryLanguage = "en",
        subtitle = null,
        description = null,
        category = null,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )

    private fun statistics(): EpgRevisionStatistics = EpgRevisionStatistics(
        acceptedChannels = 0,
        acceptedProgrammes = 1,
        skippedProgrammes = 0,
        warningCount = 0,
        unresolvedTimeCount = 0,
    )

    private companion object {
        const val SOURCE_ID = "epg-source-ordering"
    }
}