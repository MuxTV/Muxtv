package app.muxtv.database.measurement

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.Build
import android.os.SystemClock
import android.util.JsonWriter
import androidx.room3.Room
import androidx.room3.RoomDatabase
import app.muxtv.benchmark.competitive.CompetitiveReport
import app.muxtv.database.MuxTvDatabase
import app.muxtv.database.RoomSourceRevisionStore
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.StagedCatalogEntry
import java.io.File
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class RefreshDeltaDatabaseVariant {
    A_CURRENT_MUXTV,
    B_IMMUTABLE_COW,
}

internal data class RefreshDeltaDatabaseMeasurementSpec(
    val sourceCommit: String,
    val warmupIterations: Int = 1,
    val measuredIterations: Int = 5,
    val entryCount: Int = DEFAULT_ENTRY_COUNT,
) {
    init {
        require(sourceCommit.matches(SHA1_PATTERN)) { "C03 source commit must be exact lowercase 40-hex." }
        require(warmupIterations in 0..5)
        require(measuredIterations in 1..10)
        require(entryCount == DEFAULT_ENTRY_COUNT) { "C03 regular Room measurement is fixed at 10k." }
    }

    companion object {
        const val DEFAULT_ENTRY_COUNT = 10_000
    }
}

internal data class RefreshDeltaDatabaseEnvironment(
    val manufacturer: String,
    val model: String,
    val apiLevel: Int,
    val availableProcessors: Int,
)

internal data class RefreshDeltaDatabaseFileState(
    val databaseBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
) {
    init {
        require(databaseBytes >= 0L)
        require(walBytes >= 0L)
        require(shmBytes >= 0L)
    }

    val totalBytes: Long get() = databaseBytes + walBytes + shmBytes
}

internal data class RefreshDeltaDatabaseSample(
    val iteration: Int,
    val refreshWallNanos: Long,
    val beginRevisionNanos: Long,
    val stageTotalNanos: Long,
    val maxStageTransactionNanos: Long,
    val activationTransactionNanos: Long,
    val observedRowMutations: Long,
    val before: RefreshDeltaDatabaseFileState,
    val afterStage: RefreshDeltaDatabaseFileState,
    val afterActivation: RefreshDeltaDatabaseFileState,
    val afterCheckpoint: RefreshDeltaDatabaseFileState,
) {
    init {
        require(iteration >= 1)
        require(refreshWallNanos > 0L)
        require(beginRevisionNanos > 0L)
        require(stageTotalNanos > 0L)
        require(maxStageTransactionNanos > 0L)
        require(activationTransactionNanos > 0L)
        require(observedRowMutations >= 0L)
    }

    val walPeakBytes: Long get() = max(afterStage.walBytes, afterActivation.walBytes)
    val checkpointDatabaseGrowthBytes: Long get() =
        (afterCheckpoint.databaseBytes - before.databaseBytes).coerceAtLeast(0L)
    val checkpointDiskGrowthBytes: Long get() =
        (afterCheckpoint.totalBytes - before.totalBytes).coerceAtLeast(0L)
}

internal data class RefreshDeltaDatabaseVariantReport(
    val variant: RefreshDeltaDatabaseVariant,
    val correctnessDigestSha256: String,
    val correctnessCount: Int,
    val previousGoodCount: Int,
    val actualRowMutations: Long,
    val rowMutationBreakdown: Map<String, Long>,
    val samples: List<RefreshDeltaDatabaseSample>,
) {
    init {
        require(correctnessDigestSha256.matches(SHA256_PATTERN))
        require(correctnessCount >= 0)
        require(previousGoodCount >= 0)
        require(actualRowMutations > 0L)
        require(rowMutationBreakdown.values.all { it >= 0L })
        require(samples.isNotEmpty())
    }
}

internal data class RefreshDeltaDatabaseScenarioReport(
    val scenarioId: String,
    val corpusContentSha256: String,
    val expectedActiveDigestSha256: String,
    val expectedActiveCount: Int,
    val variants: List<RefreshDeltaDatabaseVariantReport>,
) {
    init {
        require(scenarioId.matches(TOKEN_PATTERN))
        require(corpusContentSha256.matches(SHA256_PATTERN))
        require(expectedActiveDigestSha256.matches(SHA256_PATTERN))
        require(expectedActiveCount > 0)
        require(variants.map(RefreshDeltaDatabaseVariantReport::variant) == RefreshDeltaDatabaseVariant.entries)
    }
}

internal data class RefreshDeltaDatabaseMeasurementReport(
    val schemaVersion: Int,
    val methodVersion: String,
    val thresholdApplied: Boolean,
    val sourceCommit: String,
    val entryCount: Int,
    val batchSize: Int,
    val fixtureManifestSha256: String,
    val environment: RefreshDeltaDatabaseEnvironment,
    val scenarios: List<RefreshDeltaDatabaseScenarioReport>,
    val redactionPassed: Boolean,
    val limitations: List<String>,
)

