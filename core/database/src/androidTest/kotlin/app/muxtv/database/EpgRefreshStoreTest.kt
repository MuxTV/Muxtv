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
        insertSource(accessRef = "access-a")
    }

    @After
    fun tearDown() {
        database.close()
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
        acquire("run-1", 100)
        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-1",
            trigger = EpgRefreshTrigger.MANUAL,
            completion = EpgRefreshCompletion.Refreshed(
                completedAtEpochMillis = 120,
                revisionNumber = 7,
                channelCount = 12,
                programmeCount = 300,
                skippedProgrammeCount = 2,
                warningCount = 1,
                unresolvedTimeCount = 3,
                validators = EpgRefreshHttpValidators(
                    etag = "etag-a",
                    lastModified = "last-modified-a",
                ),
            ),
        )

        acquire("run-2", 200)
        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-2",
            trigger = EpgRefreshTrigger.PERIODIC,
            completion = EpgRefreshCompletion.NotModified(
                completedAtEpochMillis = 220,
                validators = EpgRefreshHttpValidators(
                    etag = "etag-b",
                    lastModified = "last-modified-b",
                ),
            ),
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
                validators = EpgRefreshHttpValidators(etag = "stale-etag"),
            ),
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
                validators = EpgRefreshHttpValidators(etag = "current-etag"),
            ),
        )

        assertThat(store.getRecentAttempts(SOURCE_ID)).hasSize(1)
        assertThat(store.getTarget(SOURCE_ID)?.validators?.etag).isEqualTo("current-etag")
    }

    @Test
    fun accessRefChangeInvalidatesValidatorsWithoutChangingActiveRevision() = runTest {
        acquire("run-1", 100)
        store.complete(
            sourceId = SOURCE_ID,
            runToken = "run-1",
            trigger = EpgRefreshTrigger.MANUAL,
            completion = EpgRefreshCompletion.Refreshed(
                completedAtEpochMillis = 120,
                revisionNumber = 9,
                channelCount = 1,
                programmeCount = 1,
                skippedProgrammeCount = 0,
                warningCount = 0,
                unresolvedTimeCount = 0,
                validators = EpgRefreshHttpValidators(etag = "etag-a"),
            ),
        )
        assertThat(store.getTarget(SOURCE_ID)?.validators?.etag).isEqualTo("etag-a")

        insertSource(accessRef = "access-b")

        val target = store.getTarget(SOURCE_ID)
        assertThat(target?.activeRevision).isEqualTo(0)
        assertThat(target?.validators?.isEmpty).isTrue()
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
            )
        }

        val attempts = store.getRecentAttempts(SOURCE_ID)
        assertThat(attempts).hasSize(MAX_EPG_REFRESH_ATTEMPTS)
        assertThat(attempts.first().runToken).isEqualTo("run-${MAX_EPG_REFRESH_ATTEMPTS + 4}")
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
    }
}
