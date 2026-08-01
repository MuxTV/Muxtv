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
class EpgRefreshStoreTest {
    private lateinit var database: MuxTvDatabase
    private lateinit var store: EpgRefreshStore

    @Before
    fun setUp() = runTest {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            MuxTvDatabase::class.java,
        ).build()
        store = RoomEpgRefreshStore(database.epgRefreshDao())
        insertSource(accessRef = ACCESS_A)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun policyRoundTripsWithoutProviderValues() = runTest {
        val policy = EpgRefreshPolicy(
            sourceId = SOURCE_ID,
            enabled = true,
            intervalMinutes = 60,
            unmeteredOnly = true,
            requiresCharging = false,
            updatedAtEpochMillis = 90,
        )

        store.upsertPolicy(policy)

        assertThat(store.getPolicy(SOURCE_ID)).isEqualTo(policy)
        assertThat(store.getPolicies()).containsExactly(policy)

        store.removePolicy(SOURCE_ID)
        assertThat(store.getPolicy(SOURCE_ID)).isNull()
    }

    @Test
    fun freshLeaseCannotBeStolenButStaleLeaseCanBeReclaimed() = runTest {
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "run-1",
                startedAtEpochMillis = 100,
                staleBeforeEpochMillis = 0,
            ),
        ).isTrue()

        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "run-2",
                startedAtEpochMillis = 150,
                staleBeforeEpochMillis = 99,
            ),
        ).isFalse()

        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "run-2",
                startedAtEpochMillis = 300,
                staleBeforeEpochMillis = 200,
            ),
        ).isTrue()
    }

    @Test
    fun notModifiedIsSuccessfulWithoutNewRevisionAndRotatesValidators() = runTest {
        completeRefreshed(
            runToken = "run-1",
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 120,
            revisionNumber = 7,
            validators = EpgRefreshHttpValidators(
                etag = "etag-a",
                lastModified = "last-modified-a",
            ),
        )

        acquire("run-2", 200)
        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-2",
            trigger = EpgRefreshTrigger.PERIODIC,
            completion = EpgRefreshCompletion.NotModified(
                completedAtEpochMillis = 220,
                accessRefBinding = ACCESS_A,
                validators = EpgRefreshHttpValidators(
                    etag = "etag-b",
                    lastModified = "last-modified-b",
                ),
            ),
            expectedAccessRef = ACCESS_A,
        )

        val status = store.observeStatus(SOURCE_ID).first()
        assertThat(status?.state).isEqualTo(EpgRefreshRunState.SUCCEEDED)
        assertThat(status?.lastSuccessRevision).isEqualTo(7)
        assertThat(status?.lastSuccessAtEpochMillis).isEqualTo(220)
        assertThat(status?.resultCode).isEqualTo(EpgRefreshCompletion.RESULT_NOT_MODIFIED)
        assertThat(status?.httpStatus).isEqualTo(304)

        val attempts = store.getRecentAttempts(SOURCE_ID)
        assertThat(attempts).hasSize(2)
        assertThat(attempts.first().resultCode).isEqualTo(EpgRefreshCompletion.RESULT_NOT_MODIFIED)
        assertThat(attempts.first().revisionNumber).isNull()

        val target = store.getTarget(SOURCE_ID)
        assertThat(target?.activeRevision).isEqualTo(0)
        assertThat(target?.validators?.etag).isEqualTo("etag-b")
        assertThat(target?.validators?.lastModified).isEqualTo("last-modified-b")
    }

    @Test
    fun refreshedResponseWithoutValidatorsClearsPreviousValidators() = runTest {
        completeRefreshed(
            runToken = "run-1",
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 120,
            revisionNumber = 7,
            validators = EpgRefreshHttpValidators(etag = "etag-a"),
        )
        assertThat(store.getTarget(SOURCE_ID)?.validators?.etag).isEqualTo("etag-a")

        completeRefreshed(
            runToken = "run-2",
            startedAtEpochMillis = 200,
            completedAtEpochMillis = 220,
            revisionNumber = 8,
            validators = EpgRefreshHttpValidators(),
        )

        assertThat(store.getTarget(SOURCE_ID)?.validators?.isEmpty).isTrue()
    }

    @Test
    fun terminalFailurePreservesSuccessfulValidators() = runTest {
        completeRefreshed(
            runToken = "run-1",
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 120,
            revisionNumber = 7,
            validators = EpgRefreshHttpValidators(etag = "etag-a"),
        )

        acquire("run-2", 200)
        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-2",
            trigger = EpgRefreshTrigger.PERIODIC,
            completion = EpgRefreshCompletion.Terminal(
                state = EpgRefreshRunState.FAILED,
                completedAtEpochMillis = 220,
                resultFamily = "NETWORK",
                resultCode = "TIMEOUT",
            ),
            expectedAccessRef = ACCESS_A,
        )

        val status = store.observeStatus(SOURCE_ID).first()
        assertThat(status?.state).isEqualTo(EpgRefreshRunState.FAILED)
        assertThat(status?.lastSuccessRevision).isEqualTo(7)
        assertThat(status?.lastSuccessAtEpochMillis).isEqualTo(120)
        assertThat(store.getTarget(SOURCE_ID)?.validators?.etag).isEqualTo("etag-a")
    }

    @Test
    fun reclaimedOldTokenCannotFinishOrOverwriteValidators() = runTest {
        acquire("old-run", 100)
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = "new-run",
                startedAtEpochMillis = 300,
                staleBeforeEpochMillis = 200,
            ),
        ).isTrue()

        store.complete(
            sourceId = SOURCE_ID,
            runToken = "old-run",
            trigger = EpgRefreshTrigger.MANUAL,
            completion = EpgRefreshCompletion.NotModified(
                completedAtEpochMillis = 310,
                accessRefBinding = ACCESS_A,
                validators = EpgRefreshHttpValidators(etag = "stale-etag"),
            ),
            expectedAccessRef = ACCESS_A,
        )

        assertThat(store.observeStatus(SOURCE_ID).first()?.state)
            .isEqualTo(EpgRefreshRunState.RUNNING)
        assertThat(store.getRecentAttempts(SOURCE_ID)).isEmpty()
        assertThat(store.getTarget(SOURCE_ID)?.validators?.isEmpty).isTrue()

        store.complete(
            sourceId = SOURCE_ID,
            runToken = "new-run",
            trigger = EpgRefreshTrigger.PERIODIC,
            completion = EpgRefreshCompletion.NotModified(
                completedAtEpochMillis = 320,
                accessRefBinding = ACCESS_A,
                validators = EpgRefreshHttpValidators(etag = "current-etag"),
            ),
            expectedAccessRef = ACCESS_A,
        )

        assertThat(store.getRecentAttempts(SOURCE_ID)).hasSize(1)
        assertThat(store.getTarget(SOURCE_ID)?.validators?.etag).isEqualTo("current-etag")
    }

    @Test
    fun accessRefChangeInvalidatesValidatorsWithoutChangingActiveRevision() = runTest {
        completeRefreshed(
            runToken = "run-1",
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 120,
            revisionNumber = 9,
            validators = EpgRefreshHttpValidators(etag = "etag-a"),
        )
        assertThat(store.getTarget(SOURCE_ID)?.validators?.etag).isEqualTo("etag-a")

        insertSource(accessRef = ACCESS_B)

        val target = store.getTarget(SOURCE_ID)
        assertThat(target?.activeRevision).isEqualTo(0)
        assertThat(target?.validators?.isEmpty).isTrue()
    }

    @Test
    fun responseFromOldAccessRefCannotPublishSuccessOrValidatorsToChangedSource() = runTest {
        acquire("run-1", 100)
        insertSource(accessRef = ACCESS_B)

        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-1",
            trigger = EpgRefreshTrigger.MANUAL,
            completion = EpgRefreshCompletion.NotModified(
                completedAtEpochMillis = 120,
                accessRefBinding = ACCESS_A,
                validators = EpgRefreshHttpValidators(etag = "old-resource-etag"),
            ),
            expectedAccessRef = ACCESS_A,
        )

        val status = requireNotNull(store.observeStatus(SOURCE_ID).first())
        assertThat(status.state).isEqualTo(EpgRefreshRunState.CANCELLED)
        assertThat(status.lastSuccessAtEpochMillis).isNull()
        assertThat(status.resultFamily).isEqualTo(EpgRefreshCompletion.RESULT_FAMILY)
        assertThat(status.resultCode).isEqualTo("SUPERSEDED")
        assertThat(status.httpStatus).isNull()

        val attempts = store.getRecentAttempts(SOURCE_ID)
        assertThat(attempts).hasSize(1)
        assertThat(attempts.single().state).isEqualTo(EpgRefreshRunState.CANCELLED)
        assertThat(attempts.single().resultCode).isEqualTo("SUPERSEDED")
        assertThat(attempts.single().revisionNumber).isNull()

        val target = requireNotNull(store.getTarget(SOURCE_ID))
        assertThat(target.accessRef).isEqualTo(ACCESS_B)
        assertThat(target.validators.isEmpty).isTrue()
    }

    @Test
    fun unchangedAccessRefPreservesValidators() = runTest {
        completeRefreshed(
            runToken = "run-1",
            startedAtEpochMillis = 100,
            completedAtEpochMillis = 120,
            revisionNumber = 9,
            validators = EpgRefreshHttpValidators(etag = "etag-a"),
        )

        insertSource(accessRef = ACCESS_A)

        assertThat(store.getTarget(SOURCE_ID)?.validators?.etag).isEqualTo("etag-a")
    }

    @Test
    fun attemptsAreBoundedPerSource() = runTest {
        repeat(MAX_EPG_REFRESH_ATTEMPTS + 5) { index ->
            val runToken = "run-$index"
            val startedAt = 1_000L + (index * 10L)
            acquire(runToken, startedAt)
            store.complete(
                sourceId = SOURCE_ID,
                runToken = runToken,
                trigger = EpgRefreshTrigger.STARTUP,
                completion = EpgRefreshCompletion.Terminal(
                    state = EpgRefreshRunState.FAILED,
                    completedAtEpochMillis = startedAt + 1,
                    resultFamily = "NETWORK",
                    resultCode = "TIMEOUT",
                ),
                expectedAccessRef = ACCESS_A,
            )
        }

        val attempts = store.getRecentAttempts(SOURCE_ID)
        assertThat(attempts).hasSize(MAX_EPG_REFRESH_ATTEMPTS)
        assertThat(attempts.first().completedAtEpochMillis)
            .isEqualTo(1_000L + ((MAX_EPG_REFRESH_ATTEMPTS + 4) * 10L) + 1)
    }

    @Test
    fun sensitiveRefreshModelsRedactRawValuesFromToString() = runTest {
        val validators = EpgRefreshHttpValidators(
            etag = "secret-etag-value",
            lastModified = "secret-last-modified-value",
        )
        val completion = EpgRefreshCompletion.Refreshed(
            completedAtEpochMillis = 120,
            accessRefBinding = ACCESS_A,
            revisionNumber = 7,
            channelCount = 1,
            programmeCount = 1,
            skippedProgrammeCount = 0,
            warningCount = 0,
            unresolvedTimeCount = 0,
            validators = validators,
        )
        acquire("run-1", 100)
        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-1",
            trigger = EpgRefreshTrigger.MANUAL,
            completion = completion,
            expectedAccessRef = ACCESS_A,
        )

        val targetText = requireNotNull(store.getTarget(SOURCE_ID)).toString()
        val validatorText = validators.toString()
        val completionText = completion.toString()
        assertThat(targetText).doesNotContain(ACCESS_A)
        assertThat(targetText).doesNotContain("secret-etag-value")
        assertThat(targetText).doesNotContain("secret-last-modified-value")
        assertThat(validatorText).doesNotContain("secret-etag-value")
        assertThat(validatorText).doesNotContain("secret-last-modified-value")
        assertThat(completionText).doesNotContain(ACCESS_A)
        assertThat(completionText).doesNotContain("secret-etag-value")
        assertThat(completionText).doesNotContain("secret-last-modified-value")
    }

    private suspend fun completeRefreshed(
        runToken: String,
        startedAtEpochMillis: Long,
        completedAtEpochMillis: Long,
        revisionNumber: Long,
        validators: EpgRefreshHttpValidators,
    ) {
        acquire(runToken, startedAtEpochMillis)
        store.complete(
            sourceId = SOURCE_ID,
            runToken = runToken,
            trigger = EpgRefreshTrigger.MANUAL,
            completion = EpgRefreshCompletion.Refreshed(
                completedAtEpochMillis = completedAtEpochMillis,
                accessRefBinding = ACCESS_A,
                revisionNumber = revisionNumber,
                channelCount = 12,
                programmeCount = 300,
                skippedProgrammeCount = 2,
                warningCount = 1,
                unresolvedTimeCount = 3,
                validators = validators,
            ),
            expectedAccessRef = ACCESS_A,
        )
    }

    private suspend fun acquire(runToken: String, startedAtEpochMillis: Long) {
        assertThat(
            store.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = runToken,
                startedAtEpochMillis = startedAtEpochMillis,
                staleBeforeEpochMillis = startedAtEpochMillis - 1,
            ),
        ).isTrue()
    }

    private suspend fun insertSource(accessRef: String) {
        database.epgRevisionDao().insertSource(
            EpgSourceEntity(
                id = SOURCE_ID,
                name = "Synthetic guide",
                providerSourceId = null,
                accessRef = accessRef,
                defaultZoneId = "UTC",
            ),
        )
    }

    private companion object {
        const val SOURCE_ID = "epg-source-1"
        const val ACCESS_A = "access-a"
        const val ACCESS_B = "access-b"
    }
}
