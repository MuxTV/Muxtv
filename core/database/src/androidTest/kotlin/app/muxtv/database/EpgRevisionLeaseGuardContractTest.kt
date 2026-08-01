package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgRevisionLeaseGuardContractTest {
    @Test
    fun reclaimedWorkerCannotActivateStagedRevision() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        try {
            val revisionStore = RoomEpgRevisionStore(database.epgRevisionDao())
            val refreshStore: EpgRefreshStore = RoomEpgRefreshStore(database.epgRefreshDao())
            revisionStore.upsertSource(
                EpgSourceDefinition(
                    id = SOURCE_ID,
                    name = "Guide",
                    providerSourceId = null,
                    accessRef = ACCESS_REF,
                    defaultZoneId = "UTC",
                ),
            )
            assertThat(
                refreshStore.tryAcquire(
                    sourceId = SOURCE_ID,
                    runToken = OLD_TOKEN,
                    startedAtEpochMillis = 100,
                    staleBeforeEpochMillis = 99,
                ),
            ).isTrue()

            val revision = revisionStore.beginRevision(SOURCE_ID, startedAtEpochMillis = 110)
            revisionStore.stageBatch(
                channels = listOf(
                    EpgChannelEntity(
                        sourceId = SOURCE_ID,
                        revisionNumber = revision,
                        externalId = "channel-1",
                        primaryDisplayName = "Channel",
                        primaryLanguage = null,
                        iconRef = null,
                    ),
                ),
                programmes = listOf(
                    EpgProgrammeEntity(
                        sourceId = SOURCE_ID,
                        revisionNumber = revision,
                        sequenceNumber = 1,
                        externalChannelId = "channel-1",
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
                refreshStore.tryAcquire(
                    sourceId = SOURCE_ID,
                    runToken = NEW_TOKEN,
                    startedAtEpochMillis = 200,
                    staleBeforeEpochMillis = 150,
                ),
            ).isTrue()

            val activation = revisionStore.activateRevisionIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                expectedAccessRef = ACCESS_REF,
                expectedRunToken = OLD_TOKEN,
                activatedAtEpochMillis = 210,
                statistics = EpgRevisionStatistics(1, 1, 0, 0, 0),
            )

            assertThat(activation).isEqualTo(EpgRevisionActivationResult.Superseded)
            assertThat(database.epgRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(0)
            assertThat(database.epgRevisionDao().revisionStatus(SOURCE_ID, revision)).isNull()
        } finally {
            database.close()
        }
    }

    @Test
    fun removedSchedulingStatePreventsOldWorkerActivation() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        try {
            val revisionStore = RoomEpgRevisionStore(database.epgRevisionDao())
            val refreshStore: EpgRefreshStore = RoomEpgRefreshStore(database.epgRefreshDao())
            revisionStore.upsertSource(
                EpgSourceDefinition(
                    id = SOURCE_ID,
                    name = "Guide",
                    providerSourceId = null,
                    accessRef = ACCESS_REF,
                    defaultZoneId = "UTC",
                ),
            )
            assertThat(refreshStore.tryAcquire(SOURCE_ID, OLD_TOKEN, 100, 99)).isTrue()
            val revision = revisionStore.beginRevision(SOURCE_ID, startedAtEpochMillis = 110)
            revisionStore.stageBatch(
                channels = listOf(
                    EpgChannelEntity(SOURCE_ID, revision, "channel-1", "Channel", null, null),
                ),
                programmes = listOf(
                    EpgProgrammeEntity(
                        SOURCE_ID,
                        revision,
                        1,
                        "channel-1",
                        1_000,
                        2_000,
                        "Programme",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        false,
                    ),
                ),
            )
            refreshStore.removePolicy(SOURCE_ID)

            val activation = revisionStore.activateRevisionIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                expectedAccessRef = ACCESS_REF,
                expectedRunToken = OLD_TOKEN,
                activatedAtEpochMillis = 210,
                statistics = EpgRevisionStatistics(1, 1, 0, 0, 0),
            )

            assertThat(activation).isEqualTo(EpgRevisionActivationResult.Superseded)
            assertThat(database.epgRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(0)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val SOURCE_ID = "epg-source-lease-guard"
        const val ACCESS_REF = "access-a"
        const val OLD_TOKEN = "run-old"
        const val NEW_TOKEN = "run-new"
    }
}
