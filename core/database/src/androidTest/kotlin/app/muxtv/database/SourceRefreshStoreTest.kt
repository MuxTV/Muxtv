package app.muxtv.database

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceRefreshStoreTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: SourceRefreshStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomSourceRefreshStore(database.sourceRefreshDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun leasePreventsOverlapAndIgnoresLateCompletionFromReclaimedRun() = runTest {
        insertSource()

        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "run-1",
                startedAtEpochMillis = 1_000,
                staleBeforeEpochMillis = 0,
            ),
        ).isTrue()
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "run-2",
                startedAtEpochMillis = 1_100,
                staleBeforeEpochMillis = 0,
            ),
        ).isFalse()
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "run-2",
                startedAtEpochMillis = 2_000_000,
                staleBeforeEpochMillis = 100_000,
            ),
        ).isTrue()

        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-1",
            trigger = SourceRefreshTrigger.MANUAL,
            completion = success(completedAt = 2_000_100, revision = 1),
        )
        assertThat(store.observeStatus(SOURCE_ID).first()?.state)
            .isEqualTo(SourceRefreshRunState.RUNNING)

        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-2",
            trigger = SourceRefreshTrigger.PERIODIC,
            completion = success(completedAt = 2_000_500, revision = 2),
        )

        val status = store.observeStatus(SOURCE_ID).first()
        assertThat(status?.state).isEqualTo(SourceRefreshRunState.SUCCEEDED)
        assertThat(status?.lastSuccessRevision).isEqualTo(2)
        assertThat(status?.skippedEntries).isEqualTo(1)

        val attempts = store.getRecentAttempts(SOURCE_ID)
        assertThat(attempts).hasSize(1)
        assertThat(attempts.single().trigger).isEqualTo(SourceRefreshTrigger.PERIODIC)
        assertThat(attempts.single().revisionNumber).isEqualTo(2)
    }

    @Test
    fun policyRoundTripsWithoutEmbeddingCredentialData() = runTest {
        insertSource()
        val policy = SourceRefreshPolicy(
            sourceId = SOURCE_ID,
            enabled = true,
            intervalMinutes = 60,
            unmeteredOnly = true,
            requiresCharging = false,
            updatedAtEpochMillis = 5_000,
        )

        store.upsertPolicy(policy)

        assertThat(store.getPolicies()).containsExactly(policy)
    }

    private suspend fun insertSource() {
        database.sourceRevisionDao().upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Primary",
                credentialRef = "4b86c9b3-e9e9-4af7-a8ac-02ea23574989",
            ),
        )
    }

    private fun success(
        completedAt: Long,
        revision: Long,
    ): SourceRefreshCompletion = SourceRefreshCompletion(
        state = SourceRefreshRunState.SUCCEEDED,
        resultFamily = "SUCCESS",
        resultCode = null,
        completedAtEpochMillis = completedAt,
        revisionNumber = revision,
        parsedEntries = 10,
        skippedEntries = 1,
        warningCount = 2,
    )

    private companion object {
        const val SOURCE_ID = "source-1"
    }
}
