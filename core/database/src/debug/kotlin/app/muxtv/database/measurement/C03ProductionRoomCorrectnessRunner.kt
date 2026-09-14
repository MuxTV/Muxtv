package app.muxtv.database.measurement

import android.content.Context
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import app.muxtv.database.MuxTvDatabase
import app.muxtv.database.RoomSourceRefreshStore
import app.muxtv.database.RoomSourceRevisionStore
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRefreshStore
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.SourceRevisionStore
import app.muxtv.database.StagedCatalogEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class C03ProductionScenario {
    DELTA_0,
    DELTA_1,
    DELTA_10,
    DELTA_100,
    REORDER,
    REMOVE_10,
    TOKEN_CHURN,
}

internal data class C03ProductionCorrectnessSnapshot(
    val activeCount: Int,
    val activeDigestSha256: String,
    val logicalIdentityDigestSha256: String,
    val previousGoodDigestSha256: String,
    val activeOrderDigestSha256: String,
    val catalogPayloadRowsAdded: Int = 0,
    val searchPayloadRowsAdded: Int = 0,
    val membershipRowsAdded: Int = 0,
    val distinctLogicalIdentityCount: Int = 0,
    val distinctCatalogPayloadCount: Int = 0,
    val membershipCount: Int = 0,
    val stagingEntryCount: Int = 0,
    val stagingMembershipCount: Int = 0,
    val orphanRowsAfterBoundedCleanup: Int = 0,
    val cleanupPasses: Int = 0,
    val lateActivationPublished: Boolean = false,
)

internal data class C03ProductionContentScenarioResult(
    val production: C03ProductionCorrectnessSnapshot,
    val candidate: C03ProductionCorrectnessSnapshot,
    val expectedIncomingOrderDigestSha256: String,
    val baselineLogicalIdentityDigestSha256: String,
    val baselineActiveDigestSha256: String,
)

internal data class C03ProductionPairResult(
    val production: C03ProductionCorrectnessSnapshot,
    val candidate: C03ProductionCorrectnessSnapshot,
)

internal data class C03ProductionSafetyResult(
    val production: C03ProductionCorrectnessSnapshot,
    val candidate: C03ProductionCorrectnessSnapshot,
    val productionSuperseded: Boolean = false,
    val candidateSuperseded: Boolean = false,
)

/**
 * Correctness-only A/B proof for #364.
 *
 * A is the current production Room representation. B is the debug-only production-equivalent
 * immutable-payload + revision-membership Room candidate. Both use in-memory Room deliberately:
 * file/WAL/timing evidence belongs to the next evidence layer and must not contaminate correctness.
 */
