package app.muxtv.database.measurement

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.room3.Room
import androidx.room3.RoomDatabase
import app.muxtv.database.MuxTvDatabase
import app.muxtv.database.ProfileEntity
import app.muxtv.database.RoomSourceRevisionStore
import app.muxtv.database.SourceDefinition
import app.muxtv.database.SourceRevisionActivationResult
import app.muxtv.database.SourceRevisionStatistics
import app.muxtv.database.StagedCatalogEntry
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal class CatalogDatabaseMeasurementRunner(
    context: Context,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val applicationContext = context.applicationContext
    private val databaseSequence = AtomicInteger()

    suspend fun run(spec: CatalogDatabaseMeasurementSpec): CatalogDatabaseMeasurementReport =
        withContext(Dispatchers.IO) {
            val fixture = PreparedCatalogFixture.create(spec.workload)
            val operations = listOf(
                measureOperation(
                    operationId = OPERATION_STAGE_BATCH,
                    expectedResultCount = spec.workload.batchSize,
                    workload = spec.workload,
                ) { iteration ->
                    measureStageBatch(iteration, fixture)
                },
                measureOperation(
                    operationId = OPERATION_STAGE_TOTAL,
                    expectedResultCount = spec.workload.entryCount,
                    workload = spec.workload,
                ) { iteration ->
                    measureStageTotal(iteration, fixture)
                },
                measureOperation(
                    operationId = OPERATION_ACTIVATE,
                    expectedResultCount = spec.workload.entryCount,
                    workload = spec.workload,
                ) { iteration ->
                    measureActivation(iteration, fixture)
                },
                measureOperation(
                    operationId = OPERATION_ACTIVE_CHANNELS,
                    expectedResultCount = spec.workload.firstPageLimit,
                    workload = spec.workload,
                ) { iteration ->
                    measureActiveChannels(iteration, fixture, spec.workload.firstPageLimit)
                },
                measureOperation(
                    operationId = OPERATION_SOURCE_OVERVIEW,
                    expectedResultCount = spec.workload.sourceOverviewCount,
                    workload = spec.workload,
                ) { iteration ->
                    measureSourceOverview(iteration, spec.workload.sourceOverviewCount)
                },
            )

            CatalogDatabaseMeasurementReport(
                schemaVersion = REPORT_SCHEMA_VERSION,
                methodVersion = METHOD_VERSION,
                thresholdApplied = false,
                sourceCommit = spec.sourceCommit,
                runnerLabel = spec.runnerLabel,
                cacheState = CACHE_STATE,
                workload = spec.workload,
                environment = captureEnvironment(),
                operations = operations,
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

        val samples = buildList(workload.measuredIterations) {
            repeat(workload.measuredIterations) { index ->
                val measured = block(index + 1)
                check(measured.resultCount == expectedResultCount) {
                    "Catalog database measurement result agreement failed."
                }
                add(measured)
            }
        }
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

    private suspend fun measureStageTotal(
        iteration: Int,
        fixture: PreparedCatalogFixture,
    ): CatalogDatabaseMeasurementSample = withFreshDatabase(OPERATION_STAGE_TOTAL, iteration) { handle ->
        handle.prepareRevision()
        val startedAt = nanoTime()
        fixture.batches.forEach { batch ->
            handle.store.stageBatch(SOURCE_ID, REVISION_NUMBER, batch)
        }
        val completedAt = nanoTime()
        val count = handle.database.sourceRevisionDao().countRevisionEntries(SOURCE_ID, REVISION_NUMBER)
        check(count == fixture.workload.entryCount) {
            "Catalog database staged total count agreement failed."
        }
        handle.sample(iteration, completedAt - startedAt, count)
    }

    private suspend fun measureActivation(
        iteration: Int,
        fixture: PreparedCatalogFixture,
    ): CatalogDatabaseMeasurementSample = withFreshDatabase(OPERATION_ACTIVATE, iteration) { handle ->
        handle.prepareRevision()
        fixture.batches.forEach { batch ->
            handle.store.stageBatch(SOURCE_ID, REVISION_NUMBER, batch)
        }
        val startedAt = nanoTime()
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
        val completedAt = nanoTime()
        val activated = activation as? SourceRevisionActivationResult.Activated
            ?: error("Catalog database activation agreement failed.")
        check(activated.entryCount == fixture.workload.entryCount) {
            "Catalog database activation entry count agreement failed."
        }
        handle.sample(iteration, completedAt - startedAt, activated.entryCount)
    }

    private suspend fun measureActiveChannels(
        iteration: Int,
        fixture: PreparedCatalogFixture,
        pageLimit: Int,
    ): CatalogDatabaseMeasurementSample = withFreshDatabase(OPERATION_ACTIVE_CHANNELS, iteration) { handle ->
        handle.database.profileDao().insert(
            ProfileEntity(
                id = PROFILE_ID,
                name = "Measurement profile",
                isPrimary = true,
            ),
        )
        handle.prepareRevision()
        fixture.batches.forEach { batch ->
            handle.store.stageBatch(SOURCE_ID, REVISION_NUMBER, batch)
        }
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
        check(activation is SourceRevisionActivationResult.Activated) {
            "Catalog database query preparation failed."
        }

        val startedAt = nanoTime()
        val rows = handle.database.playbackCatalogDao().observeActiveChannels(
            profileId = PROFILE_ID,
            searchPattern = null,
            favoritesOnly = false,
            limit = pageLimit,
        ).first()
        val completedAt = nanoTime()
        check(rows.size == pageLimit) {
            "Catalog database active channel query count agreement failed."
        }
        handle.sample(iteration, completedAt - startedAt, rows.size)
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
        val name = "muxtv-measurement-$operationId-$safeIteration-$sequence.db"
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
            supportedAbis = Build.SUPPORTED_ABIS.map(String::safeEnvironmentValue),
            lowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
        )
    }

    private fun String.safeEnvironmentValue(maxLength: Int = MAX_ENVIRONMENT_VALUE_LENGTH): String =
        trim().take(maxLength).ifBlank { "unknown" }

    private class DatabaseHandle(
        private val context: Context,
        private val databaseName: String,
        val database: MuxTvDatabase,
        val store: RoomSourceRevisionStore,
    ) {
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

        private fun File.safeLength(): Long = if (isFile) length().coerceAtLeast(0L) else 0L
    }

    private class PreparedCatalogFixture private constructor(
        val workload: CatalogDatabaseMeasurementWorkload,
        val entries: List<StagedCatalogEntry>,
        val batches: List<List<StagedCatalogEntry>>,
        val sha256: String,
    ) {
        companion object {
            fun create(workload: CatalogDatabaseMeasurementWorkload): PreparedCatalogFixture {
                val digest = MessageDigest.getInstance("SHA-256")
                val entries = List(workload.entryCount) { index ->
                    val suffix = index.toString().padStart(5, '0')
                    val entry = StagedCatalogEntry(
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
                    digest.update(entry.providerChannelId.toByteArray(StandardCharsets.UTF_8))
                    digest.update(0)
                    digest.update(entry.canonicalChannelId.toByteArray(StandardCharsets.UTF_8))
                    digest.update(0)
                    digest.update(entry.streamVariantId.toByteArray(StandardCharsets.UTF_8))
                    digest.update(0)
                    entry
                }
                val immutableEntries = entries.toList()
                val immutableBatches = immutableEntries.chunked(workload.batchSize).map(List<StagedCatalogEntry>::toList)
                check(immutableBatches.size == workload.entryCount / workload.batchSize) {
                    "Catalog database fixture batch agreement failed."
                }
                return PreparedCatalogFixture(
                    workload = workload,
                    entries = immutableEntries,
                    batches = immutableBatches,
                    sha256 = digest.digest().toHex(),
                )
            }

            private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
                String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
            }
        }
    }

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val METHOD_VERSION = 1
        const val CACHE_STATE = "fresh-file-per-sample"
        const val OPERATION_STAGE_BATCH = "stage-batch-250"
        const val OPERATION_STAGE_TOTAL = "stage-total-10k"
        const val OPERATION_ACTIVATE = "activate-10k"
        const val OPERATION_ACTIVE_CHANNELS = "active-channel-first-page"
        const val OPERATION_SOURCE_OVERVIEW = "source-overview-32"
        const val SOURCE_ID = "measurement-source"
        const val PROFILE_ID = "measurement-profile"
        const val REVISION_NUMBER = 1L
        const val STARTED_AT_EPOCH_MILLIS = 1_000L
        const val ACTIVATED_AT_EPOCH_MILLIS = 2_000L
        const val GROUP_COUNT = 16
        const val USER_AGENT_INTERVAL = 17
        const val USER_AGENT_VARIANTS = 4
        const val REFERRER_INTERVAL = 29
        const val REFERRER_VARIANTS = 3
        const val MAX_ENVIRONMENT_VALUE_LENGTH = 128
        const val MAX_FINGERPRINT_LENGTH = 256
        val LIMITATIONS = listOf(
            "Descriptive Android Room evidence for the exact recorded environment only.",
            "Database creation and prerequisite seeding are outside measured intervals.",
            "Not a codec, startup, zapping, first-frame or physical weak-TV claim.",
        )
    }
}
