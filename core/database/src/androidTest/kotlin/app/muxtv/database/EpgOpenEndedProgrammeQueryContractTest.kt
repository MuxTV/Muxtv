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
class EpgOpenEndedProgrammeQueryContractTest {
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
    fun openEndedProgrammeDoesNotLeakIntoFutureWindows() = runBlocking {
        dao.insertSource(
            EpgSourceEntity(
                id = SOURCE_ID,
                name = "Synthetic EPG",
                providerSourceId = null,
                accessRef = null,
                defaultZoneId = null,
            ),
        )
        dao.insertRevision(
            EpgRevisionEntity(
                sourceId = SOURCE_ID,
                revisionNumber = 1,
                status = EpgRevisionEntity.STATUS_STAGING,
                startedAtEpochMillis = 1,
            ),
        )
        dao.stageBatch(
            channels = listOf(
                EpgChannelEntity(
                    sourceId = SOURCE_ID,
                    revisionNumber = 1,
                    externalId = CHANNEL_ID,
                    primaryDisplayName = "Synthetic channel",
                    primaryLanguage = "en",
                    iconRef = null,
                ),
            ),
            programmes = listOf(
                programme(sequenceNumber = 1, startEpochMillis = 1_000, stopEpochMillis = null),
                programme(sequenceNumber = 2, startEpochMillis = 5_000, stopEpochMillis = 6_000),
            ),
        )
        dao.activateRevision(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            activatedAtEpochMillis = 2,
            statistics = EpgRevisionStatistics(
                acceptedChannels = 1,
                acceptedProgrammes = 2,
                skippedProgrammes = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
            ),
        )

        assertThat(dao.activeProgrammes(SOURCE_ID, listOf(CHANNEL_ID), 0, 2_000, 20))
            .containsExactly(programme(1, 1_000, null))
        assertThat(dao.activeProgrammes(SOURCE_ID, listOf(CHANNEL_ID), 4_000, 7_000, 20))
            .containsExactly(programme(2, 5_000, 6_000))
        Unit
    }

    private fun programme(
        sequenceNumber: Long,
        startEpochMillis: Long,
        stopEpochMillis: Long?,
    ): EpgProgrammeEntity = EpgProgrammeEntity(
        sourceId = SOURCE_ID,
        revisionNumber = 1,
        sequenceNumber = sequenceNumber,
        externalChannelId = CHANNEL_ID,
        startEpochMillis = startEpochMillis,
        stopEpochMillis = stopEpochMillis,
        primaryTitle = "Programme $sequenceNumber",
        primaryLanguage = "en",
        subtitle = null,
        description = null,
        category = null,
        iconRef = null,
        episodeNumber = null,
        isNew = false,
    )

    private companion object {
        const val SOURCE_ID = "epg-source-open-ended"
        const val CHANNEL_ID = "channel-open-ended"
    }
}