internal class C03ProductionRoomCorrectnessRunner(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun runContentScenario(
        scenario: C03ProductionScenario,
        entryCount: Int,
    ): C03ProductionContentScenarioResult {
        require(entryCount > 0)
        val baseline = Fixture.baseline(entryCount)
        val incoming = scenario.apply(baseline)
        val expectedOrder = orderDigest(incoming.toNormalizedRows())
        val baselineLogical = logicalDigest(baseline.toNormalizedRows())
        val baselineActive = activeDigest(baseline.toNormalizedRows())

        return withHarnesses { production, candidate ->
            publishBaseline(production, candidate, baseline)
            val beforeCandidate = candidate.dao.rowCounts()
            acquireRefresh(production, candidate, RUN_REFRESH, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE)
            beginAndStage(production, candidate, REFRESH_REVISION, incoming)
            val afterCandidateStage = candidate.dao.rowCounts()

            val productionActivation = production.revisions.activateIfRefreshOwnerMatches(
                sourceId = SOURCE_ID,
                revisionNumber = REFRESH_REVISION,
                expectedCredentialRef = CREDENTIAL_REF,
                expectedRunToken = RUN_REFRESH,
                activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
                statistics = SourceRevisionStatistics(incoming.size, 0, 0),
            )
            check(productionActivation is SourceRevisionActivationResult.Activated)
            check(
                candidate.dao.activateIfRefreshOwnerMatches(
                    sourceId = SOURCE_ID,
                    revisionNumber = REFRESH_REVISION,
                    expectedCredentialRef = CREDENTIAL_REF,
                    expectedRunToken = RUN_REFRESH,
                    activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
                ) == C03ProductionCandidateActivationResult.Published,
            )

            val productionRows = production.database.productionRows(REFRESH_REVISION)
            val productionPrevious = production.database.productionRows(BASELINE_REVISION)
            val candidateRows = candidate.dao.activeRows(SOURCE_ID).map {
                NormalizedRow(it.logicalChannelId, it.contentHash, it.ordinal, it.payloadId)
            }
            val candidatePrevious = candidate.database.candidateRows(BASELINE_REVISION)

            check(activeDigest(productionRows) == activeDigest(incoming.toNormalizedRows()))
            check(activeDigest(candidateRows) == activeDigest(incoming.toNormalizedRows()))

            C03ProductionContentScenarioResult(
                production = snapshot(
                    activeRows = productionRows,
                    previousRows = productionPrevious,
                ),
                candidate = snapshot(
                    activeRows = candidateRows,
                    previousRows = candidatePrevious,
                    catalogPayloadRowsAdded = afterCandidateStage.payloadRows - beforeCandidate.payloadRows,
                    searchPayloadRowsAdded = afterCandidateStage.searchPayloadRows - beforeCandidate.searchPayloadRows,
                    membershipRowsAdded = afterCandidateStage.membershipRows - beforeCandidate.membershipRows,
                ),
                expectedIncomingOrderDigestSha256 = expectedOrder,
                baselineLogicalIdentityDigestSha256 = baselineLogical,
                baselineActiveDigestSha256 = baselineActive,
            )
        }
    }

    suspend fun runDuplicateScenario(): C03ProductionPairResult {
        val item = Fixture.baseline(1).single()
        val incoming = listOf(item.copy(ordinal = 0), item.copy(ordinal = 1))
        return withHarnesses { production, candidate ->
            publishBaseline(production, candidate, listOf(item))
            acquireRefresh(production, candidate, RUN_REFRESH, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE)
            beginAndStage(production, candidate, REFRESH_REVISION, incoming)
            check(
                production.revisions.activateIfRefreshOwnerMatches(
                    SOURCE_ID,
                    REFRESH_REVISION,
                    CREDENTIAL_REF,
                    RUN_REFRESH,
                    REFRESH_ACTIVATED_AT,
                    SourceRevisionStatistics(incoming.size, 0, 0),
                ) is SourceRevisionActivationResult.Activated,
            )
            check(
                candidate.dao.activateIfRefreshOwnerMatches(
                    SOURCE_ID,
                    REFRESH_REVISION,
                    CREDENTIAL_REF,
                    RUN_REFRESH,
                    REFRESH_ACTIVATED_AT,
                ) == C03ProductionCandidateActivationResult.Published,
            )
            val productionRows = production.database.productionRows(REFRESH_REVISION)
            val candidateRows = candidate.dao.activeRows(SOURCE_ID).map {
                NormalizedRow(it.logicalChannelId, it.contentHash, it.ordinal, it.payloadId)
            }
            C03ProductionPairResult(
                production = snapshot(productionRows, production.database.productionRows(BASELINE_REVISION)),
                candidate = snapshot(
                    activeRows = candidateRows,
                    previousRows = candidate.database.candidateRows(BASELINE_REVISION),
                    distinctLogicalIdentityCount = candidateRows.map { it.logicalChannelId }.toSet().size,
                    distinctCatalogPayloadCount = candidateRows.mapNotNull { it.payloadId }.toSet().size,
                    membershipCount = candidate.dao.membershipCount(SOURCE_ID, REFRESH_REVISION),
                ),
            )
        }
    }

    suspend fun runPartialFailureScenario(entryCount: Int): C03ProductionSafetyResult {
        require(entryCount > 1)
        val baseline = Fixture.baseline(entryCount)
        val partial = C03ProductionScenario.DELTA_10.apply(baseline).take(entryCount / 2)
        return withHarnesses { production, candidate ->
            publishBaseline(production, candidate, baseline)
            val candidateBaselineCounts = candidate.dao.rowCounts()
            acquireRefresh(production, candidate, RUN_REFRESH, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE)
            production.revisions.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            candidate.dao.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            val failure = runCatching {
                stage(production, candidate, REFRESH_REVISION, partial)
                error("injected-c03-partial-refresh-failure")
            }
            check(failure.isFailure)
            production.revisions.discard(SOURCE_ID, REFRESH_REVISION)
            candidate.dao.discardRevision(SOURCE_ID, REFRESH_REVISION)
            val cleanup = compactToBaseline(candidate, candidateBaselineCounts)

            C03ProductionSafetyResult(
                production = safetySnapshotProduction(production),
                candidate = safetySnapshotCandidate(candidate, candidateBaselineCounts, cleanup),
            )
        }
    }

    suspend fun runStaleOwnerScenario(entryCount: Int): C03ProductionSafetyResult {
        require(entryCount > 0)
        val baseline = Fixture.baseline(entryCount)
        val incoming = C03ProductionScenario.DELTA_10.apply(baseline)
        return withHarnesses { production, candidate ->
            publishBaseline(production, candidate, baseline)
            acquireRefresh(production, candidate, RUN_STALE, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE)
            beginAndStage(production, candidate, REFRESH_REVISION, incoming)

            check(
                production.refresh.tryAcquire(
                    sourceId = SOURCE_ID,
                    runToken = RUN_REPLACEMENT,
                    startedAtEpochMillis = REPLACEMENT_STARTED_AT,
                    staleBeforeEpochMillis = REPLACEMENT_STALE_BEFORE,
                ),
            )
            candidate.dao.setRunningRefreshOwner(SOURCE_ID, RUN_REPLACEMENT)

            val productionResult = production.revisions.activateIfRefreshOwnerMatches(
                SOURCE_ID,
                REFRESH_REVISION,
                CREDENTIAL_REF,
                RUN_STALE,
                REPLACEMENT_STARTED_AT + 1,
                SourceRevisionStatistics(incoming.size, 0, 0),
            )
            val candidateResult = candidate.dao.activateIfRefreshOwnerMatches(
                SOURCE_ID,
                REFRESH_REVISION,
                CREDENTIAL_REF,
                RUN_STALE,
                REPLACEMENT_STARTED_AT + 1,
            )

            C03ProductionSafetyResult(
                production = safetySnapshotProduction(production),
                candidate = safetySnapshotCandidate(candidate, candidate.dao.rowCounts(), CleanupState(0, 0)),
                productionSuperseded = productionResult == SourceRevisionActivationResult.Superseded,
                candidateSuperseded = candidateResult == C03ProductionCandidateActivationResult.Superseded,
            )
        }
    }

    suspend fun runCancellationScenario(entryCount: Int): C03ProductionSafetyResult {
        require(entryCount > 1)
        val baseline = Fixture.baseline(entryCount)
        val partial = C03ProductionScenario.DELTA_10.apply(baseline).take(entryCount / 2)
        return withHarnesses { production, candidate ->
            publishBaseline(production, candidate, baseline)
            val candidateBaselineCounts = candidate.dao.rowCounts()
            acquireRefresh(production, candidate, RUN_REFRESH, REFRESH_STARTED_AT, BASELINE_STALE_BEFORE)
            production.revisions.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            candidate.dao.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)

            coroutineScope {
                val staged = CompletableDeferred<Unit>()
                val job = launch {
                    try {
                        stage(production, candidate, REFRESH_REVISION, partial)
                        staged.complete(Unit)
                        awaitCancellation()
                    } catch (failure: Throwable) {
                        if (!staged.isCompleted) staged.completeExceptionally(failure)
                        throw failure
                    } finally {
                        withContext(NonCancellable) {
                            production.revisions.discard(SOURCE_ID, REFRESH_REVISION)
                            candidate.dao.discardRevision(SOURCE_ID, REFRESH_REVISION)
                        }
                    }
                }
                staged.await()
                job.cancelAndJoin()
            }

            val lateCandidate = candidate.dao.activateIfRefreshOwnerMatches(
                SOURCE_ID,
                REFRESH_REVISION,
                CREDENTIAL_REF,
                RUN_REFRESH,
                REFRESH_ACTIVATED_AT,
            )
            val cleanup = compactToBaseline(candidate, candidateBaselineCounts)
            val candidateSnapshot = safetySnapshotCandidate(candidate, candidateBaselineCounts, cleanup).copy(
                lateActivationPublished = lateCandidate == C03ProductionCandidateActivationResult.Published,
            )
            C03ProductionSafetyResult(
                production = safetySnapshotProduction(production),
                candidate = candidateSnapshot,
            )
        }
    }

    private suspend fun publishBaseline(
        production: ProductionHarness,
        candidate: CandidateHarness,
        baseline: List<FixtureItem>,
    ) {
        production.revisions.upsertSource(SourceDefinition(SOURCE_ID, "C03 A/B source", CREDENTIAL_REF))
        candidate.dao.upsertSource(SOURCE_ID, CREDENTIAL_REF)
        acquireRefresh(production, candidate, RUN_BASELINE, BASELINE_STARTED_AT, 0)
        beginAndStage(production, candidate, BASELINE_REVISION, baseline)
        check(
            production.revisions.activateIfRefreshOwnerMatches(
                SOURCE_ID,
                BASELINE_REVISION,
                CREDENTIAL_REF,
                RUN_BASELINE,
                BASELINE_ACTIVATED_AT,
                SourceRevisionStatistics(baseline.size, 0, 0),
            ) is SourceRevisionActivationResult.Activated,
        )
        check(
            candidate.dao.activateIfRefreshOwnerMatches(
                SOURCE_ID,
                BASELINE_REVISION,
                CREDENTIAL_REF,
                RUN_BASELINE,
                BASELINE_ACTIVATED_AT,
            ) == C03ProductionCandidateActivationResult.Published,
        )
    }

    private suspend fun acquireRefresh(
        production: ProductionHarness,
        candidate: CandidateHarness,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ) {
        check(
            production.refresh.tryAcquire(
                sourceId = SOURCE_ID,
                runToken = runToken,
                startedAtEpochMillis = startedAtEpochMillis,
                staleBeforeEpochMillis = staleBeforeEpochMillis,
            ),
        )
        candidate.dao.setRunningRefreshOwner(SOURCE_ID, runToken)
    }

    private suspend fun beginAndStage(
        production: ProductionHarness,
        candidate: CandidateHarness,
        revision: Long,
        items: List<FixtureItem>,
    ) {
        production.revisions.beginRevision(SOURCE_ID, revision, REFRESH_STARTED_AT)
        candidate.dao.beginRevision(SOURCE_ID, revision, REFRESH_STARTED_AT)
        stage(production, candidate, revision, items)
    }

    private suspend fun stage(
        production: ProductionHarness,
        candidate: CandidateHarness,
        revision: Long,
        items: List<FixtureItem>,
    ) {
        items.chunked(BATCH_SIZE).forEach { batch ->
            production.revisions.stageBatch(
                SOURCE_ID,
                revision,
                batch.map { it.toProductionEntry(revision) },
            )
            candidate.dao.stageBatch(
                SOURCE_ID,
                revision,
                batch.map(FixtureItem::toCandidateEntry),
            )
        }
    }

    private suspend fun safetySnapshotProduction(production: ProductionHarness): C03ProductionCorrectnessSnapshot {
        val activeRevision = production.database.activeRevision()
        val active = production.database.productionRows(activeRevision)
        return snapshot(
            activeRows = active,
            previousRows = production.database.productionRows(BASELINE_REVISION),
            stagingEntryCount = production.database.productionEntryCount(REFRESH_REVISION),
        )
    }

    private suspend fun safetySnapshotCandidate(
        candidate: CandidateHarness,
        baselineCounts: C03ProductionCandidateRowCounts,
        cleanup: CleanupState,
    ): C03ProductionCorrectnessSnapshot {
        val active = candidate.dao.activeRows(SOURCE_ID).map {
            NormalizedRow(it.logicalChannelId, it.contentHash, it.ordinal, it.payloadId)
        }
        val counts = candidate.dao.rowCounts()
        return snapshot(
            activeRows = active,
            previousRows = candidate.database.candidateRows(BASELINE_REVISION),
            stagingMembershipCount = candidate.dao.membershipCount(SOURCE_ID, REFRESH_REVISION),
            orphanRowsAfterBoundedCleanup =
                (counts.payloadRows - baselineCounts.payloadRows).coerceAtLeast(0) +
                    (counts.searchPayloadRows - baselineCounts.searchPayloadRows).coerceAtLeast(0),
            cleanupPasses = cleanup.passes,
        )
    }

    private suspend fun compactToBaseline(
        candidate: CandidateHarness,
        baseline: C03ProductionCandidateRowCounts,
    ): CleanupState {
        var passes = 0
        var deleted = 0
        repeat(MAX_CLEANUP_PASSES) {
            passes++
            val result = candidate.dao.compactOrphans(CLEANUP_BATCH_SIZE)
            deleted += result.payloadRowsDeleted + result.searchPayloadRowsDeleted
            if (result.payloadRowsDeleted == 0 && result.searchPayloadRowsDeleted == 0) {
                return CleanupState(passes, deleted)
            }
        }
        val counts = candidate.dao.rowCounts()
        check(counts.payloadRows <= baseline.payloadRows && counts.searchPayloadRows <= baseline.searchPayloadRows) {
            "C03 candidate orphan cleanup exceeded its bounded correctness budget."
        }
        return CleanupState(passes, deleted)
    }

    private suspend fun MuxTvDatabase.activeRevision(): Long = useReaderConnection { connection ->
        connection.usePrepared("SELECT activeRevision FROM sources WHERE id = ?") { statement ->
            statement.bindText(1, SOURCE_ID)
            check(statement.step())
            statement.getLong(0)
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
                        val content = contentHash(
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
                        )
                        add(NormalizedRow(logical, content, ordinal++))
                    }
                }
            }
        }

    private suspend fun C03ProductionCandidateDatabase.candidateRows(revision: Long): List<NormalizedRow> =
        useReaderConnection { connection ->
            connection.usePrepared(
                """
                SELECT m.ordinal, m.logicalChannelId, p.contentHash, p.payloadId
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
                                payloadId = statement.getText(3),
                            ),
                        )
                    }
                }
            }
        }

    private suspend fun <T> withHarnesses(
        block: suspend (ProductionHarness, CandidateHarness) -> T,
    ): T {
        val productionDb = Room.inMemoryDatabaseBuilder(applicationContext, MuxTvDatabase::class.java).build()
        val candidateDb = Room.inMemoryDatabaseBuilder(
            applicationContext,
            C03ProductionCandidateDatabase::class.java,
        ).build()
        val production = ProductionHarness(
            database = productionDb,
            revisions = RoomSourceRevisionStore(productionDb.sourceRevisionDao()),
            refresh = RoomSourceRefreshStore(productionDb.sourceRefreshDao()),
        )
        val candidate = CandidateHarness(candidateDb, candidateDb.candidateDao())
        return try {
            block(production, candidate)
        } finally {
            candidateDb.close()
            productionDb.close()
        }
    }

    private fun C03ProductionScenario.apply(previous: List<FixtureItem>): List<FixtureItem> = when (this) {
        C03ProductionScenario.DELTA_0 -> previous.mapIndexed { index, item -> item.copy(ordinal = index.toLong()) }
        C03ProductionScenario.DELTA_1 -> Fixture.contentDelta(previous, 1)
        C03ProductionScenario.DELTA_10 -> Fixture.contentDelta(previous, 10)
        C03ProductionScenario.DELTA_100 -> Fixture.contentDelta(previous, 100)
        C03ProductionScenario.REORDER -> previous.asReversed().mapIndexed { index, item -> item.copy(ordinal = index.toLong()) }
        C03ProductionScenario.REMOVE_10 -> previous.dropLast((previous.size / 10).coerceAtLeast(1))
            .mapIndexed { index, item -> item.copy(ordinal = index.toLong()) }
        C03ProductionScenario.TOKEN_CHURN -> previous.mapIndexed { index, item ->
            item.copy(
                ordinal = index.toLong(),
                locator = "${item.locator}&token=rotated-$index",
            ).rehash()
        }
    }

    private fun snapshot(
        activeRows: List<NormalizedRow>,
        previousRows: List<NormalizedRow>,
        catalogPayloadRowsAdded: Int = 0,
        searchPayloadRowsAdded: Int = 0,
        membershipRowsAdded: Int = 0,
        distinctLogicalIdentityCount: Int = activeRows.map { it.logicalChannelId }.toSet().size,
        distinctCatalogPayloadCount: Int = activeRows.mapNotNull { it.payloadId }.toSet().size,
        membershipCount: Int = activeRows.size,
        stagingEntryCount: Int = 0,
        stagingMembershipCount: Int = 0,
        orphanRowsAfterBoundedCleanup: Int = 0,
        cleanupPasses: Int = 0,
    ): C03ProductionCorrectnessSnapshot = C03ProductionCorrectnessSnapshot(
        activeCount = activeRows.size,
        activeDigestSha256 = activeDigest(activeRows),
        logicalIdentityDigestSha256 = logicalDigest(activeRows),
        previousGoodDigestSha256 = activeDigest(previousRows),
        activeOrderDigestSha256 = orderDigest(activeRows),
        catalogPayloadRowsAdded = catalogPayloadRowsAdded,
        searchPayloadRowsAdded = searchPayloadRowsAdded,
        membershipRowsAdded = membershipRowsAdded,
        distinctLogicalIdentityCount = distinctLogicalIdentityCount,
        distinctCatalogPayloadCount = distinctCatalogPayloadCount,
        membershipCount = membershipCount,
        stagingEntryCount = stagingEntryCount,
        stagingMembershipCount = stagingMembershipCount,
        orphanRowsAfterBoundedCleanup = orphanRowsAfterBoundedCleanup,
        cleanupPasses = cleanupPasses,
    )

    private data class ProductionHarness(
        val database: MuxTvDatabase,
        val revisions: SourceRevisionStore,
        val refresh: SourceRefreshStore,
    )

    private data class CandidateHarness(
        val database: C03ProductionCandidateDatabase,
        val dao: C03ProductionCandidateDao,
    )

    private data class CleanupState(val passes: Int, val deletedRows: Int)

    private data class NormalizedRow(
        val logicalChannelId: String,
        val contentHash: String,
        val ordinal: Long,
        val payloadId: String? = null,
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

    private fun List<FixtureItem>.toNormalizedRows(): List<NormalizedRow> = map {
        NormalizedRow(it.logicalChannelId, it.contentHash, it.ordinal)
    }

    private fun activeDigest(rows: List<NormalizedRow>): String = digestFrames(
        ACTIVE_DIGEST_DOMAIN,
        rows.sortedWith(compareBy<NormalizedRow> { it.logicalChannelId }.thenBy { it.contentHash })
            .flatMap { listOf(it.logicalChannelId, it.contentHash) },
    )

    private fun logicalDigest(rows: List<NormalizedRow>): String = digestFrames(
        LOGICAL_DIGEST_DOMAIN,
        rows.map { it.logicalChannelId }.sorted(),
    )

    private fun orderDigest(rows: List<NormalizedRow>): String = digestFrames(
        ORDER_DIGEST_DOMAIN,
        rows.sortedBy { it.ordinal }.flatMap { listOf(it.logicalChannelId, it.contentHash) },
    )

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
        const val BATCH_SIZE = 40
        const val CLEANUP_BATCH_SIZE = 64
        const val MAX_CLEANUP_PASSES = 4
        const val LOGICAL_ID_DOMAIN = "catalog-logical-v1"
        const val CONTENT_HASH_DOMAIN = "c03-production-content-v1"
        const val SEARCH_HASH_DOMAIN = "c03-production-search-v1"
        const val ACTIVE_DIGEST_DOMAIN = "c03-active-digest-v1"
        const val LOGICAL_DIGEST_DOMAIN = "c03-logical-digest-v1"
        const val ORDER_DIGEST_DOMAIN = "c03-order-digest-v1"

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
            return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
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