internal class RefreshDeltaDatabaseMeasurementRunner(
    context: Context,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val applicationContext = context.applicationContext
    private val databaseSequence = AtomicInteger()

    suspend fun run(spec: RefreshDeltaDatabaseMeasurementSpec): RefreshDeltaDatabaseMeasurementReport =
        withContext(Dispatchers.IO) {
            val baseline = RefreshDeltaDatabaseFixture.baseline(spec.entryCount)
            val scenarioInputs = RefreshDeltaDatabaseScenario.entries.associateWith { scenario ->
                scenario.apply(baseline)
            }
            val scenarioReports = scenarioInputs.map { (scenario, incoming) ->
                val expectedDigest = RefreshDeltaDatabaseFixture.activeDigest(incoming)
                val expectedCount = incoming.size
                val variantReports = RefreshDeltaDatabaseVariant.entries.map { variant ->
                    repeat(spec.warmupIterations) { warmupIndex ->
                        measureOnce(
                            variant = variant,
                            scenario = scenario,
                            baseline = baseline,
                            incoming = incoming,
                            iteration = -(warmupIndex + 1),
                        )
                    }
                    val samples = List(spec.measuredIterations) { index ->
                        measureOnce(
                            variant = variant,
                            scenario = scenario,
                            baseline = baseline,
                            incoming = incoming,
                            iteration = index + 1,
                        )
                    }
                    val correctness = samples.map { it.correctness }.distinct()
                    check(correctness.size == 1) { "C03 file-backed correctness was not deterministic." }
                    val previousGoodCounts = samples.map { it.previousGoodCount }.distinct()
                    check(previousGoodCounts.size == 1) { "C03 previous-good retention was not deterministic." }
                    val rowEvidence = when (variant) {
                        RefreshDeltaDatabaseVariant.A_CURRENT_MUXTV -> auditRoomMutations(
                            scenario = scenario,
                            baseline = baseline,
                            incoming = incoming,
                        )
                        RefreshDeltaDatabaseVariant.B_IMMUTABLE_COW -> {
                            val counts = samples.map { it.sample.observedRowMutations }.distinct()
                            check(counts.size == 1) { "C03 candidate row mutations were not deterministic." }
                            mapOf("sqlite.total_changes" to counts.single())
                        }
                    }
                    val correctnessSnapshot = correctness.single()
                    check(correctnessSnapshot.digestSha256 == expectedDigest)
                    check(correctnessSnapshot.count == expectedCount)
                    check(previousGoodCounts.single() == spec.entryCount)
                    val actualRowMutations = rowEvidence.values.sum()
                    check(actualRowMutations > 0L)
                    RefreshDeltaDatabaseVariantReport(
                        variant = variant,
                        correctnessDigestSha256 = correctnessSnapshot.digestSha256,
                        correctnessCount = correctnessSnapshot.count,
                        previousGoodCount = previousGoodCounts.single(),
                        actualRowMutations = actualRowMutations,
                        rowMutationBreakdown = rowEvidence.toSortedMap(),
                        samples = samples.map { it.sample },
                    )
                }
                RefreshDeltaDatabaseScenarioReport(
                    scenarioId = scenario.id,
                    corpusContentSha256 = RefreshDeltaDatabaseFixture.corpusDigest(incoming),
                    expectedActiveDigestSha256 = expectedDigest,
                    expectedActiveCount = expectedCount,
                    variants = variantReports,
                )
            }

            RefreshDeltaDatabaseMeasurementReport(
                schemaVersion = 1,
                methodVersion = METHOD_VERSION,
                thresholdApplied = false,
                sourceCommit = spec.sourceCommit,
                entryCount = spec.entryCount,
                batchSize = BATCH_SIZE,
                fixtureManifestSha256 = RefreshDeltaDatabaseFixture.manifestDigest(spec.entryCount),
                environment = captureEnvironment(),
                scenarios = scenarioReports,
                redactionPassed = true,
                limitations = listOf(
                    "A row mutations are counted in a separate untimed audit-trigger pass so counters cannot inflate timed WAL evidence.",
                    "B is a benchmark-only immutable payload plus revision-membership schema; it is not the production Room schema or read path.",
                    "WAL bytes are physical file growth proxies; catalog-table row mutations do not count SQLite index or FTS page writes.",
                ),
            )
        }

    private suspend fun measureOnce(
        variant: RefreshDeltaDatabaseVariant,
        scenario: RefreshDeltaDatabaseScenario,
        baseline: List<RefreshDeltaDatabaseItem>,
        incoming: List<RefreshDeltaDatabaseItem>,
        iteration: Int,
    ): MeasuredRefresh = when (variant) {
        RefreshDeltaDatabaseVariant.A_CURRENT_MUXTV -> measureRoomOnce(scenario, baseline, incoming, iteration)
        RefreshDeltaDatabaseVariant.B_IMMUTABLE_COW -> measureCandidateOnce(scenario, baseline, incoming, iteration)
    }

    private suspend fun measureRoomOnce(
        scenario: RefreshDeltaDatabaseScenario,
        baseline: List<RefreshDeltaDatabaseItem>,
        incoming: List<RefreshDeltaDatabaseItem>,
        iteration: Int,
    ): MeasuredRefresh {
        val name = nextDatabaseName("room", scenario.id, iteration)
        prepareRoomBaseline(name, baseline)
        val database = openRoom(name)
        val store = RoomSourceRevisionStore(database.sourceRevisionDao())
        val before = fileState(name)
        val preparedBatches = incoming.toRoomBatches(revisionNumber = REFRESH_REVISION)
        val wallStarted = nanoTime()
        val beginStarted = nanoTime()
        store.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
        val beginNanos = elapsed(beginStarted)
        var stageTotalNanos = 0L
        var maxStageNanos = 0L
        preparedBatches.forEach { batch ->
            val started = nanoTime()
            store.stageBatch(SOURCE_ID, REFRESH_REVISION, batch)
            val duration = elapsed(started)
            stageTotalNanos += duration
            maxStageNanos = max(maxStageNanos, duration)
        }
        val afterStage = fileState(name)
        val activationStarted = nanoTime()
        val activation = store.activate(
            sourceId = SOURCE_ID,
            revisionNumber = REFRESH_REVISION,
            activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
            statistics = SourceRevisionStatistics(
                parsedEntries = incoming.size,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        check(activation is SourceRevisionActivationResult.Activated)
        val activationNanos = elapsed(activationStarted)
        val refreshWallNanos = elapsed(wallStarted)
        val afterActivation = fileState(name)
        database.close()
        checkpoint(name)
        val afterCheckpoint = fileState(name)
        val correctness = readRoomCorrectness(name)
        val previousGoodCount = scalarLong(
            name,
            "SELECT COUNT(*) FROM provider_channels WHERE sourceId = ? AND revisionNumber = ?",
            arrayOf(SOURCE_ID, BASELINE_REVISION.toString()),
        ).toInt()
        cleanup(name)
        return MeasuredRefresh(
            sample = RefreshDeltaDatabaseSample(
                iteration = iteration.coerceAtLeast(1),
                refreshWallNanos = refreshWallNanos,
                beginRevisionNanos = beginNanos,
                stageTotalNanos = stageTotalNanos.coerceAtLeast(1L),
                maxStageTransactionNanos = maxStageNanos.coerceAtLeast(1L),
                activationTransactionNanos = activationNanos,
                observedRowMutations = 0L,
                before = before,
                afterStage = afterStage,
                afterActivation = afterActivation,
                afterCheckpoint = afterCheckpoint,
            ),
            correctness = correctness,
            previousGoodCount = previousGoodCount,
        )
    }

    private fun measureCandidateOnce(
        scenario: RefreshDeltaDatabaseScenario,
        baseline: List<RefreshDeltaDatabaseItem>,
        incoming: List<RefreshDeltaDatabaseItem>,
        iteration: Int,
    ): MeasuredRefresh {
        val name = nextDatabaseName("cow", scenario.id, iteration)
        cleanup(name)
        val databaseFile = applicationContext.getDatabasePath(name)
        databaseFile.parentFile?.mkdirs()
        val database = SQLiteDatabase.openOrCreateDatabase(databaseFile, null)
        check(database.enableWriteAheadLogging() || database.isWriteAheadLoggingEnabled)
        database.execSQL("PRAGMA foreign_keys=ON")
        createCandidateSchema(database)
        prepareCandidateBaseline(database, baseline)
        checkpoint(database)
        val before = fileState(name)
        val beforeChanges = totalChanges(database)
        val wallStarted = nanoTime()
        val beginStarted = nanoTime()
        candidateTransaction(database) {
            database.execSQL(
                "INSERT INTO c03_b_revisions(source_id, revision_number, status) VALUES(?, ?, 'STAGING')",
                arrayOf<Any?>(SOURCE_ID, REFRESH_REVISION),
            )
        }
        val beginNanos = elapsed(beginStarted)
        val statements = CandidateStatements(database)
        var stageTotalNanos = 0L
        var maxStageNanos = 0L
        try {
            incoming.chunked(BATCH_SIZE).forEach { batch ->
                val started = nanoTime()
                candidateTransaction(database) {
                    batch.forEach { item -> statements.stage(item, REFRESH_REVISION) }
                }
                val duration = elapsed(started)
                stageTotalNanos += duration
                maxStageNanos = max(maxStageNanos, duration)
            }
        } finally {
            statements.close()
        }
        val afterStage = fileState(name)
        val activationStarted = nanoTime()
        candidateTransaction(database) {
            database.execSQL(
                "UPDATE c03_b_revisions SET status='RETAINED' WHERE source_id=? AND status='ACTIVE'",
                arrayOf(SOURCE_ID),
            )
            database.execSQL(
                "UPDATE c03_b_revisions SET status='ACTIVE' WHERE source_id=? AND revision_number=? AND status='STAGING'",
                arrayOf<Any?>(SOURCE_ID, REFRESH_REVISION),
            )
            database.execSQL(
                "UPDATE c03_b_sources SET retained_revision=active_revision, active_revision=? WHERE id=?",
                arrayOf<Any?>(REFRESH_REVISION, SOURCE_ID),
            )
        }
        val activationNanos = elapsed(activationStarted)
        val refreshWallNanos = elapsed(wallStarted)
        val afterActivation = fileState(name)
        val rowMutations = totalChanges(database) - beforeChanges
        val correctness = readCandidateCorrectness(database)
        val previousGoodCount = scalarLong(
            database,
            "SELECT COUNT(*) FROM c03_b_revision_entries WHERE source_id=? AND revision_number=?",
            arrayOf(SOURCE_ID, BASELINE_REVISION.toString()),
        ).toInt()
        checkpoint(database)
        val afterCheckpoint = fileState(name)
        database.close()
        cleanup(name)
        return MeasuredRefresh(
            sample = RefreshDeltaDatabaseSample(
                iteration = iteration.coerceAtLeast(1),
                refreshWallNanos = refreshWallNanos,
                beginRevisionNanos = beginNanos,
                stageTotalNanos = stageTotalNanos.coerceAtLeast(1L),
                maxStageTransactionNanos = maxStageNanos.coerceAtLeast(1L),
                activationTransactionNanos = activationNanos,
                observedRowMutations = rowMutations,
                before = before,
                afterStage = afterStage,
                afterActivation = afterActivation,
                afterCheckpoint = afterCheckpoint,
            ),
            correctness = correctness,
            previousGoodCount = previousGoodCount,
        )
    }

    private suspend fun auditRoomMutations(
        scenario: RefreshDeltaDatabaseScenario,
        baseline: List<RefreshDeltaDatabaseItem>,
        incoming: List<RefreshDeltaDatabaseItem>,
    ): Map<String, Long> {
        val name = nextDatabaseName("audit", scenario.id, 0)
        prepareRoomBaseline(name, baseline)
        installAuditTriggers(name)
        checkpoint(name)
        val database = openRoom(name)
        val store = RoomSourceRevisionStore(database.sourceRevisionDao())
        store.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
        incoming.toRoomBatches(REFRESH_REVISION).forEach { batch ->
            store.stageBatch(SOURCE_ID, REFRESH_REVISION, batch)
        }
        check(
            store.activate(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
                statistics = SourceRevisionStatistics(incoming.size, 0, 0),
            ) is SourceRevisionActivationResult.Activated,
        )
        database.close()
        val raw = openRaw(name)
        val counts = linkedMapOf<String, Long>()
        raw.rawQuery(
            "SELECT table_name, operation, mutation_count FROM c03_audit_counts ORDER BY table_name, operation",
            emptyArray(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                counts["${cursor.getString(0)}.${cursor.getString(1)}"] = cursor.getLong(2)
            }
        }
        raw.close()
        cleanup(name)
        return counts.filterValues { it > 0L }
    }

    private suspend fun prepareRoomBaseline(name: String, baseline: List<RefreshDeltaDatabaseItem>) {
        cleanup(name)
        val database = openRoom(name)
        val store = RoomSourceRevisionStore(database.sourceRevisionDao())
        store.upsertSource(SourceDefinition(SOURCE_ID, "C03 measurement source"))
        store.beginRevision(SOURCE_ID, BASELINE_REVISION, BASELINE_STARTED_AT)
        baseline.toRoomBatches(BASELINE_REVISION).forEach { batch ->
            store.stageBatch(SOURCE_ID, BASELINE_REVISION, batch)
        }
        check(
            store.activate(
                sourceId = SOURCE_ID,
                revisionNumber = BASELINE_REVISION,
                activatedAtEpochMillis = BASELINE_ACTIVATED_AT,
                statistics = SourceRevisionStatistics(baseline.size, 0, 0),
            ) is SourceRevisionActivationResult.Activated,
        )
        database.close()
        checkpoint(name)
    }

    private fun prepareCandidateBaseline(
        database: SQLiteDatabase,
        baseline: List<RefreshDeltaDatabaseItem>,
    ) {
        database.execSQL(
            "INSERT INTO c03_b_sources(id, active_revision, retained_revision) VALUES(?, 0, 0)",
            arrayOf(SOURCE_ID),
        )
        candidateTransaction(database) {
            database.execSQL(
                "INSERT INTO c03_b_revisions(source_id, revision_number, status) VALUES(?, ?, 'STAGING')",
                arrayOf<Any?>(SOURCE_ID, BASELINE_REVISION),
            )
        }
        val statements = CandidateStatements(database)
        try {
            baseline.chunked(BATCH_SIZE).forEach { batch ->
                candidateTransaction(database) {
                    batch.forEach { item -> statements.stage(item, BASELINE_REVISION) }
                }
            }
        } finally {
            statements.close()
        }
        candidateTransaction(database) {
            database.execSQL(
                "UPDATE c03_b_revisions SET status='ACTIVE' WHERE source_id=? AND revision_number=?",
                arrayOf<Any?>(SOURCE_ID, BASELINE_REVISION),
            )
            database.execSQL(
                "UPDATE c03_b_sources SET active_revision=? WHERE id=?",
                arrayOf<Any?>(BASELINE_REVISION, SOURCE_ID),
            )
        }
    }

    private fun createCandidateSchema(database: SQLiteDatabase) {
        database.execSQL(
            """
            CREATE TABLE c03_b_sources(
                id TEXT PRIMARY KEY NOT NULL,
                active_revision INTEGER NOT NULL,
                retained_revision INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE c03_b_revisions(
                source_id TEXT NOT NULL,
                revision_number INTEGER NOT NULL,
                status TEXT NOT NULL,
                PRIMARY KEY(source_id, revision_number)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE c03_b_canonical_channels(
                id TEXT PRIMARY KEY NOT NULL,
                display_name TEXT NOT NULL
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE c03_b_payloads(
                payload_id TEXT PRIMARY KEY NOT NULL,
                source_id TEXT NOT NULL,
                stable_key TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                canonical_id TEXT NOT NULL,
                raw_name TEXT NOT NULL,
                tvg_id TEXT,
                tvg_name TEXT,
                logo_url TEXT,
                group_title TEXT,
                channel_number TEXT,
                locator TEXT NOT NULL,
                user_agent TEXT,
                referrer TEXT,
                FOREIGN KEY(canonical_id) REFERENCES c03_b_canonical_channels(id),
                UNIQUE(source_id, stable_key, content_hash)
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE c03_b_search_payloads(
                payload_id TEXT PRIMARY KEY NOT NULL,
                normalized_name TEXT NOT NULL,
                normalized_group TEXT NOT NULL,
                FOREIGN KEY(payload_id) REFERENCES c03_b_payloads(payload_id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        database.execSQL(
            """
            CREATE TABLE c03_b_revision_entries(
                source_id TEXT NOT NULL,
                revision_number INTEGER NOT NULL,
                stable_key TEXT NOT NULL,
                payload_id TEXT NOT NULL,
                sort_order INTEGER NOT NULL,
                PRIMARY KEY(source_id, revision_number, stable_key),
                FOREIGN KEY(payload_id) REFERENCES c03_b_payloads(payload_id)
            )
            """.trimIndent(),
        )
        database.execSQL("CREATE INDEX c03_b_payload_lookup ON c03_b_payloads(source_id, stable_key, content_hash)")
        database.execSQL("CREATE INDEX c03_b_revision_lookup ON c03_b_revision_entries(source_id, revision_number)")
        database.execSQL("CREATE INDEX c03_b_revision_payload ON c03_b_revision_entries(payload_id)")
        database.execSQL("CREATE INDEX c03_b_search_name ON c03_b_search_payloads(normalized_name)")
        database.execSQL("CREATE INDEX c03_b_search_group ON c03_b_search_payloads(normalized_group)")
    }

    private fun installAuditTriggers(name: String) {
        val database = openRaw(name)
        database.enableWriteAheadLogging()
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS c03_audit_counts(
                table_name TEXT NOT NULL,
                operation TEXT NOT NULL,
                mutation_count INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(table_name, operation)
            )
            """.trimIndent(),
        )
        AUDITED_TABLES.forEach { table ->
            AUDITED_OPERATIONS.forEach { operation ->
                database.execSQL(
                    "INSERT OR REPLACE INTO c03_audit_counts(table_name, operation, mutation_count) VALUES(?, ?, 0)",
                    arrayOf(table, operation),
                )
                val sqlOperation = operation.uppercase(Locale.ROOT)
                database.execSQL(
                    """
                    CREATE TRIGGER c03_audit_${table}_${operation}
                    AFTER $sqlOperation ON $table
                    BEGIN
                        UPDATE c03_audit_counts
                        SET mutation_count = mutation_count + 1
                        WHERE table_name = '$table' AND operation = '$operation';
                    END
                    """.trimIndent(),
                )
            }
        }
        checkpoint(database)
        database.close()
    }

    private fun readRoomCorrectness(name: String): CorrectnessSnapshot {
        val database = openRaw(name, readOnly = true)
        val rows = ArrayList<Pair<String, String>>()
        database.rawQuery(
            """
            SELECT p.providerKey, p.rawName, p.tvgId, p.tvgName, p.logoUrl, p.groupTitle,
                   p.channelNumber, v.locator, v.userAgent, v.referrer
            FROM provider_channels AS p
            INNER JOIN stream_variants AS v ON v.providerChannelId = p.id
            INNER JOIN sources AS src ON src.id = p.sourceId
            WHERE p.sourceId = ? AND p.revisionNumber = src.activeRevision
            ORDER BY p.providerKey COLLATE BINARY ASC
            """.trimIndent(),
            arrayOf(SOURCE_ID),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val stableKey = cursor.getString(0)
                val contentHash = RefreshDeltaDatabaseFixture.contentDigest(
                    stableKey = stableKey,
                    rawName = cursor.getString(1),
                    tvgId = cursor.nullableString(2),
                    tvgName = cursor.nullableString(3),
                    logoUrl = cursor.nullableString(4),
                    groupTitle = cursor.nullableString(5),
                    channelNumber = cursor.nullableString(6),
                    locator = cursor.getString(7),
                    userAgent = cursor.nullableString(8),
                    referrer = cursor.nullableString(9),
                )
                rows += stableKey to contentHash
            }
        }
        database.close()
        return CorrectnessSnapshot(rows.size, RefreshDeltaDatabaseFixture.digestPairs(rows))
    }

    private fun readCandidateCorrectness(database: SQLiteDatabase): CorrectnessSnapshot {
        val rows = ArrayList<Pair<String, String>>()
        database.rawQuery(
            """
            SELECT e.stable_key, p.content_hash
            FROM c03_b_revision_entries AS e
            INNER JOIN c03_b_payloads AS p ON p.payload_id = e.payload_id
            INNER JOIN c03_b_sources AS src ON src.id = e.source_id
            WHERE e.source_id = ? AND e.revision_number = src.active_revision
            ORDER BY e.stable_key COLLATE BINARY ASC
            """.trimIndent(),
            arrayOf(SOURCE_ID),
        ).use { cursor ->
            while (cursor.moveToNext()) rows += cursor.getString(0) to cursor.getString(1)
        }
        return CorrectnessSnapshot(rows.size, RefreshDeltaDatabaseFixture.digestPairs(rows))
    }

    private fun List<RefreshDeltaDatabaseItem>.toRoomBatches(
        revisionNumber: Long,
    ): List<List<StagedCatalogEntry>> =
        mapIndexed { ordinal, item -> item.toRoomEntry(revisionNumber, ordinal) }
            .chunked(BATCH_SIZE)

    private fun RefreshDeltaDatabaseItem.toRoomEntry(
        revisionNumber: Long,
        ordinal: Int,
    ): StagedCatalogEntry = StagedCatalogEntry(
        providerChannelId = sha256("provider|$SOURCE_ID|$revisionNumber|$ordinal"),
        providerKey = stableKey,
        rawName = rawName,
        canonicalChannelId = canonicalId,
        canonicalDisplayName = tvgName ?: rawName,
        streamVariantId = sha256("stream|$SOURCE_ID|$revisionNumber|$ordinal"),
        locator = locator,
        tvgId = tvgId,
        tvgName = tvgName,
        logoUrl = logoUrl,
        groupTitle = groupTitle,
        channelNumber = channelNumber,
        userAgent = userAgent,
        referrer = referrer,
    )

    private fun openRoom(name: String): MuxTvDatabase =
        Room.databaseBuilder(applicationContext, MuxTvDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()

    private fun openRaw(name: String, readOnly: Boolean = false): SQLiteDatabase {
        val flags = if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE
        return SQLiteDatabase.openDatabase(applicationContext.getDatabasePath(name).absolutePath, null, flags)
    }

    private fun checkpoint(name: String) {
        val database = openRaw(name)
        checkpoint(database)
        database.close()
    }

    private fun checkpoint(database: SQLiteDatabase) {
        database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) {
                check(cursor.getInt(0) == 0) { "C03 WAL checkpoint remained busy." }
            }
        }
    }

    private fun fileState(name: String): RefreshDeltaDatabaseFileState {
        val file = applicationContext.getDatabasePath(name)
        return RefreshDeltaDatabaseFileState(
            databaseBytes = file.safeLength(),
            walBytes = File(file.path + "-wal").safeLength(),
            shmBytes = File(file.path + "-shm").safeLength(),
        )
    }

    private fun File.safeLength(): Long = if (isFile) length().coerceAtLeast(0L) else 0L

    private fun cleanup(name: String) {
        applicationContext.deleteDatabase(name)
        val file = applicationContext.getDatabasePath(name)
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }

    private fun nextDatabaseName(kind: String, scenario: String, iteration: Int): String =
        "c03-$kind-$scenario-${iteration.toString().replace('-', 'w')}-${databaseSequence.incrementAndGet()}.db"

    private fun elapsed(startedAt: Long): Long = (nanoTime() - startedAt).coerceAtLeast(1L)

    private fun captureEnvironment(): RefreshDeltaDatabaseEnvironment = RefreshDeltaDatabaseEnvironment(
        manufacturer = Build.MANUFACTURER.safeEnvironmentValue(),
        model = Build.MODEL.safeEnvironmentValue(),
        apiLevel = Build.VERSION.SDK_INT,
        availableProcessors = Runtime.getRuntime().availableProcessors(),
    )

    private fun String.safeEnvironmentValue(): String =
        trim().replace(CONTROL_CHARACTERS, " ").take(64).ifBlank { "unknown" }

    private fun scalarLong(name: String, sql: String, args: Array<String>): Long {
        val database = openRaw(name, readOnly = true)
        val value = scalarLong(database, sql, args)
        database.close()
        return value
    }

    private fun scalarLong(database: SQLiteDatabase, sql: String, args: Array<String>): Long =
        database.rawQuery(sql, args).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun totalChanges(database: SQLiteDatabase): Long =
        scalarLong(database, "SELECT total_changes()", emptyArray())

    private inline fun candidateTransaction(database: SQLiteDatabase, block: () -> Unit) {
        database.beginTransactionNonExclusive()
        try {
            block()
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    private data class MeasuredRefresh(
        val sample: RefreshDeltaDatabaseSample,
        val correctness: CorrectnessSnapshot,
        val previousGoodCount: Int,
    )

    private data class CorrectnessSnapshot(
        val count: Int,
        val digestSha256: String,
    )

    private inner class CandidateStatements(database: SQLiteDatabase) : AutoCloseable {
        private val canonical = database.compileStatement(
            "INSERT OR IGNORE INTO c03_b_canonical_channels(id, display_name) VALUES(?, ?)",
        )
        private val payload = database.compileStatement(
            """
            INSERT OR IGNORE INTO c03_b_payloads(
                payload_id, source_id, stable_key, content_hash, canonical_id, raw_name,
                tvg_id, tvg_name, logo_url, group_title, channel_number, locator, user_agent, referrer
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        )
        private val search = database.compileStatement(
            "INSERT OR IGNORE INTO c03_b_search_payloads(payload_id, normalized_name, normalized_group) VALUES(?, ?, ?)",
        )
        private val membership = database.compileStatement(
            """
            INSERT INTO c03_b_revision_entries(source_id, revision_number, stable_key, payload_id, sort_order)
            VALUES(?, ?, ?, ?, ?)
            """.trimIndent(),
        )

        fun stage(item: RefreshDeltaDatabaseItem, revisionNumber: Long) {
            canonical.clearBindings()
            canonical.bindString(1, item.canonicalId)
            canonical.bindString(2, item.tvgName ?: item.rawName)
            canonical.executeInsert()

            val payloadId = item.payloadId()
            payload.clearBindings()
            payload.bindString(1, payloadId)
            payload.bindString(2, SOURCE_ID)
            payload.bindString(3, item.stableKey)
            payload.bindString(4, item.contentHash)
            payload.bindString(5, item.canonicalId)
            payload.bindString(6, item.rawName)
            payload.bindNullableString(7, item.tvgId)
            payload.bindNullableString(8, item.tvgName)
            payload.bindNullableString(9, item.logoUrl)
            payload.bindNullableString(10, item.groupTitle)
            payload.bindNullableString(11, item.channelNumber)
            payload.bindString(12, item.locator)
            payload.bindNullableString(13, item.userAgent)
            payload.bindNullableString(14, item.referrer)
            payload.executeInsert()

            search.clearBindings()
            search.bindString(1, payloadId)
            search.bindString(2, item.rawName.lowercase(Locale.ROOT))
            search.bindString(3, item.groupTitle.orEmpty().lowercase(Locale.ROOT))
            search.executeInsert()

            membership.clearBindings()
            membership.bindString(1, SOURCE_ID)
            membership.bindLong(2, revisionNumber)
            membership.bindString(3, item.stableKey)
            membership.bindString(4, payloadId)
            membership.bindLong(5, item.order.toLong())
            membership.executeInsert()
        }

        override fun close() {
            canonical.close()
            payload.close()
            search.close()
            membership.close()
        }
    }

    private fun SQLiteStatement.bindNullableString(index: Int, value: String?) {
        if (value == null) bindNull(index) else bindString(index, value)
    }

    private fun Cursor.nullableString(index: Int): String? = if (isNull(index)) null else getString(index)

    private companion object {
        const val METHOD_VERSION = "c03-room-delta-v1"
        const val SOURCE_ID = "c03-source"
        const val BASELINE_REVISION = 1L
        const val REFRESH_REVISION = 2L
        const val BASELINE_STARTED_AT = 1_000L
        const val BASELINE_ACTIVATED_AT = 2_000L
        const val REFRESH_STARTED_AT = 3_000L
        const val REFRESH_ACTIVATED_AT = 4_000L
        const val BATCH_SIZE = 250
        val AUDITED_TABLES = listOf(
            "sources",
            "source_revisions",
            "canonical_channels",
            "provider_channels",
            "stream_variants",
            "search_documents",
        )
        val AUDITED_OPERATIONS = listOf("insert", "update", "delete")
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f]")
    }
}

private enum class RefreshDeltaDatabaseScenario(val id: String) {
    DELTA_0("delta-0"),
    DELTA_1("delta-1"),
    DELTA_10("delta-10"),
    DELTA_100("delta-100"),
    REORDER("reorder"),
    REMOVE_10("remove-10");

    fun apply(previous: List<RefreshDeltaDatabaseItem>): List<RefreshDeltaDatabaseItem> = when (this) {
        DELTA_0 -> previous
        DELTA_1 -> RefreshDeltaDatabaseFixture.contentDelta(previous, 1)
        DELTA_10 -> RefreshDeltaDatabaseFixture.contentDelta(previous, 10)
        DELTA_100 -> RefreshDeltaDatabaseFixture.contentDelta(previous, 100)
        REORDER -> previous.asReversed().mapIndexed { order, item -> item.copy(order = order) }
        REMOVE_10 -> previous.dropLast(previous.size / 10)
    }
}

private data class RefreshDeltaDatabaseItem(
    val stableKey: String,
    val canonicalId: String,
    val rawName: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val locator: String,
    val userAgent: String?,
    val referrer: String?,
    val order: Int,
    val contentHash: String,
) {
    fun payloadId(): String = sha256("payload|$stableKey|$contentHash")
}

private object RefreshDeltaDatabaseFixture {
    private const val SEED = 348_003L

    fun baseline(size: Int): List<RefreshDeltaDatabaseItem> = List(size) { index ->
        createItem(
            index = index,
            order = index,
            locator = "https://fixture.invalid/live/$index?session=baseline-$SEED-$index",
        )
    }

    fun contentDelta(
        previous: List<RefreshDeltaDatabaseItem>,
        percent: Int,
    ): List<RefreshDeltaDatabaseItem> {
        require(percent in 0..100)
        val changed = (previous.size.toLong() * percent / 100L).toInt()
        return previous.mapIndexed { index, item ->
            if (index < changed) {
                item.withLocator("https://fixture.invalid/live/$index?session=refresh-$percent-$index")
            } else {
                item
            }
        }
    }

    fun activeDigest(items: List<RefreshDeltaDatabaseItem>): String =
        digestPairs(items.map { it.stableKey to it.contentHash })

    fun corpusDigest(items: List<RefreshDeltaDatabaseItem>): String {
        val digest = MessageDigest.getInstance(SHA_256)
        items.forEach { item ->
            digest.updateField(item.stableKey)
            digest.updateField(item.contentHash)
            digest.updateField(item.order.toString())
        }
        return digest.digest().toHex()
    }

    fun manifestDigest(entryCount: Int): String = sha256(
        "c03-room-v1|seed=$SEED|entryCount=$entryCount|batch=250|" +
            RefreshDeltaDatabaseScenario.entries.joinToString(",") { it.id },
    )

    fun digestPairs(rows: List<Pair<String, String>>): String {
        val digest = MessageDigest.getInstance(SHA_256)
        rows.sortedBy { it.first }.forEach { (stableKey, contentHash) ->
            digest.updateField(stableKey)
            digest.updateField(contentHash)
        }
        return digest.digest().toHex()
    }

    fun contentDigest(
        stableKey: String,
        rawName: String,
        tvgId: String?,
        tvgName: String?,
        logoUrl: String?,
        groupTitle: String?,
        channelNumber: String?,
        locator: String,
        userAgent: String?,
        referrer: String?,
    ): String {
        val digest = MessageDigest.getInstance(SHA_256)
        listOf(
            stableKey,
            rawName,
            tvgId,
            tvgName,
            logoUrl,
            groupTitle,
            channelNumber,
            locator,
            userAgent,
            referrer,
        ).forEach { digest.updateNullableField(it) }
        return digest.digest().toHex()
    }

    private fun createItem(index: Int, order: Int, locator: String): RefreshDeltaDatabaseItem {
        val suffix = index.toString().padStart(5, '0')
        val stableKey = "tvg:c03-$suffix"
        val rawName = "C03 Channel $suffix"
        val tvgId = "c03-$suffix"
        val tvgName = "C03 Channel $suffix"
        val logoUrl = "https://images.invalid/c03/$suffix.png"
        val groupTitle = "Group ${index % 32}"
        val channelNumber = (index + 1).toString()
        val userAgent = if (index % 17 == 0) "MuxTV-C03/${index % 5}" else null
        val referrer = if (index % 29 == 0) "https://portal.invalid/c03/${index % 7}" else null
        return RefreshDeltaDatabaseItem(
            stableKey = stableKey,
            canonicalId = sha256("canonical|source|c03-source|$stableKey"),
            rawName = rawName,
            tvgId = tvgId,
            tvgName = tvgName,
            logoUrl = logoUrl,
            groupTitle = groupTitle,
            channelNumber = channelNumber,
            locator = locator,
            userAgent = userAgent,
            referrer = referrer,
            order = order,
            contentHash = contentDigest(
                stableKey,
                rawName,
                tvgId,
                tvgName,
                logoUrl,
                groupTitle,
                channelNumber,
                locator,
                userAgent,
                referrer,
            ),
        )
    }

    private fun RefreshDeltaDatabaseItem.withLocator(locator: String): RefreshDeltaDatabaseItem =
        copy(
            locator = locator,
            contentHash = contentDigest(
                stableKey,
                rawName,
                tvgId,
                tvgName,
                logoUrl,
                groupTitle,
                channelNumber,
                locator,
                userAgent,
                referrer,
            ),
        )
}

internal object RefreshDeltaDatabaseMeasurementJsonWriter {
    fun write(
        report: RefreshDeltaDatabaseMeasurementReport,
        competitiveReports: List<CompetitiveReport>,
        output: OutputStream,
    ) {
        val writer = JsonWriter(OutputStreamWriter(output, StandardCharsets.UTF_8))
        writer.beginObject()
        writer.name("schemaVersion").value(report.schemaVersion.toLong())
        writer.name("methodVersion").value(report.methodVersion)
        writer.name("thresholdApplied").value(report.thresholdApplied)
        writer.name("sourceCommit").value(report.sourceCommit)
        writer.name("entryCount").value(report.entryCount.toLong())
        writer.name("batchSize").value(report.batchSize.toLong())
        writer.name("fixtureManifestSha256").value(report.fixtureManifestSha256)
        writer.name("redactionPassed").value(report.redactionPassed)
        writer.name("environment").beginObject()
        writer.name("manufacturer").value(report.environment.manufacturer)
        writer.name("model").value(report.environment.model)
        writer.name("apiLevel").value(report.environment.apiLevel.toLong())
        writer.name("availableProcessors").value(report.environment.availableProcessors.toLong())
        writer.endObject()
        writer.name("limitations").beginArray()
        report.limitations.forEach(writer::value)
        writer.endArray()
        writer.name("scenarios").beginArray()
        report.scenarios.forEach { scenario ->
            writer.beginObject()
            writer.name("scenarioId").value(scenario.scenarioId)
            writer.name("corpusContentSha256").value(scenario.corpusContentSha256)
            writer.name("expectedActiveDigestSha256").value(scenario.expectedActiveDigestSha256)
            writer.name("expectedActiveCount").value(scenario.expectedActiveCount.toLong())
            writer.name("variants").beginArray()
            scenario.variants.forEach { variant ->
                writer.beginObject()
                writer.name("variant").value(variant.variant.name)
                writer.name("correctnessDigestSha256").value(variant.correctnessDigestSha256)
                writer.name("correctnessCount").value(variant.correctnessCount.toLong())
                writer.name("previousGoodCount").value(variant.previousGoodCount.toLong())
                writer.name("actualRowMutations").value(variant.actualRowMutations)
                writer.name("rowMutationBreakdown").beginObject()
                variant.rowMutationBreakdown.toSortedMap().forEach { (key, value) ->
                    writer.name(key).value(value)
                }
                writer.endObject()
                writer.name("samples").beginArray()
                variant.samples.forEach { sample ->
                    writer.beginObject()
                    writer.name("iteration").value(sample.iteration.toLong())
                    writer.name("refreshWallNanos").value(sample.refreshWallNanos)
                    writer.name("beginRevisionNanos").value(sample.beginRevisionNanos)
                    writer.name("stageTotalNanos").value(sample.stageTotalNanos)
                    writer.name("maxStageTransactionNanos").value(sample.maxStageTransactionNanos)
                    writer.name("activationTransactionNanos").value(sample.activationTransactionNanos)
                    writer.name("observedRowMutations").value(sample.observedRowMutations)
                    writer.name("walPeakBytes").value(sample.walPeakBytes)
                    writer.name("checkpointDatabaseGrowthBytes").value(sample.checkpointDatabaseGrowthBytes)
                    writer.name("checkpointDiskGrowthBytes").value(sample.checkpointDiskGrowthBytes)
                    writer.name("before").writeFileState(sample.before)
                    writer.name("afterStage").writeFileState(sample.afterStage)
                    writer.name("afterActivation").writeFileState(sample.afterActivation)
                    writer.name("afterCheckpoint").writeFileState(sample.afterCheckpoint)
                    writer.endObject()
                }
                writer.endArray()
                writer.endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        writer.endArray()
        writer.name("competitiveReports").beginArray()
        competitiveReports.forEach { competitive ->
            writer.beginObject()
            writer.name("schemaVersion").value(competitive.schemaVersion.toLong())
            writer.name("thresholdApplied").value(competitive.thresholdApplied)
            writer.name("scenarioId").value(competitive.scenarioId)
            writer.name("seed").value(competitive.seed)
            writer.name("environmentFingerprintSha256").value(competitive.environmentFingerprintSha256)
            writer.name("baselineVariant").value(competitive.baselineVariant.name)
            writer.name("variants").beginArray()
            competitive.variantReports.forEach { variant ->
                writer.beginObject()
                writer.name("variant").value(variant.variant.name)
                writer.name("repository").value(variant.source.repository)
                writer.name("sourceSha").value(variant.source.sha)
                writer.name("metrics").beginArray()
                variant.metrics.forEach { metric ->
                    writer.beginObject()
                    writer.name("metricId").value(metric.metricId)
                    writer.name("unit").value(metric.unit)
                    writer.name("sampleCount").value(metric.sampleCount.toLong())
                    writer.name("minimum").value(metric.minimum)
                    writer.name("median").value(metric.median)
                    writer.name("p90").value(metric.p90)
                    writer.name("p95").value(metric.p95)
                    writer.name("p99").value(metric.p99)
                    writer.name("maximum").value(metric.maximum)
                    writer.name("deltaBasisPointsFromBaseline").value(metric.deltaBasisPointsFromBaseline)
                    writer.endObject()
                }
                writer.endArray()
                writer.endObject()
            }
            writer.endArray()
            writer.endObject()
        }
        writer.endArray()
        writer.endObject()
        writer.flush()
    }

    private fun JsonWriter.writeFileState(state: RefreshDeltaDatabaseFileState) {
        beginObject()
        name("databaseBytes").value(state.databaseBytes)
        name("walBytes").value(state.walBytes)
        name("shmBytes").value(state.shmBytes)
        name("totalBytes").value(state.totalBytes)
        endObject()
    }
}

private fun MessageDigest.updateField(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update((bytes.size ushr 24).toByte())
    update((bytes.size ushr 16).toByte())
    update((bytes.size ushr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

private fun MessageDigest.updateNullableField(value: String?) {
    if (value == null) {
        update(0.toByte())
    } else {
        update(1.toByte())
        updateField(value)
    }
}

private fun ByteArray.toHex(): String {
    val chars = CharArray(size * 2)
    var output = 0
    forEach { byte ->
        val value = byte.toInt() and 0xff
        chars[output++] = HEX[value ushr 4]
        chars[output++] = HEX[value and 0x0f]
    }
    return chars.concatToString()
}

private fun sha256(value: String): String =
    MessageDigest.getInstance(SHA_256)
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .toHex()

private const val SHA_256 = "SHA-256"
private val SHA1_PATTERN = Regex("[0-9a-f]{40}")
private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
private val TOKEN_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val HEX = "0123456789abcdef".toCharArray()
