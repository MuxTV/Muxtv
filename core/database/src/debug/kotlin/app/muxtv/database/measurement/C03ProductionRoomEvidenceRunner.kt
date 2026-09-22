package app.muxtv.database.measurement

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room3.Room
import androidx.room3.RoomDatabase
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

/**
 * Canonical C03 evidence composition.
 *
 * Latency/WAL samples come from [C03ProductionRoomMeasurementRunner] without audit triggers.
 * Physical INSERT/UPDATE/DELETE counts come from separate disposable file-backed Room databases.
 * Keeping those passes separate prevents measurement instrumentation from contaminating timing or
 * WAL growth while still reporting actual application-table mutations rather than net row deltas.
 */
internal class C03ProductionRoomEvidenceRunner(context: Context) {
    private val applicationContext = context.applicationContext

    suspend fun run(spec: C03ProductionRoomMeasurementSpec): C03ProductionRoomMeasurementReport {
        val correctnessRunner = C03ProductionRoomCorrectnessRunner(applicationContext)
        spec.scenarios.forEach { scenario ->
            val browseParity = correctnessRunner.runBrowseParityScenario(
                scenario = scenario,
                entryCount = spec.entryCount,
                pageSize = CANONICAL_BROWSE_PAGE_SIZE,
            )
            check(browseParity.productionRows == browseParity.candidateRows) {
                "C03 Browse parity failed before performance for ${scenario.name}."
            }
        }
        val duplicateBrowse = correctnessRunner.runDuplicateBrowseScenario()
        check(duplicateBrowse.productionVariantCount == DUPLICATE_VARIANT_COUNT)
        check(duplicateBrowse.candidateVariantCount == DUPLICATE_VARIANT_COUNT)

        val timed = C03ProductionRoomMeasurementRunner(applicationContext).run(spec)
        val physicalWrites = C03ProductionRoomPhysicalMutationRunner(applicationContext).run(spec)
        val supplemental = C03ProductionRoomSupplementalEvidenceRunner(applicationContext).run(spec)

        val scenarios = timed.scenarios.mapIndexed { scenarioIndex, scenarioReport ->
            val scenario = spec.scenarios[scenarioIndex]
            val writesByVariant = checkNotNull(physicalWrites[scenario]) {
                "C03 physical mutation evidence is missing for $scenario."
            }
            scenarioReport.copy(
                variants = scenarioReport.variants.map { variantReport ->
                    variantReport.copy(
                        writes = checkNotNull(writesByVariant[variantReport.variant]) {
                            "C03 physical mutation evidence is missing for ${variantReport.variant}."
                        },
                    )
                },
            )
        }

        return timed.copy(
            methodVersion = METHOD_VERSION,
            scenarios = scenarios,
            repeatedRevisionStorage = supplemental.repeatedRevisionStorage,
            safety = supplemental.safety,
            limitations = timed.limitations
                .filterNot { limitation ->
                    limitation.contains("row-state", ignoreCase = true) ||
                        limitation.contains("trigger-based", ignoreCase = true) ||
                        limitation.contains("Search latency", ignoreCase = true) ||
                        limitation.contains("Repeated-revision", ignoreCase = true)
                }
                .plus(
                    "Physical write counts are application-table INSERT/UPDATE/DELETE mutations collected by persistent audit triggers in a separate untimed file-backed Room pass; timed latency/WAL databases never contain audit triggers.",
                )
                .plus(
                    "Repeated-revision storage and refresh safety are collected in separate untimed file-backed passes so they cannot contaminate stage/search latency distributions.",
                )
                .plus(
                    "This report qualifies the production-equivalent candidate behavior only; it does not exercise or approve a production schema migration or read-path switch.",
                )
                .plus(
                    "Allocations, GC and peak-memory deltas are not claimed by this file-backed Room pass; no allocation profiler is attached to timed sections.",
                ),
        )
    }

    private companion object {
        const val METHOD_VERSION = "c03-production-room-file-v8-browse-parity-gated-membership-multiplicity-c01-interleaved-correctness-first-physical-mutations-search-storage-safety"
        const val CANONICAL_BROWSE_PAGE_SIZE = 64
        const val DUPLICATE_VARIANT_COUNT = 2
    }
}

private class C03ProductionRoomPhysicalMutationRunner(context: Context) {
    private val applicationContext = context.applicationContext
    private val databaseSequence = AtomicInteger()

    suspend fun run(
        spec: C03ProductionRoomMeasurementSpec,
    ): Map<C03ProductionScenario, Map<C03ProductionMeasurementVariant, C03ProductionRoomWriteCounts>> =
        withContext(Dispatchers.IO) {
            val baseline = AuditFixture.baseline(spec.entryCount)
            spec.scenarios.associateWith { scenario ->
                val incoming = scenario.applyTo(baseline)
                C03ProductionMeasurementVariant.entries.associateWith { variant ->
                    when (variant) {
                        C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION ->
                            auditProduction(scenario, baseline, incoming)
                        C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE ->
                            auditCandidate(scenario, baseline, incoming)
                    }
                }
            }
        }

