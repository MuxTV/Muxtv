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
class EpgMatchingFanoutYagniTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: EpgMatchingStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomEpgMatchingStore(database.epgMatchingDao())

        database.sourceRevisionDao().insertSource(
            SourceEntity(
                id = PROVIDER_SOURCE,
                name = "Provider",
                activeRevision = 1,
            ),
        )
        database.sourceRevisionDao().insertRevision(
            SourceRevisionEntity(
                sourceId = PROVIDER_SOURCE,
                revisionNumber = 1,
                status = SourceRevisionEntity.STATUS_ACTIVE,
                startedAtEpochMillis = 10,
                activatedAtEpochMillis = 20,
                parsedEntries = 0,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun providerReconcileProcessesEveryLinkedGuideWithoutSpeculativeHardCap() = runTest {
        repeat(LINKED_GUIDE_COUNT) { index ->
            val epgSourceId = "epg-$index"
            database.epgRevisionDao().insertSource(
                EpgSourceEntity(
                    id = epgSourceId,
                    name = "Guide",
                    providerSourceId = PROVIDER_SOURCE,
                    accessRef = null,
                    defaultZoneId = "UTC",
                    activeRevision = 1,
                ),
            )
            database.epgRevisionDao().insertRevision(
                EpgRevisionEntity(
                    sourceId = epgSourceId,
                    revisionNumber = 1,
                    status = EpgRevisionEntity.STATUS_ACTIVE,
                    startedAtEpochMillis = 10,
                    activatedAtEpochMillis = 20,
                    acceptedChannels = 0,
                ),
            )
        }

        val result = store.reconcileProviderSource(PROVIDER_SOURCE)

        assertThat(result).isEqualTo(
            EpgProviderMatchingReconcileResult.Applied(
                processedCount = LINKED_GUIDE_COUNT,
                appliedCount = LINKED_GUIDE_COUNT,
                notReadyCount = 0,
                supersededCount = 0,
            ),
        )
    }

    private companion object {
        const val PROVIDER_SOURCE = "provider-source"
        const val LINKED_GUIDE_COUNT = 33
    }
}
