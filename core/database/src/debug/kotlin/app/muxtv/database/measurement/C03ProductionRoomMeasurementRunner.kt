package app.muxtv.database.measurement

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Build
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class C03ProductionRoomMeasurementSpec(
    val sourceCommit: String,
    val warmupIterations: Int = 1,
    val measuredIterations: Int = 5,
    val entryCount: Int = DEFAULT_ENTRY_COUNT,
    val scenarios: List<C03ProductionScenario> = C03ProductionScenario.entries,
) {
    init {
        require(sourceCommit.isNotBlank())
        require(warmupIterations in 0..5)
        require(measuredIterations in 1..10)
        require(entryCount > 0)
        require(scenarios.isNotEmpty())
        require(scenarios.distinct().size == scenarios.size)
    }

    companion object {
        const val DEFAULT_ENTRY_COUNT = 10_000
    }
}

/**
 * Threshold-free file-backed A/B harness for #364.
 *
 * A is the current production Room schema. B is the debug-only production-equivalent Room
 * candidate. The harness deliberately stops short of a production migration. This first
 * executable increment measures staging/publication file growth and correctness only; active
 * Search/query-plan, repeated-revision storage, cancellation and trigger-based mutation auditing
 * are added by the following evidence increments rather than represented by synthetic numbers.
 */