    private suspend fun auditProduction(
        scenario: C03ProductionScenario,
        baseline: List<AuditFixtureItem>,
        incoming: List<AuditFixtureItem>,
    ): C03ProductionRoomWriteCounts {
        val name = nextDatabaseName("a", scenario)
        cleanupDatabase(name)
        prepareProductionBaseline(name, baseline)
        installAudit(name, PRODUCTION_AUDIT_TABLES)

        val database = openProduction(name)
        try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            check(
                refresh.tryAcquire(
                    sourceId = SOURCE_ID,
                    runToken = RUN_REFRESH,
                    startedAtEpochMillis = REFRESH_STARTED_AT,
                    staleBeforeEpochMillis = BASELINE_STALE_BEFORE,
                ),
            ) { "C03 production audit refresh owner could not be acquired." }
            revisions.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            incoming.chunked(C03_PRODUCTION_EVIDENCE_BATCH_SIZE).forEach { batch ->
                revisions.stageBatch(
                    SOURCE_ID,
                    REFRESH_REVISION,
                    batch.map { it.toProductionEntry(REFRESH_REVISION) },
                )
            }
            check(
                revisions.activateIfRefreshOwnerMatches(
                    sourceId = SOURCE_ID,
                    revisionNumber = REFRESH_REVISION,
                    expectedCredentialRef = CREDENTIAL_REF,
                    expectedRunToken = RUN_REFRESH,
                    activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
                    statistics = SourceRevisionStatistics(incoming.size, 0, 0),
                ) is SourceRevisionActivationResult.Activated,
            ) { "C03 production audit guarded publication failed." }
        } finally {
            database.close()
        }

