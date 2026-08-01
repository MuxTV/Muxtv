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
class RefreshCompletionDispositionContractTest {
    private lateinit var database: MuxTvDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun sourceBindingChangeAfterRetryableNetworkDecisionSupersedesWorkCompletion() = runTest {
        val store: SourceRefreshStore = RoomSourceRefreshStore(database.sourceRefreshDao())
        database.sourceRevisionDao().upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Source",
                credentialRef = CREDENTIAL_A,
            ),
        )
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = RUN_TOKEN,
                startedAtEpochMillis = 100,
                staleBeforeEpochMillis = 99,
            ),
        ).isTrue()
        database.sourceRevisionDao().upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Replacement",
                credentialRef = CREDENTIAL_B,
            ),
        )

        val disposition = store.completeWithDisposition(
            sourceId = SOURCE_ID,
            runToken = RUN_TOKEN,
            trigger = SourceRefreshTrigger.PERIODIC,
            completion = SourceRefreshCompletion(
                state = SourceRefreshRunState.FAILED,
                resultFamily = "HTTP",
                resultCode = "503",
                completedAtEpochMillis = 120,
                httpStatus = 503,
            ),
            expectedCredentialRef = CREDENTIAL_A,
        )

        assertThat(disposition).isEqualTo(RefreshCompletionDisposition.SUPERSEDED)
        val attempt = store.getRecentAttempts(SOURCE_ID).single()
        assertThat(attempt.state).isEqualTo(SourceRefreshRunState.CANCELLED)
        assertThat(attempt.resultCode).isEqualTo(SourceRefreshCompletion.RESULT_SUPERSEDED)
        assertThat(attempt.httpStatus).isNull()
    }

    @Test
    fun epgBindingChangeAfterRetryableNetworkDecisionSupersedesWorkCompletion() = runTest {
        val store: EpgRefreshStore = RoomEpgRefreshStore(database.epgRefreshDao())
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = EPG_SOURCE_ID,
                name = "Guide",
                providerSourceId = null,
                accessRef = ACCESS_A,
                defaultZoneId = "UTC",
            ),
        )
        assertThat(
            store.tryAcquire(
                sourceId = EPG_SOURCE_ID,
                runToken = RUN_TOKEN,
                startedAtEpochMillis = 100,
                staleBeforeEpochMillis = 99,
            ),
        ).isTrue()
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = EPG_SOURCE_ID,
                name = "Replacement guide",
                providerSourceId = null,
                accessRef = ACCESS_B,
                defaultZoneId = "UTC",
            ),
        )

        val disposition = store.completeWithDisposition(
            sourceId = EPG_SOURCE_ID,
            runToken = RUN_TOKEN,
            trigger = EpgRefreshTrigger.PERIODIC,
            completion = EpgRefreshCompletion.Terminal(
                state = EpgRefreshRunState.FAILED,
                completedAtEpochMillis = 120,
                resultFamily = "HTTP",
                resultCode = "503",
                httpStatus = 503,
            ),
            expectedAccessRef = ACCESS_A,
        )

        assertThat(disposition).isEqualTo(RefreshCompletionDisposition.SUPERSEDED)
        val attempt = store.getRecentAttempts(EPG_SOURCE_ID).single()
        assertThat(attempt.state).isEqualTo(EpgRefreshRunState.CANCELLED)
        assertThat(attempt.resultCode).isEqualTo(EpgRefreshCompletion.RESULT_SUPERSEDED)
        assertThat(attempt.httpStatus).isNull()
    }

    @Test
    fun reclaimedLeaseReturnsIgnoredDispositionInsteadOfRevivingOldWork() = runTest {
        val store: SourceRefreshStore = RoomSourceRefreshStore(database.sourceRefreshDao())
        database.sourceRevisionDao().upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Source",
                credentialRef = CREDENTIAL_A,
            ),
        )
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "old-run",
                startedAtEpochMillis = 100,
                staleBeforeEpochMillis = 99,
            ),
        ).isTrue()
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "new-run",
                startedAtEpochMillis = 300,
                staleBeforeEpochMillis = 200,
            ),
        ).isTrue()

        val disposition = store.completeWithDisposition(
            sourceId = SOURCE_ID,
            runToken = "old-run",
            trigger = SourceRefreshTrigger.PERIODIC,
            completion = SourceRefreshCompletion(
                state = SourceRefreshRunState.FAILED,
                resultFamily = "HTTP",
                resultCode = "503",
                completedAtEpochMillis = 320,
                httpStatus = 503,
            ),
            expectedCredentialRef = CREDENTIAL_A,
        )

        assertThat(disposition).isEqualTo(RefreshCompletionDisposition.IGNORED)
        assertThat(store.getRecentAttempts(SOURCE_ID)).isEmpty()
    }

    private companion object {
        const val SOURCE_ID = "source-completion-disposition"
        const val EPG_SOURCE_ID = "epg-completion-disposition"
        const val CREDENTIAL_A = "00000000-0000-0000-0000-000000000076"
        const val CREDENTIAL_B = "00000000-0000-0000-0000-000000000077"
        const val ACCESS_A = "00000000-0000-0000-0000-000000000078"
        const val ACCESS_B = "00000000-0000-0000-0000-000000000079"
        const val RUN_TOKEN = "completion-disposition-run"
    }
}