internal class C03ProductionRoomMeasurementRunner(
    context: Context,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val applicationContext = context.applicationContext
    private val databaseSequence = AtomicInteger()

    suspend fun run(spec: C03ProductionRoomMeasurementSpec): C03ProductionRoomMeasurementReport =
        withContext(Dispatchers.IO) {
            val baseline = Fixture.baseline(spec.entryCount)
            val scenarios = spec.scenarios.map { scenario ->
                val incoming = scenario.applyTo(baseline)
                val expectedDigest = activeDigest(incoming)
                val expectedCount = incoming.size
                val variants = C03ProductionMeasurementVariant.entries.map { variant ->
                    repeat(spec.warmupIterations) { warmupIndex ->
                        measureOnce(
                            variant = variant,
                            scenario = scenario,
                            baseline = baseline,
                            incoming = incoming,
                            iterationLabel = "w${warmupIndex + 1}",
                        )
                    }
                    val measured = List(spec.measuredIterations) { index ->
                        measureOnce(
                            variant = variant,
                            scenario = scenario,
                            baseline = baseline,
                            incoming = incoming,
                            iterationLabel = "m${index + 1}",
                        )
                    }
                    check(measured.all { it.correctnessDigestSha256 == expectedDigest }) {
                        "C03 measured active digest disagrees with deterministic fixture."
                    }
                    check(measured.all { it.correctnessCount == expectedCount }) {
                        "C03 measured active count disagrees with deterministic fixture."
                    }
                    check(measured.map { it.logicalIdentityDigestSha256 }.distinct().size == 1) {
                        "C03 logical identity evidence was not deterministic."
                    }
                    check(measured.map { it.previousGoodDigestSha256 }.distinct().size == 1) {
                        "C03 previous-good evidence was not deterministic."
                    }
                    check(measured.map { it.writes }.distinct().size == 1) {
                        "C03 row-state write evidence was not deterministic."
                    }
                    variantMeasurement(variant, measured)
                }
                C03ProductionRoomScenarioMeasurement(
                    scenarioId = scenario.id,
                    expectedCorrectnessDigestSha256 = expectedDigest,
                    expectedCorrectnessCount = expectedCount,
                    variants = variants,
                )
            }

            C03ProductionRoomMeasurementReport(
                schemaVersion = REPORT_SCHEMA_VERSION,
                methodVersion = METHOD_VERSION,
                sourceCommit = spec.sourceCommit,
                warmupIterations = spec.warmupIterations,
                measuredIterations = spec.measuredIterations,
                entryCount = spec.entryCount,
                environment = C03ProductionRoomMeasurementEnvironment(
                    apiLevel = Build.VERSION.SDK_INT,
                    manufacturer = Build.MANUFACTURER.safeEnvironmentValue(),
                    model = Build.MODEL.safeEnvironmentValue(),
                    availableProcessors = Runtime.getRuntime().availableProcessors(),
                ),
                scenarios = scenarios,
                repeatedRevisionStorage = emptyList(),
                safety = emptyList(),
                redactionPassed = true,
                limitations = listOf(
                    "This executable increment is threshold-free harness validation; it is not adoption evidence.",
                    "Write counts are persisted row-state deltas collected outside timed sections; trigger-based physical mutation auditing is the next Task 4 increment.",
                    "Search latency and query-plan fields are intentionally zero/empty until the Task 5 active-search contract is executable; no synthetic search number is reported.",
                    "Repeated-revision storage, bounded orphan compaction and cancellation cleanup remain separate evidence increments and are not inferred from one refresh.",
                ),
            )
        }

    private suspend fun measureOnce(
        variant: C03ProductionMeasurementVariant,
        scenario: C03ProductionScenario,
        baseline: List<FixtureItem>,
        incoming: List<FixtureItem>,
        iterationLabel: String,
    ): MeasuredVariant = when (variant) {
        C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION ->
            measureProduction(scenario, baseline, incoming, iterationLabel)
        C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE ->
            measureCandidate(scenario, baseline, incoming, iterationLabel)
    }

    private suspend fun measureProduction(
        scenario: C03ProductionScenario,
        baseline: List<FixtureItem>,
        incoming: List<FixtureItem>,
        iterationLabel: String,
    ): MeasuredVariant {
        val name = nextDatabaseName("a", scenario.id, iterationLabel)
        cleanupDatabase(name)
        prepareProductionBaseline(name, baseline)
        checkpoint(name)

        val database = openProduction(name)
        val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
        val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
        return try {
            val beforeCounts = database.productionCounts()
            val before = fileState(name)
            check(
                refresh.tryAcquire(
                    sourceId = SOURCE_ID,
                    runToken = RUN_REFRESH,
                    startedAtEpochMillis = REFRESH_STARTED_AT,
                    staleBeforeEpochMillis = BASELINE_STALE_BEFORE,
                ),
            ) { "C03 production refresh owner could not be acquired." }
            revisions.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)

            val stageStarted = nanoTime()
            incoming.chunked(BATCH_SIZE).forEach { batch ->
                revisions.stageBatch(
                    SOURCE_ID,
                    REFRESH_REVISION,
                    batch.map { it.toProductionEntry(REFRESH_REVISION) },
                )
            }
            val stageNanos = elapsed(stageStarted)
            val afterStage = fileState(name)

            val publicationStarted = nanoTime()
            val activation = revisions.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_REFRESH,
                activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
                statistics = SourceRevisionStatistics(incoming.size, 0, 0),
            )
            check(activation is SourceRevisionActivationResult.Activated) {
                "C03 production guarded publication failed."
            }
            val publicationNanos = elapsed(publicationStarted)
            val afterPublication = fileState(name)
            val afterCounts = database.productionCounts()

            val rows = database.productionRows(REFRESH_REVISION)
            val previousRows = database.productionRows(BASELINE_REVISION)
            val browseStarted = nanoTime()
            val browseCount = database.productionActiveCount()
            val browseNanos = elapsed(browseStarted)
            check(browseCount == incoming.size)
            val lookupStarted = nanoTime()
            check(database.productionProviderExists(incoming.first().providerKey))
            val lookupNanos = elapsed(lookupStarted)

            MeasuredVariant(
                correctnessDigestSha256 = activeDigest(rows),
                correctnessCount = rows.size,
                logicalIdentityDigestSha256 = logicalDigest(rows),
                previousGoodDigestSha256 = activeDigest(previousRows),
                writes = C03ProductionRoomWriteCounts(
                    providerOrPayloadWrites =
                        (afterCounts.providerRows - beforeCounts.providerRows).coerceAtLeast(0) +
                            (afterCounts.streamRows - beforeCounts.streamRows).coerceAtLeast(0),
                    searchPayloadWrites = 0,
                    searchDocumentWrites =
                        (afterCounts.searchDocumentRows - beforeCounts.searchDocumentRows).coerceAtLeast(0),
                    membershipWrites = 0,
                    revisionMetadataWrites =
                        (afterCounts.revisionRows - beforeCounts.revisionRows).coerceAtLeast(0),
                    sourceMetadataWrites = 0,
                    cleanupDeletes = 0,
                ),
                sample = C03ProductionRoomTimingSample(
                    stageTotalNanos = stageNanos,
                    publicationNanos = publicationNanos,
                    cleanupNanos = 0,
                    cancellationCleanupNanos = 0,
                    browseNanos = browseNanos,
                    providerLookupNanos = lookupNanos,
                    searchNanos = 0,
                    before = before,
                    afterStage = afterStage,
                    afterPublication = afterPublication,
                    afterCleanup = afterPublication,
                ),
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun measureCandidate(
        scenario: C03ProductionScenario,
        baseline: List<FixtureItem>,
        incoming: List<FixtureItem>,
        iterationLabel: String,
    ): MeasuredVariant {
        val name = nextDatabaseName("b", scenario.id, iterationLabel)
        cleanupDatabase(name)
        prepareCandidateBaseline(name, baseline)
        checkpoint(name)

        val database = openCandidate(name)
        val dao = database.candidateDao()
        return try {
            val beforeCounts = dao.rowCounts()
            val beforeRevisionRows = database.candidateRevisionCount()
            val before = fileState(name)
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_REFRESH)
            dao.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)

            val stageStarted = nanoTime()
            incoming.chunked(BATCH_SIZE).forEach { batch ->
                dao.stageBatch(
                    SOURCE_ID,
                    REFRESH_REVISION,
                    batch.map(FixtureItem::toCandidateEntry),
                )
            }
            val stageNanos = elapsed(stageStarted)
            val afterStage = fileState(name)

            val publicationStarted = nanoTime()
            val activation = dao.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_REFRESH,
                activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
            )
            check(activation == C03ProductionCandidateActivationResult.Published) {
                "C03 candidate guarded publication failed."
            }
            val publicationNanos = elapsed(publicationStarted)
            val afterPublication = fileState(name)
            val afterCounts = dao.rowCounts()
            val afterRevisionRows = database.candidateRevisionCount()

            val rows = dao.activeRows(SOURCE_ID).map { row ->
                NormalizedRow(row.logicalChannelId, row.contentHash, row.ordinal)
            }
            val previousRows = database.candidateRows(BASELINE_REVISION)
            val browseStarted = nanoTime()
            val browsed = dao.activeRows(SOURCE_ID)
            val browseNanos = elapsed(browseStarted)
            check(browsed.size == incoming.size)
            val lookupStarted = nanoTime()
            check(database.candidateProviderExists(incoming.first().providerKey))
            val lookupNanos = elapsed(lookupStarted)

            MeasuredVariant(
                correctnessDigestSha256 = activeDigest(rows),
                correctnessCount = rows.size,
                logicalIdentityDigestSha256 = logicalDigest(rows),
                previousGoodDigestSha256 = activeDigest(previousRows),
                writes = C03ProductionRoomWriteCounts(
                    providerOrPayloadWrites =
                        (afterCounts.payloadRows - beforeCounts.payloadRows).coerceAtLeast(0).toLong(),
                    searchPayloadWrites =
                        (afterCounts.searchPayloadRows - beforeCounts.searchPayloadRows).coerceAtLeast(0).toLong(),
                    searchDocumentWrites =
                        (afterCounts.searchDocumentRows - beforeCounts.searchDocumentRows).coerceAtLeast(0).toLong(),
                    membershipWrites =
                        (afterCounts.membershipRows - beforeCounts.membershipRows).coerceAtLeast(0).toLong(),
                    revisionMetadataWrites =
                        (afterRevisionRows - beforeRevisionRows).coerceAtLeast(0).toLong(),
                    sourceMetadataWrites = 0,
                    cleanupDeletes = 0,
                ),
                sample = C03ProductionRoomTimingSample(
                    stageTotalNanos = stageNanos,
                    publicationNanos = publicationNanos,
                    cleanupNanos = 0,
                    cancellationCleanupNanos = 0,
                    browseNanos = browseNanos,
                    providerLookupNanos = lookupNanos,
                    searchNanos = 0,
                    before = before,
                    afterStage = afterStage,
                    afterPublication = afterPublication,
                    afterCleanup = afterPublication,
                ),
            )
        } finally {
            database.close()
            cleanupDatabase(name)
        }
    }

    private suspend fun prepareProductionBaseline(name: String, baseline: List<FixtureItem>) {
        val database = openProduction(name)
        try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            revisions.upsertSource(SourceDefinition(SOURCE_ID, "C03 production measurement", CREDENTIAL_REF))
            check(refresh.tryAcquire(SOURCE_ID, RUN_BASELINE, BASELINE_STARTED_AT, 0))
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

    private suspend fun MuxTvDatabase.productionCounts(): ProductionCounts = useReaderConnection { connection ->
        fun count(table: String): Long = connection.usePrepared("SELECT COUNT(*) FROM $table") { statement ->
            check(statement.step())
            statement.getLong(0)
        }
        ProductionCounts(
            providerRows = count("provider_channels"),
            streamRows = count("stream_variants"),
            searchDocumentRows = count("search_documents"),
            revisionRows = count("source_revisions"),
        )
    }

    private suspend fun MuxTvDatabase.productionActiveCount(): Int = useReaderConnection { connection ->
        connection.usePrepared(
            """
            SELECT COUNT(*)
            FROM provider_channels AS p
            INNER JOIN sources AS s ON s.id = p.sourceId AND s.activeRevision = p.revisionNumber
            WHERE p.sourceId = ?
            """.trimIndent(),
        ) { statement ->
            statement.bindText(1, SOURCE_ID)
            check(statement.step())
            statement.getLong(0).toInt()
        }
    }

    private suspend fun MuxTvDatabase.productionProviderExists(providerKey: String): Boolean =
        useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT 1
                FROM provider_channels AS p
                INNER JOIN sources AS s ON s.id = p.sourceId AND s.activeRevision = p.revisionNumber
                WHERE p.sourceId = ? AND p.providerKey = ?
                LIMIT 1
                """.trimIndent(),
            ) { statement ->
                statement.bindText(1, SOURCE_ID)
                statement.bindText(2, providerKey)
                statement.step()
            }
        }

    private suspend fun C03ProductionCandidateDatabase.candidateProviderExists(providerKey: String): Boolean =
        useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT 1
                FROM c03_production_candidate_sources AS s
                INNER JOIN c03_production_candidate_memberships AS m
                    ON m.sourceId = s.sourceId AND m.revisionNumber = s.activeRevision
                INNER JOIN c03_production_candidate_payloads AS p ON p.payloadId = m.payloadId
                WHERE s.sourceId = ? AND p.providerKey = ?
                LIMIT 1
                """.trimIndent(),
            ) { statement ->
                statement.bindText(1, SOURCE_ID)
                statement.bindText(2, providerKey)
                statement.step()
            }
        }

    private suspend fun C03ProductionCandidateDatabase.candidateRevisionCount(): Long =
        useReaderConnection { connection ->
            connection.usePrepared("SELECT COUNT(*) FROM c03_production_candidate_revisions") { statement ->
                check(statement.step())
                statement.getLong(0)
            }
        }

    private suspend fun MuxTvDatabase.productionRows(revision: Long): List<NormalizedRow> =
        useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT p.providerKey, p.rawName, p.tvgId, p.tvgName, p.logoUrl,
                       p.groupTitle, p.channelNumber, p.catchupMode, p.catchupSource,
                       p.catchupDays, p.catchupCorrection, v.canonicalChannelId,
                       v.locator, v.userAgent, v.referrer
                FROM provider_channels AS p
                INNER JOIN stream_variants AS v ON v.providerChannelId = p.id
                WHERE p.sourceId = ? AND p.revisionNumber = ?
                ORDER BY p.id COLLATE BINARY
                """.trimIndent(),
            ) { statement ->
                statement.bindText(1, SOURCE_ID)
                statement.bindLong(2, revision)
                buildList {
                    var ordinal = 0L
                    while (statement.step()) {
                        val providerKey = statement.getText(0)
                        val logical = logicalChannelId(SOURCE_ID, providerKey)
                        add(
                            NormalizedRow(
                                logicalChannelId = logical,
                                contentHash = contentHash(
                                    logicalChannelId = logical,
                                    providerKey = providerKey,
                                    rawName = statement.getText(1),
                                    tvgId = statement.getText(2),
                                    tvgName = statement.getText(3),
                                    logoUrl = statement.getText(4),
                                    groupTitle = statement.getText(5),
                                    channelNumber = statement.getText(6),
                                    catchupMode = statement.getText(7),
                                    catchupSource = statement.getText(8),
                                    catchupDays = statement.getLong(9).toInt(),
                                    catchupCorrection = statement.getText(10),
                                    canonicalChannelId = statement.getText(11),
                                    locator = statement.getText(12),
                                    userAgent = statement.getText(13),
                                    referrer = statement.getText(14),
                                ),
                                ordinal = ordinal++,
                            ),
                        )
                    }
                }
            }
        }

    private suspend fun C03ProductionCandidateDatabase.candidateRows(revision: Long): List<NormalizedRow> =
        useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT m.ordinal, m.logicalChannelId, p.contentHash
                FROM c03_production_candidate_memberships AS m
                INNER JOIN c03_production_candidate_payloads AS p ON p.payloadId = m.payloadId
                WHERE m.sourceId = ? AND m.revisionNumber = ?
                ORDER BY m.ordinal
                """.trimIndent(),
            ) { statement ->
                statement.bindText(1, SOURCE_ID)
                statement.bindLong(2, revision)
                buildList {
                    while (statement.step()) {
                        add(
                            NormalizedRow(
                                logicalChannelId = statement.getText(1),
                                contentHash = statement.getText(2),
                                ordinal = statement.getLong(0),
                            ),
                        )
                    }
                }
            }
        }

    private fun variantMeasurement(
        variant: C03ProductionMeasurementVariant,
        measured: List<MeasuredVariant>,
    ): C03ProductionRoomVariantMeasurement {
        val samples = measured.map(MeasuredVariant::sample)
        val first = measured.first()
        return C03ProductionRoomVariantMeasurement(
            variant = variant,
            correctnessDigestSha256 = first.correctnessDigestSha256,
            correctnessCount = first.correctnessCount,
            logicalIdentityDigestSha256 = first.logicalIdentityDigestSha256,
            previousGoodDigestSha256 = first.previousGoodDigestSha256,
            writes = first.writes,
            samples = samples,
            stage = distribution(samples.map(C03ProductionRoomTimingSample::stageTotalNanos)),
            publication = distribution(samples.map(C03ProductionRoomTimingSample::publicationNanos)),
            cleanup = distribution(samples.map(C03ProductionRoomTimingSample::cleanupNanos)),
            browse = distribution(samples.map(C03ProductionRoomTimingSample::browseNanos)),
            providerLookup = distribution(samples.map(C03ProductionRoomTimingSample::providerLookupNanos)),
            search = distribution(samples.map(C03ProductionRoomTimingSample::searchNanos)),
            queryPlans = emptyList(),
        )
    }

    private fun distribution(values: List<Long>): C03ProductionRoomDistribution {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        return C03ProductionRoomDistribution(
            medianNanos = percentile(sorted, 50),
            p90Nanos = percentile(sorted, 90),
            p95Nanos = percentile(sorted, 95),
            samples = values,
        )
    }

    private fun percentile(sorted: List<Long>, percentile: Int): Long {
        require(percentile in 0..100)
        val index = (((sorted.size - 1).toLong() * percentile) / 100L).toInt()
        return sorted[index]
    }

    private fun fileState(name: String): C03ProductionRoomFileState {
        val database = applicationContext.getDatabasePath(name)
        return C03ProductionRoomFileState(
            databaseBytes = database.safeLength(),
            walBytes = File(database.path + "-wal").safeLength(),
            shmBytes = File(database.path + "-shm").safeLength(),
        )
    }

    private fun checkpoint(name: String) {
        val file = applicationContext.getDatabasePath(name)
        if (!file.isFile) return
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).use { cursor ->
                if (cursor.moveToFirst()) {
                    check(cursor.getInt(0) == 0) { "C03 WAL checkpoint remained busy." }
                }
            }
        } finally {
            raw.close()
        }
    }

    private fun cleanupDatabase(name: String) {
        applicationContext.deleteDatabase(name)
        val file = applicationContext.getDatabasePath(name)
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }

    private fun nextDatabaseName(kind: String, scenario: String, iteration: String): String =
        "c03-production-$kind-$scenario-$iteration-${databaseSequence.incrementAndGet()}.db"

    private fun C03ProductionScenario.applyTo(previous: List<FixtureItem>): List<FixtureItem> = when (this) {
        C03ProductionScenario.DELTA_0 -> previous.mapIndexed { index, item -> item.copy(ordinal = index.toLong()) }
        C03ProductionScenario.DELTA_1 -> Fixture.contentDelta(previous, 1)
        C03ProductionScenario.DELTA_10 -> Fixture.contentDelta(previous, 10)
        C03ProductionScenario.DELTA_100 -> Fixture.contentDelta(previous, 100)
        C03ProductionScenario.REORDER -> previous.asReversed().mapIndexed { index, item ->
            item.copy(ordinal = index.toLong())
        }
        C03ProductionScenario.REMOVE_10 -> previous
            .dropLast((previous.size / 10).coerceAtLeast(1))
            .mapIndexed { index, item -> item.copy(ordinal = index.toLong()) }
        C03ProductionScenario.TOKEN_CHURN -> previous.mapIndexed { index, item ->
            item.copy(
                ordinal = index.toLong(),
                locator = "${item.locator}&token=rotated-$index",
            ).rehash()
        }
    }

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
                catchupDays,
                catchupCorrection,
                canonicalChannelId,
                locator,
                userAgent,
                referrer,
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

        fun contentDelta(previous: List<FixtureItem>, percent: Int): List<FixtureItem> {
            val changed = (previous.size.toLong() * percent / 100L).toInt()
            return previous.mapIndexed { index, item ->
                val ordered = item.copy(ordinal = index.toLong())
                if (index < changed) {
                    ordered.copy(locator = "https://stream.invalid/live/$index?session=delta-$percent").rehash()
                } else {
                    ordered
                }
            }
        }
    }

    private data class NormalizedRow(
        val logicalChannelId: String,
        val contentHash: String,
        val ordinal: Long,
    )

    private data class ProductionCounts(
        val providerRows: Long,
        val streamRows: Long,
        val searchDocumentRows: Long,
        val revisionRows: Long,
    )

    private data class MeasuredVariant(
        val correctnessDigestSha256: String,
        val correctnessCount: Int,
        val logicalIdentityDigestSha256: String,
        val previousGoodDigestSha256: String,
        val writes: C03ProductionRoomWriteCounts,
        val sample: C03ProductionRoomTimingSample,
    )

    private fun activeDigest(items: List<FixtureItem>): String = activeDigest(
        items.map { NormalizedRow(it.logicalChannelId, it.contentHash, it.ordinal) },
    )

    private fun activeDigest(rows: List<NormalizedRow>): String = digestFrames(
        ACTIVE_DIGEST_DOMAIN,
        rows.sortedWith(compareBy<NormalizedRow> { it.logicalChannelId }.thenBy { it.contentHash })
            .flatMap { listOf(it.logicalChannelId, it.contentHash) },
    )

    private fun logicalDigest(rows: List<NormalizedRow>): String = digestFrames(
        LOGICAL_DIGEST_DOMAIN,
        rows.map { it.logicalChannelId }.sorted(),
    )

    private fun elapsed(startedAt: Long): Long = (nanoTime() - startedAt).coerceAtLeast(1L)

    private fun File.safeLength(): Long = if (isFile) length().coerceAtLeast(0L) else 0L

    private fun String.safeEnvironmentValue(): String =
        trim().replace(CONTROL_CHARACTERS, " ").take(64).ifBlank { "unknown" }

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val METHOD_VERSION = "c03-production-room-file-v1"
        const val SOURCE_ID = "c03-production-ab-source"
        const val CREDENTIAL_REF = "credential-c03-production-ab"
        const val BASELINE_REVISION = 1L
        const val REFRESH_REVISION = 2L
        const val BASELINE_STARTED_AT = 10L
        const val BASELINE_ACTIVATED_AT = 20L
        const val REFRESH_STARTED_AT = 100L
        const val REFRESH_ACTIVATED_AT = 120L
        const val BASELINE_STALE_BEFORE = 50L
        const val RUN_BASELINE = "c03-baseline-run"
        const val RUN_REFRESH = "c03-refresh-run"
        const val BATCH_SIZE = 40
        const val LOGICAL_ID_DOMAIN = "catalog-logical-v1"
        const val CONTENT_HASH_DOMAIN = "c03-production-content-v1"
        const val SEARCH_HASH_DOMAIN = "c03-production-search-v1"
        const val ACTIVE_DIGEST_DOMAIN = "c03-active-digest-v1"
        const val LOGICAL_DIGEST_DOMAIN = "c03-logical-digest-v1"
        val CONTROL_CHARACTERS = Regex("[\\u0000-\\u001f]")

        fun C03ProductionScenario.id(): String = when (this) {
            C03ProductionScenario.DELTA_0 -> "delta-0"
            C03ProductionScenario.DELTA_1 -> "delta-1"
            C03ProductionScenario.DELTA_10 -> "delta-10"
            C03ProductionScenario.DELTA_100 -> "delta-100"
            C03ProductionScenario.REORDER -> "reorder"
            C03ProductionScenario.REMOVE_10 -> "remove-10"
            C03ProductionScenario.TOKEN_CHURN -> "token-churn"
        }

        val C03ProductionScenario.id: String get() = id()

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