        return try {
            val snapshot = snapshotAndUninstall(name, PRODUCTION_AUDIT_TABLES)
            C03ProductionRoomWriteCounts(
                providerOrPayloadWrites = snapshot.totalOf(
                    "canonical_channels",
                    "provider_channels",
                    "stream_variants",
                ),
                searchPayloadWrites = 0L,
                searchDocumentWrites = snapshot.table("search_documents").total,
                membershipWrites = 0L,
                revisionMetadataWrites = snapshot.table("source_revisions").total,
                sourceMetadataWrites = snapshot.totalOf("sources", "source_refresh_states"),
                cleanupDeletes = snapshot.totalDeletes(),
            )
        } finally {
            cleanupDatabase(name)
        }
    }

    private suspend fun auditCandidate(
        scenario: C03ProductionScenario,
        baseline: List<AuditFixtureItem>,
        incoming: List<AuditFixtureItem>,
    ): C03ProductionRoomWriteCounts {
        val name = nextDatabaseName("b", scenario)
        cleanupDatabase(name)
        prepareCandidateBaseline(name, baseline)
        installAudit(name, CANDIDATE_AUDIT_TABLES)

        val database = openCandidate(name)
        try {
            val dao = database.candidateDao()
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_REFRESH)
            dao.beginRevision(SOURCE_ID, REFRESH_REVISION, REFRESH_STARTED_AT)
            incoming.chunked(C03_PRODUCTION_EVIDENCE_BATCH_SIZE).forEach { batch ->
                dao.stageBatch(
                    SOURCE_ID,
                    REFRESH_REVISION,
                    batch.map(AuditFixtureItem::toCandidateEntry),
                )
            }
            check(
                dao.activateIfRefreshOwnerMatches(
                    sourceId = SOURCE_ID,
                    revisionNumber = REFRESH_REVISION,
                    expectedCredentialRef = CREDENTIAL_REF,
                    expectedRunToken = RUN_REFRESH,
                    activatedAtEpochMillis = REFRESH_ACTIVATED_AT,
                ) == C03ProductionCandidateActivationResult.Published,
            ) { "C03 candidate audit guarded publication failed." }
        } finally {
            database.close()
        }

        return try {
            val snapshot = snapshotAndUninstall(name, CANDIDATE_AUDIT_TABLES)
            C03ProductionRoomWriteCounts(
                providerOrPayloadWrites = snapshot.table("c03_production_candidate_payloads").total,
                searchPayloadWrites = snapshot.table("c03_production_candidate_search_payloads").total,
                searchDocumentWrites = snapshot.table("c03_production_candidate_search_documents").total,
                membershipWrites = snapshot.table("c03_production_candidate_memberships").total,
                revisionMetadataWrites = snapshot.table("c03_production_candidate_revisions").total,
                sourceMetadataWrites = snapshot.totalOf(
                    "c03_production_candidate_sources",
                    "c03_production_candidate_refresh_owners",
                ),
                cleanupDeletes = snapshot.totalDeletes(),
            )
        } finally {
            cleanupDatabase(name)
        }
    }

    private suspend fun prepareProductionBaseline(
        name: String,
        baseline: List<AuditFixtureItem>,
    ) {
        val database = openProduction(name)
        try {
            val revisions = RoomSourceRevisionStore(database.sourceRevisionDao())
            val refresh = RoomSourceRefreshStore(database.sourceRefreshDao())
            revisions.upsertSource(SourceDefinition(SOURCE_ID, "C03 physical mutation audit", CREDENTIAL_REF))
            check(refresh.tryAcquire(SOURCE_ID, RUN_BASELINE, BASELINE_STARTED_AT, 0L))
            revisions.beginRevision(SOURCE_ID, BASELINE_REVISION, BASELINE_STARTED_AT)
            baseline.chunked(C03_PRODUCTION_EVIDENCE_BATCH_SIZE).forEach { batch ->
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

    private suspend fun prepareCandidateBaseline(
        name: String,
        baseline: List<AuditFixtureItem>,
    ) {
        val database = openCandidate(name)
        try {
            val dao = database.candidateDao()
            dao.upsertSource(SOURCE_ID, CREDENTIAL_REF)
            dao.setRunningRefreshOwner(SOURCE_ID, RUN_BASELINE)
            dao.beginRevision(SOURCE_ID, BASELINE_REVISION, BASELINE_STARTED_AT)
            baseline.chunked(C03_PRODUCTION_EVIDENCE_BATCH_SIZE).forEach { batch ->
                dao.stageBatch(
                    SOURCE_ID,
                    BASELINE_REVISION,
                    batch.map(AuditFixtureItem::toCandidateEntry),
                )
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

    private fun installAudit(name: String, tables: Set<String>) {
        val file = applicationContext.getDatabasePath(name)
        check(file.isFile) { "C03 physical mutation audit database is missing: $name" }
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            raw.enableWriteAheadLogging()
            C03ProductionRoomMutationAudit.install(raw, tables)
        } finally {
            raw.close()
        }
    }

    private fun snapshotAndUninstall(
        name: String,
        tables: Set<String>,
    ): C03ProductionRoomMutationAuditSnapshot {
        val file = applicationContext.getDatabasePath(name)
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        return try {
            val snapshot = C03ProductionRoomMutationAudit.snapshot(raw)
            C03ProductionRoomMutationAudit.uninstall(raw, tables)
            snapshot
        } finally {
            raw.close()
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

    private fun cleanupDatabase(name: String) {
        applicationContext.deleteDatabase(name)
        val file = applicationContext.getDatabasePath(name)
        File(file.path + "-wal").delete()
        File(file.path + "-shm").delete()
    }

    private fun nextDatabaseName(kind: String, scenario: C03ProductionScenario): String =
        "c03-production-audit-$kind-${scenario.name.lowercase()}-${databaseSequence.incrementAndGet()}.db"

    private fun C03ProductionRoomMutationAuditSnapshot.totalOf(vararg tableNames: String): Long =
        tableNames.sumOf { table(it).total }

    private fun C03ProductionRoomMutationAuditSnapshot.totalDeletes(): Long =
        tables.sumOf(C03ProductionRoomTableMutationCount::deletes)

    private fun C03ProductionScenario.applyTo(
        previous: List<AuditFixtureItem>,
    ): List<AuditFixtureItem> = when (this) {
        C03ProductionScenario.DELTA_0 -> previous.mapIndexed { index, item ->
            item.copy(ordinal = index.toLong())
        }
        C03ProductionScenario.DELTA_1 -> AuditFixture.contentDelta(previous, 1)
        C03ProductionScenario.DELTA_10 -> AuditFixture.contentDelta(previous, 10)
        C03ProductionScenario.DELTA_100 -> AuditFixture.contentDelta(previous, 100)
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

    private data class AuditFixtureItem(
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
        fun rehash(): AuditFixtureItem = copy(
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

    private object AuditFixture {
        fun baseline(size: Int): List<AuditFixtureItem> = List(size) { index ->
            val providerKey = "provider:item-${index.toString().padStart(5, '0')}"
            val logical = logicalChannelId(SOURCE_ID, providerKey)
            AuditFixtureItem(
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

        fun contentDelta(
            previous: List<AuditFixtureItem>,
            percent: Int,
        ): List<AuditFixtureItem> {
            val changed = (previous.size.toLong() * percent / 100L).toInt()
            return previous.mapIndexed { index, item ->
                val ordered = item.copy(ordinal = index.toLong())
                if (index < changed) {
                    ordered.copy(
                        locator = "https://stream.invalid/live/$index?session=delta-$percent",
                    ).rehash()
                } else {
                    ordered
                }
            }
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
        const val RUN_BASELINE = "c03-baseline-run"
        const val RUN_REFRESH = "c03-refresh-run"
        const val LOGICAL_ID_DOMAIN = "catalog-logical-v1"
        const val CONTENT_HASH_DOMAIN = "c03-production-content-v1"
        const val SEARCH_HASH_DOMAIN = "c03-production-search-v1"

        val PRODUCTION_AUDIT_TABLES = setOf(
            "canonical_channels",
            "provider_channels",
            "stream_variants",
            "search_documents",
            "source_revisions",
            "sources",
            "source_refresh_states",
        )
        val CANDIDATE_AUDIT_TABLES = setOf(
            "c03_production_candidate_payloads",
            "c03_production_candidate_search_payloads",
            "c03_production_candidate_search_documents",
            "c03_production_candidate_memberships",
            "c03_production_candidate_revisions",
            "c03_production_candidate_sources",
            "c03_production_candidate_refresh_owners",
        )

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