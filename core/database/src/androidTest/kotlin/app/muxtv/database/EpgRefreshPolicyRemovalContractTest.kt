package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgRefreshPolicyRemovalContractTest {
    @Test
    fun removingPolicyClearsSchedulingStateButKeepsEpgSource() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        try {
            database.epgRevisionDao().insertSource(
                EpgSourceEntity(
                    id = SOURCE_ID,
                    name = "Guide",
                    providerSourceId = null,
                    accessRef = "access-a",
                    defaultZoneId = "UTC",
                ),
            )
            val store: EpgRefreshStore = RoomEpgRefreshStore(database.epgRefreshDao())
            store.upsertPolicy(
                EpgRefreshPolicy(
                    sourceId = SOURCE_ID,
                    enabled = true,
                    intervalMinutes = 60,
                    unmeteredOnly = false,
                    requiresCharging = false,
                    updatedAtEpochMillis = 100,
                ),
            )
            assertThat(
                store.tryAcquire(
                    sourceId = SOURCE_ID,
                    runToken = "run-1",
                    startedAtEpochMillis = 100,
                    staleBeforeEpochMillis = 99,
                ),
            ).isTrue()

            store.removePolicy(SOURCE_ID)

            assertThat(store.getPolicy(SOURCE_ID)).isNull()
            assertThat(store.observeStatus(SOURCE_ID).first()).isNull()
            assertThat(store.getTarget(SOURCE_ID)).isNotNull()
        } finally {
            database.close()
        }
    }

    private companion object {
        const val SOURCE_ID = "epg-source-policy-removal"
    }
}
