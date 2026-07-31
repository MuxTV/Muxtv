package app.muxtv.database

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgSourceMetadataContractTest {
    @Test
    fun metadataRefreshPreservesActiveRevisionPointer() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MuxTvDatabase::class.java,
        ).build()
        try {
            val dao = database.epgRevisionDao()
            dao.insertSource(
                EpgSourceEntity(
                    id = "epg-source",
                    name = "Original",
                    providerSourceId = null,
                    accessRef = "access-one",
                    defaultZoneId = null,
                ),
            )
            dao.insertRevision(
                EpgRevisionEntity(
                    sourceId = "epg-source",
                    revisionNumber = 1,
                    status = EpgRevisionEntity.STATUS_STAGING,
                    startedAtEpochMillis = 10,
                ),
            )
            dao.stageBatch(
                channels = listOf(
                    EpgChannelEntity(
                        sourceId = "epg-source",
                        revisionNumber = 1,
                        externalId = "channel",
                        primaryDisplayName = "Channel",
                        primaryLanguage = null,
                        iconRef = null,
                    ),
                ),
                programmes = listOf(
                    EpgProgrammeEntity(
                        sourceId = "epg-source",
                        revisionNumber = 1,
                        sequenceNumber = 1,
                        externalChannelId = "channel",
                        startEpochMillis = 1_000,
                        stopEpochMillis = 2_000,
                        primaryTitle = "Programme",
                        primaryLanguage = null,
                        subtitle = null,
                        description = null,
                        category = null,
                        iconRef = null,
                        episodeNumber = null,
                        isNew = false,
                    ),
                ),
            )
            assertThat(
                dao.activateRevision(
                    sourceId = "epg-source",
                    revisionNumber = 1,
                    activatedAtEpochMillis = 20,
                    statistics = EpgRevisionStatistics(1, 1, 0, 0, 0),
                ),
            ).isInstanceOf(EpgRevisionActivationResult.Activated::class.java)

            dao.insertSource(
                EpgSourceEntity(
                    id = "epg-source",
                    name = "Renamed",
                    providerSourceId = null,
                    accessRef = "access-two",
                    defaultZoneId = "Europe/Stockholm",
                ),
            )

            assertThat(dao.activeRevision("epg-source")).isEqualTo(1)
            assertThat(
                dao.activeProgrammes(
                    sourceId = "epg-source",
                    externalChannelIds = listOf("channel"),
                    fromEpochMillis = 0,
                    toEpochMillis = 3_000,
                    limit = 10,
                ),
            ).hasSize(1)
        } finally {
            database.close()
        }
    }
}
