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
class EpgRefreshCompletionAccessGuardContractTest {
    @Test
    fun oldEndpointFailureCannotPublishFailureToReplacementEndpoint() = runTest {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        try {
            val revisionDao = database.epgRevisionDao()
            revisionDao.insertSource(source(ACCESS_A))
            val store: EpgRefreshStore = RoomEpgRefreshStore(database.epgRefreshDao())
            assertThat(store.tryAcquire(SOURCE_ID, "run-a", 100, 99)).isTrue()
            revisionDao.insertSource(source(ACCESS_B))

            store.complete(
                sourceId = SOURCE_ID,
                runToken = "run-a",
                trigger = EpgRefreshTrigger.PERIODIC,
                completion = EpgRefreshCompletion.Terminal(
                    state = EpgRefreshRunState.NEEDS_AUTH,
                    completedAtEpochMillis = 120,
                    resultFamily = "HTTP",
                    resultCode = "401",
                    httpStatus = 401,
                ),
                expectedAccessRef = ACCESS_A,
            )

            val status = requireNotNull(store.observeStatus(SOURCE_ID).first())
            assertThat(status.state).isEqualTo(EpgRefreshRunState.CANCELLED)
            assertThat(status.resultFamily).isEqualTo(EpgRefreshCompletion.RESULT_FAMILY)
            assertThat(status.resultCode).isEqualTo(EpgRefreshCompletion.RESULT_SUPERSEDED)
            assertThat(status.httpStatus).isNull()
        } finally {
            database.close()
        }
    }

    private fun source(accessRef: String) = EpgSourceEntity(
        id = SOURCE_ID,
        name = "Guide",
        providerSourceId = null,
        accessRef = accessRef,
        defaultZoneId = "UTC",
    )

    private companion object {
        const val SOURCE_ID = "epg-source-completion-guard"
        const val ACCESS_A = "access-a"
        const val ACCESS_B = "access-b"
    }
}
