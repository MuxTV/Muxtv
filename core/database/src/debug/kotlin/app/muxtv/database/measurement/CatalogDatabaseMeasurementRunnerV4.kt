package app.muxtv.database.measurement

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.NowNextQuery
import app.muxtv.database.CURRENT_EPG_MATCH_POLICY_VERSION
import app.muxtv.database.ChannelSearchCandidateRow
import app.muxtv.database.ChannelSearchDataSource
import app.muxtv.database.EpgChannelEntity
import app.muxtv.database.EpgChannelMatchDecision
import app.muxtv.database.EpgChannelMatchEntity
import app.muxtv.database.EpgMatchPublicationResult
import app.muxtv.database.EpgMatchReasonCode
import app.muxtv.database.EpgProgrammeEntity
import app.muxtv.database.EpgRevisionActivationResult
import app.muxtv.database.EpgRevisionStatistics
import app.muxtv.database.EpgSourceDefinition
import app.muxtv.database.MuxTvDatabase
import app.muxtv.database.ProfileEntity
import app.muxtv.database.RoomChannelSearchRepository
import app.muxtv.database.RoomEpgGuideRepository
import app.muxtv.database.RoomEpgRevisionStore
import app.muxtv.database.RoomSourceRevisionStore
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.StagedCatalogEntry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * M0 correctness methodology v4.
 *
 * The v3 runner rebuilt the deterministic 50k catalog independently for stage-total, activation,
 * active-channel query and every Search repetition. Those rebuilds are setup work, not the
 * measured Search intervals, and made the hosted correctness gate exceed its bounded job time.
 *
 * V4 keeps the exact 50k workload and five measured repetitions, but models each repetition as
 * one real database lifecycle: stage 50k -> activate -> active query -> prepare EPG -> Search.
 * The small 250-row stage-batch probe and 32-source overview probe retain their own fresh files.
 * No timing threshold is introduced; this remains correctness/descriptive evidence only.
 */
