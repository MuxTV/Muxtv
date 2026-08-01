package app.muxtv.database

import androidx.room3.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgRevisionAccessGuardContractTest {
    @Test
    fun activationIsSupersededWhenRemoteAccessBindingChanges() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MuxTvDatabase::class.java,
        ).build()
        try {
            val store = RoomEpgRevisionStore(database.epgRevisionDao())
            store.upsertSource(
                EpgSourceDefinition(
                    id = SOURCE_ID,
                    name = "Guide",
                    providerSourceId = null,
                    accessRef = ACCESS_A,
                    defaultZoneId = "UTC",
                ),
            )
            val revision = store.beginRevision(SOURCE_ID, startedAtEpochMillis = 100)
            store.stageBatch(
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

            // Simulate the user replacing the remote EPG endpoint while the old request is in flight.
            store.upsertSource(
                EpgSourceDefinition(
                    id = SOURCE_ID,
                    name = "Guide",
                    providerSourceId = null,
                    accessRef = ACCESS_B,
                    defaultZoneId = "UTC",
                ),
            )

            val result = store.activateRevisionIfAccessMatches(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                expectedAccessRef = ACCESS_A,
                activatedAtEpochMillis = 200,
                statistics = EpgRevisionStatistics(
                    acceptedChannels = 1,
                    acceptedProgrammes = 1,
                    skippedProgrammes = 0,
                    warningCount = 0,
                    unresolvedTimeCount = 0,
                ),
            )

            assertThat(result).isEqualTo(EpgRevisionActivationResult.Superseded)
            assertThat(database.epgRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(0)
            assertThat(database.epgRevisionDao().revisionStatus(SOURCE_ID, revision)).isNull()
        } finally {
            database.close()
        }
    }

    @Test
    fun matchingAccessBindingStillActivates() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            MuxTvDatabase::class.java,
        ).build()
        try {
            val store = RoomEpgRevisionStore(database.epgRevisionDao())
            store.upsertSource(
                EpgSourceDefinition(
                    id = SOURCE_ID,
                    name = "Guide",
                    providerSourceId = null,
                    accessRef = ACCESS_A,
                    defaultZoneId = "UTC",
                ),
            )
            val revision = store.beginRevision(SOURCE_ID, startedAtEpochMillis = 100)
            store.stageBatch(
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

            val result = store.activateRevisionIfAccessMatches(
                sourceId = SOURCE_ID,
                revisionNumber = revision,
                expectedAccessRef = ACCESS_A,
                activatedAtEpochMillis = 200,
                statistics = EpgRevisionStatistics(1, 1, 0, 0, 0),
            )

            assertThat(result).isInstanceOf(EpgRevisionActivationResult.Activated::class.java)
            assertThat(database.epgRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(revision)
        } finally {
            database.close()
        }
    }

    private companion object {
        const val SOURCE_ID = "epg-source-1"
        const val ACCESS_A = "access-a"
        const val ACCESS_B = "access-b"
    }
}
