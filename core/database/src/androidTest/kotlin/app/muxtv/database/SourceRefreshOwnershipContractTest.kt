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
class SourceRefreshOwnershipContractTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var revisionStore: SourceRevisionStore
    private lateinit var refreshStore: SourceRefreshStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        revisionStore = RoomSourceRevisionStore(database.sourceRevisionDao())
        refreshStore = RoomSourceRefreshStore(database.sourceRefreshDao())
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Guide source",
                credentialRef = CREDENTIAL_A,
            ),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun replacedCredentialCannotActivateOldStagingRevision() = runTest {
        stageRevision(1)
        acquire(runToken = "run-a", startedAtEpochMillis = 100)
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Replacement source",
                credentialRef = CREDENTIAL_B,
            ),
        )

        val result = revisionStore.activateIfRefreshOwnerMatches(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            expectedCredentialRef = CREDENTIAL_A,
            expectedRunToken = "run-a",
            activatedAtEpochMillis = 120,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )

        assertThat(result).isEqualTo(SourceRevisionActivationResult.Superseded)
        assertThat(database.sourceRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(0)
        assertThat(database.sourceRevisionDao().countRevisionEntries(SOURCE_ID, 1)).isEqualTo(0)
    }

    @Test
    fun reclaimedOldRunTokenCannotActivateEvenWhenCredentialIsUnchanged() = runTest {
        stageRevision(1)
        acquire(runToken = "old-run", startedAtEpochMillis = 100)
        assertThat(
            refreshStore.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "new-run",
                startedAtEpochMillis = 300,
                staleBeforeEpochMillis = 200,
            ),
        ).isTrue()

        val result = revisionStore.activateIfRefreshOwnerMatches(
            sourceId = SOURCE_ID,
            revisionNumber = 1,
            expectedCredentialRef = CREDENTIAL_A,
            expectedRunToken = "old-run",
            activatedAtEpochMillis = 320,
            statistics = SourceRevisionStatistics(
                parsedEntries = 1,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )

        assertThat(result).isEqualTo(SourceRevisionActivationResult.Superseded)
        assertThat(database.sourceRevisionDao().activeRevision(SOURCE_ID)).isEqualTo(0)
    }

    @Test
    fun staleTerminalCompletionCannotPublishFailureToReplacementCredential() = runTest {
        acquire(runToken = "run-a", startedAtEpochMillis = 100)
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Replacement source",
                credentialRef = CREDENTIAL_B,
            ),
        )

        refreshStore.complete(
            sourceId = SOURCE_ID,
            runToken = "run-a",
            trigger = SourceRefreshTrigger.PERIODIC,
            completion = SourceRefreshCompletion(
                state = SourceRefreshRunState.NEEDS_AUTH,
                resultFamily = "HTTP",
                resultCode = "401",
                completedAtEpochMillis = 120,
                httpStatus = 401,
            ),
            expectedCredentialRef = CREDENTIAL_A,
        )

        val status = requireNotNull(refreshStore.observeStatus(SOURCE_ID).first())
        assertThat(status.state).isEqualTo(SourceRefreshRunState.CANCELLED)
        assertThat(status.failureFamily).isEqualTo(SourceRefreshCompletion.RESULT_FAMILY)
        assertThat(status.failureCode).isEqualTo(SourceRefreshCompletion.RESULT_SUPERSEDED)
        assertThat(status.httpStatus).isNull()
        assertThat(refreshStore.getRecentAttempts(SOURCE_ID)).hasSize(1)
    }

    @Test
    fun missingCredentialSnapshotCannotPublishAfterCredentialIsAttached() = runTest {
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Guide source",
                credentialRef = null,
            ),
        )
        acquire(runToken = "run-a", startedAtEpochMillis = 100)
        revisionStore.upsertSource(
            SourceDefinition(
                id = SOURCE_ID,
                name = "Guide source",
                credentialRef = CREDENTIAL_B,
            ),
        )

        refreshStore.complete(
            sourceId = SOURCE_ID,
            runToken = "run-a",
            trigger = SourceRefreshTrigger.STARTUP,
            completion = SourceRefreshCompletion(
                state = SourceRefreshRunState.NEEDS_AUTH,
                resultFamily = "CREDENTIAL",
                resultCode = "MISSING_REFERENCE",
                completedAtEpochMillis = 120,
            ),
            expectedCredentialRef = null,
        )

        val status = requireNotNull(refreshStore.observeStatus(SOURCE_ID).first())
        assertThat(status.state).isEqualTo(SourceRefreshRunState.CANCELLED)
        assertThat(status.failureCode).isEqualTo(SourceRefreshCompletion.RESULT_SUPERSEDED)
    }

    private suspend fun stageRevision(revisionNumber: Long) {
        revisionStore.beginRevision(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            startedAtEpochMillis = 10,
        )
        revisionStore.stageBatch(
            sourceId = SOURCE_ID,
            revisionNumber = revisionNumber,
            entries = listOf(
                StagedCatalogEntry(
                    providerChannelId = "provider-channel-$revisionNumber",
                    providerKey = "provider-key-$revisionNumber",
                    rawName = "Channel",
                    canonicalChannelId = "canonical-channel",
                    canonicalDisplayName = "Channel",
                    streamVariantId = "variant-$revisionNumber",
                    locator = "https://example.invalid/live/$revisionNumber",
                ),
            ),
        )
    }

    private suspend fun acquire(
        runToken: String,
        startedAtEpochMillis: Long,
    ) {
        assertThat(
            refreshStore.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = runToken,
                startedAtEpochMillis = startedAtEpochMillis,
                staleBeforeEpochMillis = startedAtEpochMillis - 1,
            ),
        ).isTrue()
    }

    private companion object {
        const val SOURCE_ID = "source-ownership-contract"
        const val CREDENTIAL_A = "00000000-0000-0000-0000-000000000076"
        const val CREDENTIAL_B = "00000000-0000-0000-0000-000000000077"
    }
}
