package app.muxtv.database.measurement

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import app.muxtv.database.MuxTvDatabase
import app.muxtv.database.RoomSourceRefreshStore
import app.muxtv.database.RoomSourceRevisionStore
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.StagedCatalogEntry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class C03ProductionRoomSupplementalEvidence(
    val repeatedRevisionStorage: List<C03ProductionRoomRepeatedRevisionStorage>,
    val safety: List<C03ProductionRoomSafetyMeasurement>,
)

internal class C03ProductionRoomSupplementalEvidenceRunner(
    context: Context,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val applicationContext = context.applicationContext
    private val databaseSequence = AtomicInteger()

    suspend fun run(spec: C03ProductionRoomMeasurementSpec): C03ProductionRoomSupplementalEvidence =
        withContext(Dispatchers.IO) {
            val repeated = REPEATED_REVISION_COUNTS.flatMap { revisionCount ->
                listOf(
                    measureRepeatedProduction(spec.entryCount, revisionCount),
                    measureRepeatedCandidate(spec.entryCount, revisionCount),
                )
            }
            val safetyEntryCount = spec.entryCount.coerceAtMost(MAX_SAFETY_ENTRY_COUNT)
            C03ProductionRoomSupplementalEvidence(
                repeatedRevisionStorage = repeated,
                safety = listOf(
                    measureProductionSafety(safetyEntryCount),
                    measureCandidateSafety(safetyEntryCount),
                ),
            )
        }

    private suspend fun measureRepeatedProduction(
        entryCount: Int,
        revisionCount: Int,
    ): C03ProductionRoomRepeatedRevisionStorage {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("repeat-a-$revisionCount")
        cleanupDatabase(name)
        prepareProductionBaseline(name, baseline)
        checkpoint(name)
        val before = fileState(name)

        val database = openProduction(name)
        try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            for (revision in 2L..revisionCount.toLong()) {
                val startedAt = revision * 1_000L
                check(
                    refresh.tryAcquire(
                        sourceId = SOURCE_ID,
                        runToken = "repeat-a-$revision",
                        startedAtEpochMillis = startedAt,
                        staleBeforeEpochMillis = startedAt - 1L,
                    ),
                )
                revisions.beginRevision(SOURCE_ID, revision, startedAt)
                val incoming = Fixture.tokenChurn(baseline, revision)
                incoming.chunked(BATCH_SIZE).forEach { batch ->
                    revisions.stageBatch(
                        SOURCE_ID,
                        revision,
                        batch.map { it.toProductionEntry(revision) },
                    )
                }
                check(
                    revisions.activateIfRefreshOwnerMatches(
                        sourceId = SOURCE_ID,
                        revisionNumber = revision,
                        expectedCredentialRef = CREDENTIAL_REF,
                        expectedRunToken = "repeat-a-$revision",
                        activatedAtEpochMillis = startedAt + 100L,
                        statistics = SourceRevisionStatistics(incoming.size, 0, 0),
                    ) is SourceRevisionActivationResult.Activated,
                )
            }
            check(database.activeRevision() == revisionCount.toLong())
            check(database.retainedRevisionCount() <= 1)
        } finally {
            database.close()
        }

        checkpoint(name)
        val beforeCompaction = fileState(name)
        val result = C03ProductionRoomRepeatedRevisionStorage(
            variant = C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION,
            revisionCount = revisionCount,
            before = before,
            beforeCompaction = beforeCompaction,
            afterCompaction = beforeCompaction,
            orphanPayloadRows = 0,
            orphanSearchPayloadRows = 0,
            compactionNanos = 0L,
            compactionDeletedRows = 0,
        )
        cleanupDatabase(name)
        return result
    }

    private suspend fun measureRepeatedCandidate(
        entryCount: Int,
        revisionCount: Int,
    ): C03ProductionRoomRepeatedRevisionStorage {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("repeat-b-$revisionCount")
        cleanupDatabase(name)
        prepareCandidateBaseline(name, baseline)
        checkpoint(name)
        val before = fileState(name)

        var database = openCandidate(name)
        try {
            val dao = database.candidateDao()
            for (revision in 2L..revisionCount.toLong()) {
                val incoming = Fixture.tokenChurn(baseline, revision)
                dao.setRunningRefreshOwner(SOURCE_ID, "repeat-b-$revision")
                dao.beginRevision(SOURCE_ID, revision, revision * 1_000L)
                incoming.chunked(BATCH_SIZE).forEach { batch ->
                    dao.stageBatch(SOURCE_ID, revision, batch.map(FixtureItem::toCandidateEntry))
                }
                check(
                    dao.activateIfRefreshOwnerMatches(
                        sourceId = SOURCE_ID,
                        revisionNumber = revision,
                        expectedCredentialRef = CREDENTIAL_REF,
                        expectedRunToken = "repeat-b-$revision",
                        activatedAtEpochMillis = revision * 1_000L + 100L,
                    ) == C03ProductionCandidateActivationResult.Published,
                )
            }
            check(dao.activeRevision(SOURCE_ID) == revisionCount.toLong())
            check(dao.retainedRevisions(SOURCE_ID).size <= 1)
        } finally {
            database.close()
        }

        checkpoint(name)
        val beforeCompaction = fileState(name)
        database = openCandidate(name)
        val compactionStarted = nanoTime()
        var deletedRows = 0
        var passes = 0
        try {
            val dao = database.candidateDao()
            var fixedPoint = false
            while (passes < MAX_COMPACTION_PASSES && !fixedPoint) {
                passes++
                val compacted = dao.compactOrphans(COMPACTION_BATCH_SIZE)
                val deleted = compacted.payloadRowsDeleted + compacted.searchPayloadRowsDeleted
                deletedRows += deleted
                fixedPoint = deleted == 0
            }
            check(fixedPoint) { "C03 repeated-storage compaction did not reach a bounded fixed point." }
            check(database.orphanPayloadCount() == 0)
            check(database.orphanSearchPayloadCount() == 0)
        } finally {
            database.close()
        }
        val compactionNanos = elapsed(compactionStarted)
        checkpoint(name)
        val afterCompaction = fileState(name)

        val databaseForCounts = openCandidate(name)
        val orphanPayloadRows: Int
        val orphanSearchPayloadRows: Int
        try {
            orphanPayloadRows = databaseForCounts.orphanPayloadCount()
            orphanSearchPayloadRows = databaseForCounts.orphanSearchPayloadCount()
        } finally {
            databaseForCounts.close()
        }

        val result = C03ProductionRoomRepeatedRevisionStorage(
            variant = C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE,
            revisionCount = revisionCount,
            before = before,
            beforeCompaction = beforeCompaction,
            afterCompaction = afterCompaction,
            orphanPayloadRows = orphanPayloadRows,
            orphanSearchPayloadRows = orphanSearchPayloadRows,
            compactionNanos = compactionNanos,
            compactionDeletedRows = deletedRows,
        )
        cleanupDatabase(name)
        return result
    }

    private suspend fun measureProductionSafety(entryCount: Int): C03ProductionRoomSafetyMeasurement {
        val partial = productionPartialFailure(entryCount)
        val stale = productionStaleOwner(entryCount)
        val cancellation = productionCancellation(entryCount)
        return C03ProductionRoomSafetyMeasurement(
            variant = C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION,
            previousGoodPreserved =
                partial.previousGoodPreserved && stale.previousGoodPreserved && cancellation.previousGoodPreserved,
            supersededRejected = stale.supersededRejected,
            partialFailurePublished = partial.published,
            cancellationLatePublished = cancellation.latePublished,
            cleanupBounded = partial.cleanupBounded && stale.cleanupBounded && cancellation.cleanupBounded,
            cancellationCleanupNanos = cancellation.cleanupNanos,
        )
    }

    private suspend fun measureCandidateSafety(entryCount: Int): C03ProductionRoomSafetyMeasurement {
        val partial = candidatePartialFailure(entryCount)
        val stale = candidateStaleOwner(entryCount)
        val cancellation = candidateCancellation(entryCount)
        return C03ProductionRoomSafetyMeasurement(
            variant = C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE,
            previousGoodPreserved =
                partial.previousGoodPreserved && stale.previousGoodPreserved && cancellation.previousGoodPreserved,
            supersededRejected = stale.supersededRejected,
            partialFailurePublished = partial.published,
            cancellationLatePublished = cancellation.latePublished,
            cleanupBounded = partial.cleanupBounded && stale.cleanupBounded && cancellation.cleanupBounded,
            cancellationCleanupNanos = cancellation.cleanupNanos,
        )
    }

    private suspend fun productionPartialFailure(entryCount: Int): SafetyOutcome {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("safety-a-partial")
        cleanupDatabase(name)
        prepareProductionBaseline(name, baseline)
        val database = openProduction(name)
        return try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            check(refresh.tryAcquire(SOURCE_ID, RUN_REFRESH, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE))
            revisions.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            Fixture.tokenChurn(baseline, REFRESH_REVISION)
                .take((entryCount / 2).coerceAtLeast(1))
                .chunked(BATCH_SIZE)
                .forEach { batch ->
                    revisions.stageBatch(
                        SOURCE_ID,
                        REFRESH_REVISION,
                        batch.map { it.toProductionEntry(REFRESH_REVISION) },
                    )
                }
            revisions.discard(SOURCE_ID, REFRESH_REVISION)
            SafetyOutcome(
                previousGoodPreserved = database.activeRevision() == BASELINE_REVISION,
                supersededRejected = false,
                published = database.activeRevision() != BASELINE_REVISION,
                latePublished = false,
                cleanupBounded = database.productionEntryCount(REFRESH_REVISION) == 0,
                cleanupNanos = 1L,
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun productionStaleOwner(entryCount: Int): SafetyOutcome {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("safety-a-stale")
        cleanupDatabase(name)
        prepareProductionBaseline(name, baseline)
        val database = openProduction(name)
        return try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            check(refresh.tryAcquire(SOURCE_ID, RUN_STALE, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE))
            revisions.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            Fixture.tokenChurn(baseline, REFRESH_REVISION).chunked(BATCH_SIZE).forEach { batch ->
                revisions.stageBatch(
                    SOURCE_ID,
                    REFRESH_REVISION,
                    batch.map { it.toProductionEntry(REFRESH_REVISION) },
                )
            }
            check(
                refresh.tryAcquire(
                    sourceId = SOURCE_ID,
                    runToken = RUN_REPLACEMENT,
                    startedAtEpochMillis = REPLACEMENT_STARTED_AT,
                    staleBeforeEpochMillis = REPLACEMENT_STALE_BEFORE,
                ),
            )
            val activation = revisions.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_STALE,
                activatedAtEpochMillis = REPLACEMENT_STARTED_AT + 1L,
                statistics = SourceRevisionStatistics(entryCount, 0, 0),
            )
            SafetyOutcome(
                previousGoodPreserved = database.activeRevision() == BASELINE_REVISION,
                supersededRejected = activation == SourceRevisionActivationResult.Superseded,
                published = activation is SourceRevisionActivationResult.Activated,
                latePublished = false,
                cleanupBounded = database.productionEntryCount(REFRESH_REVISION) == 0,
                cleanupNanos = 1L,
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun productionCancellation(entryCount: Int): SafetyOutcome {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("safety-a-cancel")
        cleanupDatabase(name)
        prepareProductionBaseline(name, baseline)
        val database = openProduction(name)
        return try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            check(refresh.tryAcquire(SOURCE_ID, RUN_REFRESH, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE))
            revisions.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)

            val cleanupStarted = nanoTime()
            coroutineScope {
                val staged = CompletableDeferred<Unit>()
                val job = launch {
                    try {
                        Fixture.tokenChurn(baseline, REFRESH_REVISION)
                            .take((entryCount / 2).coerceAtLeast(1))
                            .chunked(BATCH_SIZE)
                            .forEach { batch ->
                                revisions.stageBatch(
                                    SOURCE_ID,
                                    REFRESH_REVISION,
                                    batch.map { it.toProductionEntry(REFRESH_REVISION) },
                                )
                            }
                        staged.complete(Unit)
                        awaitCancellation()
                    } catch (failure: Throwable) {
                        if (!staged.isCompleted) staged.completeExceptionally(failure)
                        throw failure
                    } finally {
                        withContext(NonCancellable) {
                            revisions.discard(SOURCE_ID, REFRESH_REVISION)
                        }
                    }
                }
                staged.await()
                job.cancelAndJoin()
            }
            val cleanupNanos = elapsed(cleanupStarted)
            val late = revisions.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_REFRESH,
                activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
                statistics = SourceRevisionStatistics(entryCount, 0, 0),
            )
            SafetyOutcome(
                previousGoodPreserved = database.activeRevision() == BASELINE_REVISION,
                supersededRejected = false,
                published = false,
                latePublished = late is SourceRevisionActivationResult.Activated,
                cleanupBounded = database.productionEntryCount(REFRESH_REVISION) == 0,
                cleanupNanos = cleanupNanos,
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun candidatePartialFailure(entryCount: Int): SafetyOutcome {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("safety-b-partial")
        cleanupDatabase(name)
        prepareCandidateBaseline(name, baseline)
        val database = openCandidate(name)
        return try {
            val dao = database.candidateDao()
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_REFRESH)
            dao.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            Fixture.tokenChurn(baseline, REFRESH_REVISION)
                .take((entryCount / 2).coerceAtLeast(1))
                .chunked(BATCH_SIZE)
                .forEach { batch ->
                    dao.stageBatch(SOURCE_ID, REFRESH_REVISION, batch.map(FixtureItem::toCandidateEntry))
                }
            dao.discardRevision(SOURCE_ID, REFRESH_REVISION)
            compactCandidate(database)
            SafetyOutcome(
                previousGoodPreserved = dao.activeRevision(SOURCE_ID) == BASELINE_REVISION,
                supersededRejected = false,
                published = dao.activeRevision(SOURCE_ID) != BASELINE_REVISION,
                latePublished = false,
                cleanupBounded =
                    !dao.revisionExists(SOURCE_ID, REFRESH_REVISION) &&
                        database.orphanPayloadCount() == 0 &&
                        database.orphanSearchPayloadCount() == 0,
                cleanupNanos = 1L,
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun candidateStaleOwner(entryCount: Int): SafetyOutcome {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("safety-b-stale")
        cleanupDatabase(name)
        prepareCandidateBaseline(name, baseline)
        val database = openCandidate(name)
        return try {
            val dao = database.candidateDao()
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_STALE)
            dao.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            Fixture.tokenChurn(baseline, REFRESH_REVISION).chunked(BATCH_SIZE).forEach { batch ->
                dao.stageBatch(SOURCE_ID, REFRESH_REVISION, batch.map(FixtureItem::toCandidateEntry))
            }
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_REPLACEMENT)
            val activation = dao.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_STALE,
                activatedAtEpochMillis = REPLACEMENT_STARTED_AT + 1L,
            )
            compactCandidate(database)
            SafetyOutcome(
                previousGoodPreserved = dao.activeRevision(SOURCE_ID) == BASELINE_REVISION,
                supersededRejected = activation == C03ProductionCandidateActivationResult.Superseded,
                published = activation == C03ProductionCandidateActivationResult.Published,
                latePublished = false,
                cleanupBounded =
                    !dao.revisionExists(SOURCE_ID, REFRESH_REVISION) &&
                        database.orphanPayloadCount() == 0 &&
                        database.orphanSearchPayloadCount() == 0,
                cleanupNanos = 1L,
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun candidateCancellation(entryCount: Int): SafetyOutcome {
        val baseline = Fixture.baseline(entryCount)
        val name = nextDatabaseName("safety-b-cancel")
        cleanupDatabase(name)
        prepareCandidateBaseline(name, baseline)
        val database = openCandidate(name)
        return try {
            val dao = database.candidateDao()
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_REFRESH)
            dao.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)

            val cleanupStarted = nanoTime()
            coroutineScope {
                val staged = CompletableDeferred<Unit>()
                val job = launch {
                    try {
                        Fixture.tokenChurn(baseline, REFRESH_REVISION)
                            .take((entryCount / 2).coerceAtLeast(1))
                            .chunked(BATCH_SIZE)
                            .forEach { batch ->
                                dao.stageBatch(SOURCE_ID, REFRESH_REVISION, batch.map(FixtureItem::toCandidateEntry))
                            }
                        staged.complete(Unit)
                        awaitCancellation()
                    } catch (failure: Throwable) {
                        if (!staged.isCompleted) staged.completeExceptionally(failure)
                        throw failure
                    } finally {
                        withContext(NonCancellable) {
                            dao.discardRevision(SOURCE_ID, REFRESH_REVISION)
                        }
                    }
                }
                staged.await()
                job.cancelAndJoin()
            }
            compactCandidate(database)
            val cleanupNanos = elapsed(cleanupStarted)
            val late = dao.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_REFRESH,
                activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
            )
            SafetyOutcome(
                previousGoodPreserved = dao.activeRevision(SOURCE_ID) == BASELINE_REVISION,
                supersededRejected = false,
                published = false,
                latePublished = late == C03ProductionCandidateActivationResult.Published,
                cleanupBounded =
                    !dao.revisionExists(SOURCE_ID, REFRESH_REVISION) &&
                        database.orphanPayloadCount() == 0 &&
                        database.orphanSearchPayloadCount() == 0,
                cleanupNanos = cleanupNanos,
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun compactCandidate(database: C03ProductionCandidateDatabase) {
        val dao = database.candidateDao()
        var passes = 0
        var fixedPoint = false
        while (passes < MAX_COMPACTION_PASSES && !fixedPoint) {
            passes++
            val compacted = dao.compactOrphans(COMPACTION_BATCH_SIZE)
            fixedPoint = compacted.payloadRowsDeleted + compacted.searchPayloadRowsDeleted == 0
        }
        check(fixedPoint) { "C03 safety compaction did not reach a bounded fixed point." }
    }

    private suspend fun prepareProductionBaseline(name: String, baseline: List<FixtureItem>) {
        val database = openProduction(name)
        try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            revisions.upsertSource(SourceDefinition(SOURCE_ID, "C03 supplemental production", CREDENTIAL_REF))
            check(refresh.tryAcquire(SOURCE_ID, RUN_BASELINE, BASELINE_STARTED_AT, 0L))
            revisions.beginRevision(SOURCE_ID, BASELINE_REVISION, BASELINE_STARTED_AT)
            baseline.chunked(BATCH_SIZE).forEach { batch ->
                revisions.stageBatch(
                    SOURCE_ID,
                    BASELINE_REVISION,
                    batch.map { it.toProductionEntry(BASELINE_REVISION) },
                )
            }
            check(
                revisions.activateIfRefreshOwnerMatches(
                    sourceId = SOURCE_ID,
                    revisionNumber = BASELINE_REVISION,
                    expectedCredentialRef = CREDENTIAL_REF,
                    expectedRunToken = RUN_BASELINE,
                    activatedAtEpochMillis = BASELINE_ACTIVATED_AT,
                    statistics = SourceRevisionStatistics(baseline.size, 0, 0),
                ) is SourceRevisionActivationResult.Activated,
            )
        } finally {
            database.close()
        }
    }

    private suspend fun prepareCandidateBaseline(name: String, baseline: List<FixtureItem>) {
        val database = openCandidate(name)
        try {
            val dao = database.candidateDao()
            dao.upsertSource(SOURCE_ID, CREDENTIAL_REF)
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_BASELINE)
            dao.beginRevision(SOURCE_ID, BASELINE_REVISION, BASELINE_STARTED_AT)
            baseline.chunked(BATCH_SIZE).forEach { batch ->
                dao.stageBatch(SOURCE_ID, BASELINE_REVISION, batch.map(FixtureItem::toCandidateEntry))
            }
            check(
                dao.activateIfRefreshOwnerMatches(
                    sourceId = SOURCE_ID,
                    revisionNumber = BASELINE_REVISION,
                    expectedCredentialRef = CREDENTIAL_REF,
                    expectedRunToken = RUN_BASELINE,
                    activatedAtEpochMillis = BASELINE_ACTIVATED_AT,
                ) == C03ProductionCandidateActivationResult.Published,
            )
        } finally {
            database.close()
        }
    }

    private fun openProduction(name: String): MuxTvDatabase = Room.databaseBuilder(
        applicationContext,
        MuxTvDatabase::class.java,
        name,
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()

    private fun openCandidate(name: String): C03ProductionCandidateDatabase = Room.databaseBuilder(
        applicationContext,
        C03ProductionCandidateDatabase::class.java,
        name,
    ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()

    private suspend fun MuxTvDatabase.activeRevision(): Long = useReaderConnection { connection ->
        connection.usePrepared("SELECT activeRevision FROM sources WHERE id = ?") { statement ->
            statement.bindText(1, SOURCE_ID)
            check(statement.step())
            statement.getLong(0)
        }
    }

    private suspend fun MuxTvDatabase.retainedRevisionCount(): Int = useReaderConnection { connection ->
        connection.usePrepared(
            "SELECT COUNT(*) FROM source_revisions WHERE sourceId = ? AND status = 'RETAINED'",
        ) { statement ->
            statement.bindText(1, SOURCE_ID)
            check(statement.step())
            statement.getLong(0).toInt()
        }
    }

    private suspend fun MuxTvDatabase.productionEntryCount(revision: Long): Int =
        useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT COUNT(*) FROM provider_channels WHERE sourceId = ? AND revisionNumber = ?",
            ) { statement ->
                statement.bindText(1, SOURCE_ID)
                statement.bindLong(2, revision)
                check(statement.step())
                statement.getLong(0).toInt()
            }
        }

    private suspend fun C03ProductionCandidateDatabase.orphanPayloadCount(): Int =
        useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT COUNT(*)
                FROM c03_production_candidate_payloads AS p
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM c03_production_candidate_memberships AS m
                    WHERE m.payloadId = p.payloadId
                )
                """.trimIndent(),
            ) { statement ->
                check(statement.step())
                statement.getLong(0).toInt()
            }
        }

    private suspend fun C03ProductionCandidateDatabase.orphanSearchPayloadCount(): Int =
        useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT COUNT(*)
                FROM c03_production_candidate_search_payloads AS s
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM c03_production_candidate_payloads AS p
                    WHERE p.searchPayloadId = s.searchPayloadId
                )
                """.trimIndent(),
            ) { statement ->
                check(statement.step())
                statement.getLong(0).toInt()
            }
        }

    private fun checkpoint(name: String) {
        val file = applicationContext.getDatabasePath(name)
        if (!file.isFile) return
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) {
                    check(cursor.getInt(0) == 0) { "C03 supplemental WAL checkpoint remained busy." }
                }
            }
        } finally {
            raw.close()
        }
    }

    private fun fileState(name: String): C03ProductionRoomFileState {
        val database = applicationContext.getDatabasePath(name)
        return C03ProductionRoomFileState(
            databaseBytes = database.safeLength(),
            walBytes = File(database.path + "-wal").safeLength(),
            shmBytes = File(database.path + "-shm").safeLength(),
        )
    }

    private fun cleanupDatabase(name: String) {
        applicationContext.deleteDatabase(name)
        val file = applicationContext.getDatabasePath(name)
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }

    private fun nextDatabaseName(kind: String): String =
        "c03-supplemental-$kind-${databaseSequence.incrementAndGet()}.db"

    private fun elapsed(startedAt: Long): Long = (nanoTime() - startedAt).coerceAtLeast(1L)

    private fun File.safeLength(): Long = if (isFile) length().coerceAtLeast(0L) else 0L

    private data class SafetyOutcome(
        val previousGoodPreserved: Boolean,
        val supersededRejected: Boolean,
        val published: Boolean,
        val latePublished: Boolean,
        val cleanupBounded: Boolean,
        val cleanupNanos: Long,
    )

    private data class FixtureItem(
        val ordinal: Long,
        val providerKey: String,
        val logicalChannelId: String,
        val canonicalChannelId: String,
        val rawName: String,
        val tvgId: String,
        val tvgName: String,
        val logoUrl: String,
        val groupTitle: String,
        val channelNumber: String,
        val catchupMode: String,
        val catchupSource: String,
        val catchupDays: Int,
        val catchupCorrection: String,
        val locator: String,
        val userAgent: String,
        val referrer: String,
        val contentHash: String,
        val searchContentHash: String,
    ) {
        fun rehash(): FixtureItem = copy(
            contentHash = contentHash(
                logicalChannelId = logicalChannelId,
                providerKey = providerKey,
                rawName = rawName,
                tvgId = tvgId,
                tvgName = tvgName,
                logoUrl = logoUrl,
                groupTitle = groupTitle,
                channelNumber = channelNumber,
                catchupMode = catchupMode,
                catchupSource = catchupSource,
                catchupDays = catchupDays,
                catchupCorrection = catchupCorrection,
                canonicalChannelId = canonicalChannelId,
                locator = locator,
                userAgent = userAgent,
                referrer = referrer,
            ),
            searchContentHash = searchHash(logicalChannelId, rawName, groupTitle, channelNumber),
        )

        fun toProductionEntry(revision: Long): StagedCatalogEntry = StagedCatalogEntry(
            providerChannelId = physicalId("provider", revision, ordinal),
            providerKey = providerKey,
            rawName = rawName,
            canonicalChannelId = canonicalChannelId,
            canonicalDisplayName = tvgName,
            streamVariantId = physicalId("stream", revision, ordinal),
            locator = locator,
            tvgId = tvgId,
            tvgName = tvgName,
            logoUrl = logoUrl,
            groupTitle = groupTitle,
            channelNumber = channelNumber,
            catchupMode = catchupMode,
            catchupSource = catchupSource,
            catchupDays = catchupDays,
            catchupCorrection = catchupCorrection,
            userAgent = userAgent,
            referrer = referrer,
        )

        fun toCandidateEntry(): C03ProductionCandidateStageEntry = C03ProductionCandidateStageEntry(
            ordinal = ordinal,
            logicalChannelId = logicalChannelId,
            contentHash = contentHash,
            searchContentHash = searchContentHash,
            providerKey = providerKey,
            rawName = rawName,
            tvgId = tvgId,
            tvgName = tvgName,
            logoUrl = logoUrl,
            groupTitle = groupTitle,
            channelNumber = channelNumber,
            locator = locator,
            catchupMode = catchupMode,
            catchupSource = catchupSource,
            catchupDays = catchupDays,
            catchupCorrection = catchupCorrection,
            userAgent = userAgent,
            referrer = referrer,
            searchText = "$rawName $groupTitle $channelNumber",
            canonicalChannelId = canonicalChannelId,
        )
    }

    private object Fixture {
        fun baseline(size: Int): List<FixtureItem> = List(size) { index ->
            val providerKey = "provider:item-${index.toString().padStart(5, '0')}"
            val logical = logicalChannelId(SOURCE_ID, providerKey)
            FixtureItem(
                ordinal = index.toLong(),
                providerKey = providerKey,
                logicalChannelId = logical,
                canonicalChannelId = logical,
                rawName = "Channel $index",
                tvgId = "tvg-$index",
                tvgName = "Channel $index",
                logoUrl = "https://assets.invalid/$index.png",
                groupTitle = "Group ${index % 8}",
                channelNumber = (index + 1).toString(),
                catchupMode = "append",
                catchupSource = "?start={utc}",
                catchupDays = 7,
                catchupCorrection = "0",
                locator = "https://stream.invalid/live/$index?session=baseline",
                userAgent = "MuxTV-C03",
                referrer = "https://provider.invalid/",
                contentHash = "pending",
                searchContentHash = "pending",
            ).rehash()
        }

        fun tokenChurn(previous: List<FixtureItem>, revision: Long): List<FixtureItem> =
            previous.mapIndexed { index, item ->
                item.copy(
                    ordinal = index.toLong(),
                    locator = "https://stream.invalid/live/$index?session=repeat-$revision",
                ).rehash()
            }
    }

    private companion object {
        const val SOURCE_ID = "c03-production-ab-source"
        const val CREDENTIAL_REF = "credential-c03-production-ab"
        const val BASELINE_REVISION = 1L
        const val REFRESH_REVISION = 2L
        const val BASELINE_STARTED_AT = 10L
        const val BASELINE_ACTIVATED_AT = 20L
        const val REFRESH_STARTED_AT = 100L
        const val REFRESH_ACTIVATED_AT = 120L
        const val BASELINE_STALE_BEFORE = 50L
        const val REPLACEMENT_STARTED_AT = 300L
        const val REPLACEMENT_STALE_BEFORE = 200L
        const val RUN_BASELINE = "c03-baseline-run"
        const val RUN_REFRESH = "c03-refresh-run"
        const val RUN_STALE = "c03-stale-run"
        const val RUN_REPLACEMENT = "c03-replacement-run"
        const val BATCH_SIZE = 250
        const val COMPACTION_BATCH_SIZE = 4_096
        const val MAX_COMPACTION_PASSES = 64
        const val MAX_SAFETY_ENTRY_COUNT = 10_000
        const val LOGICAL_ID_DOMAIN = "catalog-logical-v1"
        const val CONTENT_HASH_DOMAIN = "c03-production-content-v1"
        const val SEARCH_HASH_DOMAIN = "c03-production-search-v1"
        val REPEATED_REVISION_COUNTS = listOf(5, 10, 20)

        fun logicalChannelId(sourceId: String, providerKey: String): String =
            digestFrames(LOGICAL_ID_DOMAIN, listOf(sourceId, providerKey))

        fun physicalId(kind: String, revision: Long, ordinal: Long): String =
            digestFrames("c03-physical-v1", listOf(kind, SOURCE_ID, revision.toString(), ordinal.toString()))

        fun contentHash(
            logicalChannelId: String,
            providerKey: String,
            rawName: String,
            tvgId: String,
            tvgName: String,
            logoUrl: String,
            groupTitle: String,
            channelNumber: String,
            catchupMode: String,
            catchupSource: String,
            catchupDays: Int,
            catchupCorrection: String,
            canonicalChannelId: String,
            locator: String,
            userAgent: String,
            referrer: String,
        ): String = digestFrames(
            CONTENT_HASH_DOMAIN,
            listOf(
                logicalChannelId,
                providerKey,
                rawName,
                tvgId,
                tvgName,
                logoUrl,
                groupTitle,
                channelNumber,
                catchupMode,
                catchupSource,
                catchupDays.toString(),
                catchupCorrection,
                canonicalChannelId,
                locator,
                userAgent,
                referrer,
            ),
        )

        fun searchHash(
            logicalChannelId: String,
            rawName: String,
            groupTitle: String,
            channelNumber: String,
        ): String = digestFrames(
            SEARCH_HASH_DOMAIN,
            listOf(logicalChannelId, rawName, groupTitle, channelNumber),
        )

        fun digestFrames(domain: String, values: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.updateFrame(domain)
            values.forEach { value -> digest.updateFrame(value) }
            return digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
        }

        fun MessageDigest.updateFrame(value: String) {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            update((bytes.size ushr 24).toByte())
            update((bytes.size ushr 16).toByte())
            update((bytes.size ushr 8).toByte())
            update(bytes.size.toByte())
            update(bytes)
        }
    }
}
