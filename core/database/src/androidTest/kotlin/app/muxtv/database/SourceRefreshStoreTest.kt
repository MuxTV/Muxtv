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
            expectedCredentialRef = CREDENTIAL_REFERENCE,
        )
        assertThat(store.observeStatus(SOURCE_ID).first()?.state)
            .isEqualTo(SourceRefreshRunState.RUNNING)

        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-2",
            trigger = SourceRefreshTrigger.PERIODIC,
            completion = success(completedAt = 2_000_500, revision = 2),
            expectedCredentialRef = CREDENTIAL_REFERENCE,
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
        val policy = policy()

        store.upsertPolicy(policy)

        assertThat(store.getPolicies()).containsExactly(policy)
    }

    @Test
    fun removePolicyDeletesOnlySchedulingConfiguration() = runTest {
        insertSource()
        store.upsertPolicy(policy())

        store.removePolicy(SOURCE_ID)

        assertThat(store.getPolicies()).isEmpty()
        assertThat(store.getTarget(SOURCE_ID)?.sourceName).isEqualTo("Primary")
    }

    @Test
    fun overviewCombinesPolicyAndTypedStatusWithoutCredentialReferenceValue() = runTest {
        insertSource()
        store.upsertPolicy(policy())
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "run-overview",
                startedAtEpochMillis = 10_000,
                staleBeforeEpochMillis = 0,
            ),
        ).isTrue()
        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-overview",
            trigger = SourceRefreshTrigger.MANUAL,
            completion = success(completedAt = 11_000, revision = 3),
            expectedCredentialRef = CREDENTIAL_REFERENCE,
        )

        val overview = store.observeOverviews().first().single()

        assertThat(overview.sourceId).isEqualTo(SOURCE_ID)
        assertThat(overview.sourceName).isEqualTo("Primary")
        assertThat(overview.hasCredentialReference).isTrue()
        assertThat(overview.activeRevision).isEqualTo(0)
        assertThat(overview.policy).isEqualTo(policy())
        assertThat(overview.status?.state).isEqualTo(SourceRefreshRunState.SUCCEEDED)
        assertThat(overview.status?.lastSuccessRevision).isEqualTo(3)
        assertThat(overview.toString()).doesNotContain(CREDENTIAL_REFERENCE)
    }

    private fun policy(): SourceRefreshPolicy = SourceRefreshPolicy(
        sourceId = SOURCE_ID,
        enabled = true,
        intervalMinutes = 60,
        unmeteredOnly = true,
        requiresCharging = false,
        updatedAtEpochMillis = 5_000,
    )

    private suspend fun insertSource() {
        database.sourceRevisionDao().upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Primary",
                credentialRef = CREDENTIAL_REFERENCE,
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
        const val CREDENTIAL_REFERENCE = "4b86c9b3-e9e9-4af7-a8ac-02ea23574989"
    }
}