internal class CatalogDatabaseMeasurementRunnerV4(
    context: Context,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val progress: (String) -> Unit = {},
) {
    private val applicationContext = context.applicationContext
    private val databaseSequence = AtomicInteger()

    suspend fun run(spec: CatalogDatabaseMeasurementSpec): CatalogDatabaseMeasurementReport =
        withContext(Dispatchers.IO) {
            mark("fixture-start")
            val fixture = PreparedCatalogFixture.create(spec.workload)
            mark("fixture-complete entries=${fixture.workload.entryCount}")

            val stageBatch = measureOperation(
                operationId = OPERATION_STAGE_BATCH,
                expectedResultCount = spec.workload.batchSize,
                workload = spec.workload,
            ) { iteration ->
                measureStageBatch(iteration, fixture)
            }
            mark("stage-batch-complete")

            repeat(spec.workload.warmupIterations) { warmupIndex ->
                val iteration = -(warmupIndex + 1)
                mark("lifecycle-warmup-${warmupIndex + 1}-start")
                measureLifecycleRepetition(iteration, fixture, capturePlans = false)
                mark("lifecycle-warmup-${warmupIndex + 1}-complete")
            }

            val lifecycle = List(spec.workload.measuredIterations) { index ->
                val iteration = index + 1
                mark("lifecycle-measured-$iteration-start")
                measureLifecycleRepetition(
                    iteration = iteration,
                    fixture = fixture,
                    capturePlans = index == 0,
                ).also {
                    mark("lifecycle-measured-$iteration-complete")
                }
            }

            val sourceOverview = measureOperation(
                operationId = OPERATION_SOURCE_OVERVIEW,
                expectedResultCount = spec.workload.sourceOverviewCount,
                workload = spec.workload,
            ) { iteration ->
                measureSourceOverview(iteration, spec.workload.sourceOverviewCount)
            }
            mark("source-overview-complete")

            val operations = buildList {
                add(stageBatch)
                add(operationReport(OPERATION_STAGE_TOTAL, lifecycle.map(LifecycleResult::stageTotal)))
                add(operationReport(OPERATION_ACTIVATE, lifecycle.map(LifecycleResult::activation)))
                add(operationReport(OPERATION_ACTIVE_CHANNELS, lifecycle.map(LifecycleResult::activeChannels)))
                add(sourceOverview)
                addAll(
                    SEARCH_SCENARIOS.flatMap { scenario ->
                        SearchPhase.entries.map { phase ->
                            val samples = lifecycle.map { repetition ->
                                repetition.search.getValue(scenario.id).sample(phase)
                            }
                            operationReport("${scenario.id}-${phase.id}", samples)
                        }
                    },
                )
            }
            val queryPlans = checkNotNull(lifecycle.firstOrNull()?.queryPlans) {
                "M0 v4 requires query-plan evidence from the first measured repetition."
            }

            mark("report-complete")
            CatalogDatabaseMeasurementReport(
                schemaVersion = REPORT_SCHEMA_VERSION,
                methodVersion = METHOD_VERSION,
                thresholdApplied = false,
                sourceCommit = spec.sourceCommit,
                runnerLabel = spec.runnerLabel,
                cacheState = CACHE_STATE,
                workload = spec.workload,
                environment = captureEnvironment(),
                fixture = CatalogDatabaseFixtureIdentity(
                    entryCount = fixture.workload.entryCount,
                    sha256 = fixture.sha256,
                ),
                operations = operations,
                queryPlans = queryPlans,
                failureCount = 0,
                limitations = LIMITATIONS,
            )
        }

    private suspend fun measureOperation(
        operationId: String,
        expectedResultCount: Int,
        workload: CatalogDatabaseMeasurementWorkload,
        block: suspend (Int) -> CatalogDatabaseMeasurementSample,
    ): CatalogDatabaseOperationReport {
        repeat(workload.warmupIterations) { warmupIndex ->
            val warmup = block(-(warmupIndex + 1))
            check(warmup.resultCount == expectedResultCount) {
                "Catalog database measurement warmup agreement failed."
            }
        }
        val samples = List(workload.measuredIterations) { index ->
            block(index + 1).also { measured ->
                check(measured.resultCount == expectedResultCount) {
                    "Catalog database measurement result agreement failed."
                }
            }
        }
        return operationReport(operationId, samples)
    }

    private suspend fun measureStageBatch(
        iteration: Int,
        fixture: PreparedCatalogFixture,
    ): CatalogDatabaseMeasurementSample = withFreshDatabase(OPERATION_STAGE_BATCH, iteration) { handle ->
        handle.prepareRevision()
        val startedAt = nanoTime()
        handle.store.stageBatch(SOURCE_ID, REVISION_NUMBER, fixture.batches.first())
        val completedAt = nanoTime()
        val count = handle.database.sourceRevisionDao().countRevisionEntries(SOURCE_ID, REVISION_NUMBER)
        check(count == fixture.workload.batchSize) {
            "Catalog database staged batch count agreement failed."
        }
        handle.sample(iteration, completedAt - startedAt, count)
    }

    private suspend fun measureLifecycleRepetition(
        iteration: Int,
        fixture: PreparedCatalogFixture,
        capturePlans: Boolean,
    ): LifecycleResult = withFreshDatabase("lifecycle", iteration) { handle ->
        handle.prepareProfile()
        handle.prepareRevision()

        val stageStartedAt = nanoTime()
        fixture.batches.forEach { batch ->
            handle.store.stageBatch(SOURCE_ID, REVISION_NUMBER, batch)
        }
        val stageCompletedAt = nanoTime()
        val stagedCount = handle.database.sourceRevisionDao()
            .countRevisionEntries(SOURCE_ID, REVISION_NUMBER)
        check(stagedCount == fixture.workload.entryCount) {
            "Catalog database staged total count agreement failed."
        }
        val stageTotal = handle.sample(
            iteration = iteration,
            wallTimeNanos = stageCompletedAt - stageStartedAt,
            resultCount = stagedCount,
        )
        mark("${iteration.label()}-stage-complete")

        val activationStartedAt = nanoTime()
        val activation = handle.store.activate(
            sourceId = SOURCE_ID,
            revisionNumber = REVISION_NUMBER,
            activatedAtEpochMillis = ACTIVATED_AT_EPOCH_MILLIS,
            statistics = SourceRevisionStatistics(
                parsedEntries = fixture.workload.entryCount,
                skippedEntries = 0,
                warningCount = 0,
            ),
        )
        val activationCompletedAt = nanoTime()
        val activated = activation as? SourceRevisionActivationResult.Activated
            ?: error("Catalog database activation agreement failed.")
        check(activated.entryCount == fixture.workload.entryCount) {
            "Catalog database activation entry count agreement failed."
        }
        val activationSample = handle.sample(
            iteration = iteration,
            wallTimeNanos = activationCompletedAt - activationStartedAt,
            resultCount = activated.entryCount,
        )
        mark("${iteration.label()}-activation-complete")

        val activeStartedAt = nanoTime()
        val activeRows = handle.database.playbackCatalogDao().observeActiveChannels(
            profileId = PROFILE_ID,
            searchPattern = null,
            favoritesOnly = false,
            limit = fixture.workload.firstPageLimit,
        ).first()
        val activeCompletedAt = nanoTime()
        check(activeRows.size == fixture.workload.firstPageLimit) {
            "Catalog database active channel query count agreement failed."
        }
        val activeSample = handle.sample(
            iteration = iteration,
            wallTimeNanos = activeCompletedAt - activeStartedAt,
            resultCount = activeRows.size,
        )
        mark("${iteration.label()}-active-query-complete")

        mark("${iteration.label()}-epg-start")
        handle.prepareActiveEpg(fixture)
        mark("${iteration.label()}-epg-complete")

        val measuredSearch = SEARCH_SCENARIOS.associate { scenario ->
            scenario.id to measureSearchScenario(
                iteration = iteration,
                handle = handle,
                scenario = scenario,
                captureQueryTrace = false,
            )
        }
        mark("${iteration.label()}-search-complete")

        val queryPlans = if (capturePlans) {
            // Trace collection is deliberately outside measured Search samples. Re-run the six
            // read-only scenarios on this already prepared first-repetition database, then obtain
            // EXPLAIN QUERY PLAN from exactly the canonical-id sets the repository requested.
            val traced = SEARCH_SCENARIOS.associate { scenario ->
                scenario.id to measureSearchScenario(
                    iteration = 0,
                    handle = handle,
                    scenario = scenario,
                    captureQueryTrace = true,
                )
            }
            captureSearchQueryPlans(handle, traced.toQueryTrace()).also {
                mark("${iteration.label()}-query-plans-complete")
            }
        } else {
            null
        }

        LifecycleResult(
            stageTotal = stageTotal,
            activation = activationSample,
            activeChannels = activeSample,
            search = measuredSearch,
            queryPlans = queryPlans,
        )
    }

    private suspend fun captureSearchQueryPlans(
        handle: DatabaseHandle,
        trace: SearchQueryTrace,
    ): List<CatalogDatabaseQueryPlan> = CatalogSearchQueryPlans.queries(
        profileId = PROFILE_ID,
        nowEpochMillis = SEARCH_NOW_EPOCH_MILLIS,
        candidateProbes = trace.candidateProbes,
        summaryCanonicalIdSets = trace.summaryCanonicalIdSets,
        nowNextCanonicalIdSets = trace.nowNextCanonicalIdSets,
    ).map { (operationId, statements) ->
        CatalogDatabaseQueryPlan(
            operationId = operationId,
            details = statements.flatMap { sql -> handle.queryPlan(sql) },
        )
    }

    private suspend fun measureSearchScenario(
        iteration: Int,
        handle: DatabaseHandle,
        scenario: SearchScenario,
        captureQueryTrace: Boolean,
    ): SearchScenarioResult {
        val dataSource = TimedSearchDataSource(
            delegate = handle.database.channelSearchDao(),
            nanoTime = nanoTime,
            captureQueryTrace = captureQueryTrace,
        )
        val guide = TimedGuideRepository(
            delegate = RoomEpgGuideRepository(handle.database.epgGuideDao()),
            nanoTime = nanoTime,
            captureQueryTrace = captureQueryTrace,
        )
        val repository: ChannelSearchRepository = RoomChannelSearchRepository(dataSource, guide)
        val startedAt = nanoTime()
        val snapshot = repository.observe(
            ChannelSearchQuery(
                profileId = PROFILE_ID,
                text = scenario.query,
                nowEpochMillis = SEARCH_NOW_EPOCH_MILLIS,
                limit = SEARCH_RESULT_LIMIT,
            ),
        ).first()
        val totalNanos = (nanoTime() - startedAt).coerceAtLeast(1L)
        check(snapshot.results.size == scenario.expectedPublishedCount)
        check(snapshot.isTruncated == scenario.expectedTruncated)
        check(snapshot.results.map { it.channel.channelId } == scenario.expectedIds)
        check(
            snapshot.results.map { it.currentProgrammeTitle } ==
                scenario.expectedIds.map { id ->
                    "Programme CrossSignal${id.removePrefix("canonical-")}"
                },
        )
        val expectedNextBoundary = expectedCatalogSearchBoundaryEpochMillis(
            canonicalChannelIds = scenario.expectedIds,
            firstBoundaryEpochMillis = FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS,
        )
        check(snapshot.nextBoundaryEpochMillis == expectedNextBoundary)

        return SearchScenarioResult(
            candidate = handle.sample(iteration, dataSource.candidateNanos, dataSource.candidateRows),
            summary = handle.sample(
                iteration = iteration,
                wallTimeNanos = (
                    totalNanos - dataSource.candidateNanos - guide.nowNextNanos
                ).coerceAtLeast(1L),
                resultCount = dataSource.summaryRows,
            ),
            nowNext = handle.sample(iteration, guide.nowNextNanos, guide.nowNextRows),
            candidateProbes = dataSource.candidateProbes.toList(),
            summaryCanonicalIds = dataSource.summaryCanonicalIds,
            nowNextCanonicalIds = guide.nowNextCanonicalIds,
        )
    }

    private fun operationReport(
        operationId: String,
        samples: List<CatalogDatabaseMeasurementSample>,
    ): CatalogDatabaseOperationReport {
        val expectedResultCount = samples.first().resultCount
        check(expectedResultCount > 0 && samples.all { it.resultCount == expectedResultCount })
        return CatalogDatabaseOperationReport(
            operationId = operationId,
            expectedResultCount = expectedResultCount,
            samples = samples,
            wallTimeNanos = CatalogDatabaseMeasurementStatistics.summarize(
                samples.map(CatalogDatabaseMeasurementSample::wallTimeNanos),
            ),
            databaseBytes = CatalogDatabaseMeasurementStatistics.summarize(
                samples.map(CatalogDatabaseMeasurementSample::databaseBytes),
            ),
            walBytes = CatalogDatabaseMeasurementStatistics.summarize(
                samples.map(CatalogDatabaseMeasurementSample::walBytes),
            ),
            shmBytes = CatalogDatabaseMeasurementStatistics.summarize(
                samples.map(CatalogDatabaseMeasurementSample::shmBytes),
            ),
        )
    }

    private suspend fun measureSourceOverview(
        iteration: Int,
        sourceCount: Int,
    ): CatalogDatabaseMeasurementSample = withFreshDatabase(OPERATION_SOURCE_OVERVIEW, iteration) { handle ->
        repeat(sourceCount) { index ->
            handle.store.upsertSource(
                SourceDefinition(
                    id = "measurement-source-${index.toString().padStart(3, '0')}",
                    name = "Measurement Source ${index.toString().padStart(3, '0')}",
                ),
            )
        }
        val startedAt = nanoTime()
        val rows = handle.database.sourceRefreshDao().observeOverviews().first()
        val completedAt = nanoTime()
        check(rows.size == sourceCount) {
            "Catalog database source overview count agreement failed."
        }
        handle.sample(iteration, completedAt - startedAt, rows.size)
    }

    private suspend fun <T> withFreshDatabase(
        operationId: String,
        iteration: Int,
        block: suspend (DatabaseHandle) -> T,
    ): T {
        val safeIteration = if (iteration > 0) "m$iteration" else "w${-iteration}"
        val sequence = databaseSequence.incrementAndGet()
        val name = "muxtv-measurement-v4-$operationId-$safeIteration-$sequence.db"
        applicationContext.deleteDatabase(name)
        val database = Room.databaseBuilder(
            applicationContext,
            MuxTvDatabase::class.java,
            name,
        ).setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING).build()
        val handle = DatabaseHandle(
            context = applicationContext,
            databaseName = name,
            database = database,
            store = RoomSourceRevisionStore(database.sourceRevisionDao()),
        )
        return try {
            block(handle)
        } finally {
            database.close()
            applicationContext.deleteDatabase(name)
        }
    }

    private fun captureEnvironment(): CatalogDatabaseMeasurementEnvironment {
        val activityManager = requireNotNull(
            applicationContext.getSystemService(ActivityManager::class.java),
        ) { "Android activity manager is unavailable." }
        return CatalogDatabaseMeasurementEnvironment(
            manufacturer = Build.MANUFACTURER.safeEnvironmentValue(),
            model = Build.MODEL.safeEnvironmentValue(),
            fingerprint = Build.FINGERPRINT.safeEnvironmentValue(MAX_FINGERPRINT_LENGTH),
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.map { it.safeEnvironmentValue() },
            lowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
        )
    }

    private fun String.safeEnvironmentValue(maxLength: Int = MAX_ENVIRONMENT_VALUE_LENGTH): String =
        trim().take(maxLength).ifBlank { "unknown" }

    private fun mark(message: String) {
        progress("m0-v4:$message")
    }

    private fun Int.label(): String = if (this > 0) "measured-$this" else "warmup-${-this}"

    private class DatabaseHandle(
        private val context: Context,
        private val databaseName: String,
        val database: MuxTvDatabase,
        val store: RoomSourceRevisionStore,
    ) {
        suspend fun prepareProfile() {
            database.profileDao().insert(
                ProfileEntity(
                    id = PROFILE_ID,
                    name = "Measurement profile",
                    isPrimary = true,
                ),
            )
        }

        suspend fun prepareRevision() {
            store.upsertSource(
                SourceDefinition(
                    id = SOURCE_ID,
                    name = "Measurement source",
                ),
            )
            store.beginRevision(
                sourceId = SOURCE_ID,
                revisionNumber = REVISION_NUMBER,
                startedAtEpochMillis = STARTED_AT_EPOCH_MILLIS,
            )
        }

        suspend fun prepareActiveEpg(fixture: PreparedCatalogFixture) {
            val epgStore = RoomEpgRevisionStore(database.epgRevisionDao())
            epgStore.upsertSource(
                EpgSourceDefinition(
                    id = EPG_SOURCE_ID,
                    name = "Measurement guide",
                    providerSourceId = SOURCE_ID,
                    accessRef = null,
                    defaultZoneId = "UTC",
                ),
            )
            val revision = epgStore.beginRevision(EPG_SOURCE_ID, STARTED_AT_EPOCH_MILLIS)
            fixture.epgBatches.forEach { (channels, programmes) ->
                epgStore.stageBatch(channels, programmes)
            }
            check(
                epgStore.activateRevision(
                    sourceId = EPG_SOURCE_ID,
                    revisionNumber = revision,
                    activatedAtEpochMillis = ACTIVATED_AT_EPOCH_MILLIS,
                    statistics = EpgRevisionStatistics(
                        acceptedChannels = fixture.workload.entryCount,
                        acceptedProgrammes = fixture.workload.entryCount * 2,
                        skippedProgrammes = 0,
                        warningCount = 0,
                        unresolvedTimeCount = 0,
                    ),
                ) is EpgRevisionActivationResult.Activated,
            )
            val relation = requireNotNull(
                database.epgMatchingDao().relationSnapshot(EPG_SOURCE_ID),
            )
            val matches = List(fixture.workload.entryCount) { index ->
                val suffix = index.toString().padStart(5, '0')
                EpgChannelMatchEntity(
                    epgSourceId = EPG_SOURCE_ID,
                    epgRevisionNumber = revision,
                    providerSourceId = SOURCE_ID,
                    catalogRevisionNumber = REVISION_NUMBER,
                    epgExternalChannelId = "epg-$suffix",
                    matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
                    decision = EpgChannelMatchDecision.MATCHED.name,
                    reasonCode = EpgMatchReasonCode.EXACT_ID.name,
                    canonicalChannelId = "canonical-$suffix",
                    candidateCount = 1,
                )
            }
            check(
                database.epgMatchingDao().replaceIfCurrent(relation, matches) ==
                    EpgMatchPublicationResult.Applied,
            )
        }

        fun sample(
            iteration: Int,
            wallTimeNanos: Long,
            resultCount: Int,
        ): CatalogDatabaseMeasurementSample {
            val databaseFile = context.getDatabasePath(databaseName)
            return CatalogDatabaseMeasurementSample(
                iteration = iteration.coerceAtLeast(1),
                wallTimeNanos = wallTimeNanos.coerceAtLeast(1L),
                resultCount = resultCount,
                databaseBytes = databaseFile.safeLength(),
                walBytes = File(databaseFile.path + "-wal").safeLength(),
                shmBytes = File(databaseFile.path + "-shm").safeLength(),
            )
        }

        suspend fun queryPlan(sql: String): List<String> = database.useReaderConnection { connection ->
            connection.usePrepared("EXPLAIN QUERY PLAN $sql") { statement ->
                buildList {
                    while (statement.step()) add(statement.getText(3))
                }
            }
        }

        private fun File.safeLength(): Long = if (isFile) length().coerceAtLeast(0L) else 0L
    }

    private data class LifecycleResult(
        val stageTotal: CatalogDatabaseMeasurementSample,
        val activation: CatalogDatabaseMeasurementSample,
        val activeChannels: CatalogDatabaseMeasurementSample,
        val search: Map<String, SearchScenarioResult>,
        val queryPlans: List<CatalogDatabaseQueryPlan>?,
    )

    private data class SearchScenario(
        val id: String,
        val query: String,
        val expectedIds: List<String>,
        val expectedTruncated: Boolean,
    ) {
        val expectedPublishedCount: Int get() = expectedIds.size
    }

    private enum class SearchPhase(val id: String) {
        CANDIDATE("candidate-resolution"),
        SUMMARY("summary-materialization-ranking"),
        NOW_NEXT("published-now-next"),
    }

    private data class SearchScenarioResult(
        val candidate: CatalogDatabaseMeasurementSample,
        val summary: CatalogDatabaseMeasurementSample,
        val nowNext: CatalogDatabaseMeasurementSample,
        val candidateProbes: List<CatalogSearchCandidatePlanProbe>,
        val summaryCanonicalIds: List<String>,
        val nowNextCanonicalIds: List<String>,
    ) {
        fun sample(phase: SearchPhase): CatalogDatabaseMeasurementSample = when (phase) {
            SearchPhase.CANDIDATE -> candidate
            SearchPhase.SUMMARY -> summary
            SearchPhase.NOW_NEXT -> nowNext
        }
    }

    private data class SearchQueryTrace(
        val candidateProbes: List<CatalogSearchCandidatePlanProbe>,
        val summaryCanonicalIdSets: List<List<String>>,
        val nowNextCanonicalIdSets: List<List<String>>,
    )

    private fun Map<String, SearchScenarioResult>.toQueryTrace(): SearchQueryTrace {
        val scenarioResults = values.toList()
        return SearchQueryTrace(
            candidateProbes = scenarioResults.flatMap { it.candidateProbes }.distinct(),
            summaryCanonicalIdSets = scenarioResults.map { it.summaryCanonicalIds }.distinct(),
            nowNextCanonicalIdSets = scenarioResults.map { it.nowNextCanonicalIds }.distinct(),
        )
    }

    private class TimedSearchDataSource(
        private val delegate: ChannelSearchDataSource,
        private val nanoTime: () -> Long,
        private val captureQueryTrace: Boolean,
    ) : ChannelSearchDataSource {
        var candidateNanos = 0L
        var candidateRows = 0
        var summaryRows = 0
        val candidateProbes = mutableListOf<CatalogSearchCandidatePlanProbe>()
        var summaryCanonicalIds: List<String> = emptyList()

        override fun observeChanges(): Flow<Unit> = delegate.observeChanges()

        override suspend fun searchCandidates(
            profileId: String,
            ftsExpression: String,
            nowEpochMillis: Long,
            fetchLimit: Int,
            restrictToCanonicalIds: List<String>?,
        ): List<ChannelSearchCandidateRow> {
            if (captureQueryTrace) {
                candidateProbes += CatalogSearchCandidatePlanProbe(
                    ftsExpression = ftsExpression,
                    fetchLimit = fetchLimit,
                    restrictedCanonicalIds = restrictToCanonicalIds?.toList(),
                )
            }
            val startedAt = nanoTime()
            return delegate.searchCandidates(
                profileId = profileId,
                ftsExpression = ftsExpression,
                nowEpochMillis = nowEpochMillis,
                fetchLimit = fetchLimit,
                restrictToCanonicalIds = restrictToCanonicalIds,
            ).also { rows ->
                candidateNanos += (nanoTime() - startedAt).coerceAtLeast(1L)
                candidateRows += rows.size
            }
        }

        override suspend fun activeChannelSummaries(
            profileId: String,
            canonicalChannelIds: List<String>,
        ) = delegate.activeChannelSummaries(profileId, canonicalChannelIds).also { rows ->
            if (captureQueryTrace) summaryCanonicalIds = canonicalChannelIds.toList()
            summaryRows = rows.size
        }
    }

    private class TimedGuideRepository(
        private val delegate: EpgGuideRepository,
        private val nanoTime: () -> Long,
        private val captureQueryTrace: Boolean,
    ) : EpgGuideRepository {
        var nowNextNanos = 0L
        var nowNextRows = 0
        var nowNextCanonicalIds: List<String> = emptyList()

        override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> {
            val startedAt = nanoTime()
            return delegate.getNowNext(query).also { rows ->
                nowNextNanos = (nanoTime() - startedAt).coerceAtLeast(1L)
                nowNextRows = rows.size
                if (captureQueryTrace) nowNextCanonicalIds = query.canonicalChannelIds.toList()
            }
        }

        override fun observeDataChanges(): Flow<Unit> = delegate.observeDataChanges()
    }

    private class PreparedCatalogFixture private constructor(
        val workload: CatalogDatabaseMeasurementWorkload,
        val batches: List<List<StagedCatalogEntry>>,
        val sha256: String,
        val epgBatches: List<Pair<List<EpgChannelEntity>, List<EpgProgrammeEntity>>>,
    ) {
        companion object {
            fun create(workload: CatalogDatabaseMeasurementWorkload): PreparedCatalogFixture {
                val entries = List(workload.entryCount) { index ->
                    val suffix = index.toString().padStart(5, '0')
                    StagedCatalogEntry(
                        providerChannelId = "provider-$suffix",
                        providerKey = "tvg:measurement-$suffix",
                        rawName = "Synthetic Channel $suffix",
                        canonicalChannelId = "canonical-$suffix",
                        canonicalDisplayName = "Synthetic Channel $suffix",
                        streamVariantId = "variant-$suffix",
                        locator = "https://stream.example/live/$suffix.m3u8",
                        tvgId = "measurement-$suffix",
                        tvgName = "Synthetic Channel $suffix",
                        logoUrl = "https://images.example/channels/$suffix.png",
                        groupTitle = "Group ${index % GROUP_COUNT}",
                        channelNumber = (index + 1).toString(),
                        userAgent = if (index % USER_AGENT_INTERVAL == 0) {
                            "MuxTV-Measurement/${index % USER_AGENT_VARIANTS}"
                        } else {
                            null
                        },
                        referrer = if (index % REFERRER_INTERVAL == 0) {
                            "https://portal.example/measurement/${index % REFERRER_VARIANTS}"
                        } else {
                            null
                        },
                    )
                }
                val immutableBatches = entries.chunked(workload.batchSize).map { it.toList() }
                check(immutableBatches.size == workload.entryCount / workload.batchSize) {
                    "Catalog database fixture batch agreement failed."
                }
                val epgBatches = entries.indices.chunked(EPG_CHANNELS_PER_BATCH).map { indices ->
                    val channels = indices.map { index ->
                        val suffix = index.toString().padStart(5, '0')
                        EpgChannelEntity(
                            sourceId = EPG_SOURCE_ID,
                            revisionNumber = REVISION_NUMBER,
                            externalId = "epg-$suffix",
                            primaryDisplayName = "Synthetic Channel $suffix",
                            primaryLanguage = "en",
                            iconRef = null,
                        )
                    }
                    val programmes = indices.flatMap { index ->
                        val suffix = index.toString().padStart(5, '0')
                        val boundary = SEARCH_NOW_EPOCH_MILLIS + 60_000L + index
                        listOf(
                            programme(
                                sequence = index * 2L + 1,
                                externalChannelId = "epg-$suffix",
                                start = SEARCH_NOW_EPOCH_MILLIS - 60_000L,
                                stop = boundary,
                                title = "Programme CrossSignal$suffix",
                            ),
                            programme(
                                sequence = index * 2L + 2,
                                externalChannelId = "epg-$suffix",
                                start = boundary,
                                stop = boundary + 60_000L,
                                title = "Upcoming $suffix",
                            ),
                        )
                    }
                    channels to programmes
                }
                return PreparedCatalogFixture(
                    workload = workload,
                    batches = immutableBatches,
                    sha256 = CatalogDatabaseFixtureDigest.sha256(
                        entries = entries,
                        epgChannels = epgBatches.flatMap { it.first },
                        epgProgrammes = epgBatches.flatMap { it.second },
                    ),
                    epgBatches = epgBatches,
                )
            }

            private fun programme(
                sequence: Long,
                externalChannelId: String,
                start: Long,
                stop: Long,
                title: String,
            ) = EpgProgrammeEntity(
                sourceId = EPG_SOURCE_ID,
                revisionNumber = REVISION_NUMBER,
                sequenceNumber = sequence,
                externalChannelId = externalChannelId,
                startEpochMillis = start,
                stopEpochMillis = stop,
                primaryTitle = title,
                primaryLanguage = "en",
                subtitle = null,
                description = null,
                category = null,
                iconRef = null,
                episodeNumber = null,
                isNew = false,
            )
        }
    }

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val METHOD_VERSION = 4
        const val CACHE_STATE = "fresh-file-per-repetition-shared-scenarios"
        const val OPERATION_STAGE_BATCH = "stage-batch-250"
        const val OPERATION_STAGE_TOTAL = "stage-total-50k"
        const val OPERATION_ACTIVATE = "activate-50k"
        const val OPERATION_ACTIVE_CHANNELS = "active-channel-first-page"
        const val OPERATION_SOURCE_OVERVIEW = "source-overview-32"
        const val SEARCH_RESULT_LIMIT = 100
        const val SEARCH_NOW_EPOCH_MILLIS = 1_700_000_000_000L
        const val FIRST_PROGRAMME_BOUNDARY_EPOCH_MILLIS = SEARCH_NOW_EPOCH_MILLIS + 60_000L
        const val SOURCE_ID = "measurement-source"
        const val EPG_SOURCE_ID = "measurement-epg"
        const val PROFILE_ID = "measurement-profile"
        const val REVISION_NUMBER = 1L
        const val STARTED_AT_EPOCH_MILLIS = 1_000L
        const val ACTIVATED_AT_EPOCH_MILLIS = 2_000L
        const val GROUP_COUNT = 16
        const val EPG_CHANNELS_PER_BATCH = 250
        const val USER_AGENT_INTERVAL = 17
        const val USER_AGENT_VARIANTS = 4
        const val REFERRER_INTERVAL = 29
        const val REFERRER_VARIANTS = 3
        const val MAX_ENVIRONMENT_VALUE_LENGTH = 128
        const val MAX_FINGERPRINT_LENGTH = 256
        val LIMITATIONS = listOf(
            "Descriptive Android Room evidence for the exact recorded environment only.",
            "Each measured lifecycle uses one fresh database for stage, activate, active query and Search.",
            "EPG fixture preparation is outside Search query intervals but inside the same repetition database.",
            "Six read-only Search scenarios share the page cache only within one repetition.",
            "Trace and EXPLAIN QUERY PLAN capture are outside measured Search samples.",
            "Summary materialization/ranking subtracts timed DAO and guide phases from total Search time.",
            "Not a codec, startup, zapping, first-frame or physical weak-TV claim.",
        )
        val BROAD_EXPECTED_IDS = List(SEARCH_RESULT_LIMIT) { index ->
            "canonical-${index.toString().padStart(5, '0')}"
        }
        val SEARCH_SCENARIOS = listOf(
            SearchScenario("search-exact-number", "50000", listOf("canonical-49999"), false),
            SearchScenario(
                "search-selective-multi-token",
                "Synthetic 50000",
                listOf("canonical-49999"),
                false,
            ),
            SearchScenario("search-broad-multi-token", "Synthetic Channel", BROAD_EXPECTED_IDS, true),
            SearchScenario("search-broad-top-100", "Synthetic", BROAD_EXPECTED_IDS, true),
            SearchScenario("search-programme-title", "CrossSignal49999", listOf("canonical-49999"), false),
            SearchScenario(
                "search-cross-document",
                "Synthetic CrossSignal49999",
                listOf("canonical-49999"),
                false,
            ),
        )
    }
}
