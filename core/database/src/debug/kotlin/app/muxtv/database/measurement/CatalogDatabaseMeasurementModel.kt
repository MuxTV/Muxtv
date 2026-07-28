package app.muxtv.database.measurement

import kotlin.math.ceil

private val SOURCE_COMMIT_PATTERN = Regex("[0-9a-f]{40}")
private val RUNNER_LABEL_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val OPERATION_ID_PATTERN = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
private const val ZERO_SHA_256 =
    "0000000000000000000000000000000000000000000000000000000000000000"

internal data class CatalogDatabaseMeasurementSummary(
    val sampleCount: Int,
    val minimum: Long,
    val p50: Long,
    val p90: Long,
    val p95: Long,
    val maximum: Long,
) {
    init {
        require(sampleCount > 0)
        require(minimum >= 0L)
        require(minimum <= p50)
        require(p50 <= p90)
        require(p90 <= p95)
        require(p95 <= maximum)
    }
}

internal object CatalogDatabaseMeasurementStatistics {
    fun summarize(values: List<Long>): CatalogDatabaseMeasurementSummary {
        require(values.isNotEmpty())
        require(values.all { it >= 0L })
        val sorted = values.sorted()
        return CatalogDatabaseMeasurementSummary(
            sampleCount = sorted.size,
            minimum = sorted.first(),
            p50 = sorted.nearestRank(50),
            p90 = sorted.nearestRank(90),
            p95 = sorted.nearestRank(95),
            maximum = sorted.last(),
        )
    }

    private fun List<Long>.nearestRank(percentile: Int): Long {
        require(percentile in 1..100)
        val rank = ceil(percentile / 100.0 * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }
}

internal data class CatalogDatabaseMeasurementWorkload(
    val entryCount: Int,
    val batchSize: Int,
    val firstPageLimit: Int,
    val sourceOverviewCount: Int,
    val warmupIterations: Int,
    val measuredIterations: Int,
) {
    init {
        require(entryCount in 250..50_000)
        require(batchSize == 250)
        require(entryCount % batchSize == 0)
        require(firstPageLimit in 1..500)
        require(firstPageLimit <= entryCount)
        require(sourceOverviewCount in 1..100)
        require(warmupIterations in 0..20)
        require(measuredIterations in 5..100)
    }
}

internal data class CatalogDatabaseMeasurementSpec(
    val sourceCommit: String,
    val runnerLabel: String,
    val workload: CatalogDatabaseMeasurementWorkload,
) {
    init {
        require(sourceCommit.matches(SOURCE_COMMIT_PATTERN))
        require(runnerLabel.matches(RUNNER_LABEL_PATTERN))
    }
}

internal class CatalogDatabaseMeasurementEnvironment(
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val apiLevel: Int,
    supportedAbis: List<String>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val availableProcessors: Int,
) {
    val supportedAbis: List<String> = supportedAbis.toList()

    init {
        require(manufacturer.isNotBlank())
        require(model.isNotBlank())
        require(fingerprint.isNotBlank())
        require(apiLevel >= 26)
        require(this.supportedAbis.isNotEmpty())
        require(this.supportedAbis.all(String::isNotBlank))
        require(memoryClassMb > 0)
        require(availableProcessors > 0)
    }
}

internal data class CatalogDatabaseMeasurementSample(
    val iteration: Int,
    val wallTimeNanos: Long,
    val resultCount: Int,
    val databaseBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
) {
    init {
        require(iteration > 0)
        require(wallTimeNanos > 0L)
        require(resultCount >= 0)
        require(databaseBytes >= 0L)
        require(walBytes >= 0L)
        require(shmBytes >= 0L)
    }
}

internal class CatalogDatabaseOperationReport(
    val operationId: String,
    val expectedResultCount: Int,
    samples: List<CatalogDatabaseMeasurementSample>,
    val wallTimeNanos: CatalogDatabaseMeasurementSummary,
    val databaseBytes: CatalogDatabaseMeasurementSummary,
    val walBytes: CatalogDatabaseMeasurementSummary,
    val shmBytes: CatalogDatabaseMeasurementSummary,
) {
    val samples: List<CatalogDatabaseMeasurementSample> = samples.toList()

    init {
        require(operationId.matches(OPERATION_ID_PATTERN))
        require(expectedResultCount > 0)
        require(this.samples.isNotEmpty())
        require(this.samples.map(CatalogDatabaseMeasurementSample::iteration) == (1..this.samples.size).toList())
        require(this.samples.all { it.resultCount == expectedResultCount })
        require(wallTimeNanos.sampleCount == this.samples.size)
        require(databaseBytes.sampleCount == this.samples.size)
        require(walBytes.sampleCount == this.samples.size)
        require(shmBytes.sampleCount == this.samples.size)
    }

    override fun toString(): String =
        "CatalogDatabaseOperationReport(" +
            "operationId=$operationId, expectedResultCount=$expectedResultCount, " +
            "sampleCount=${samples.size})"
}

internal data class CatalogDatabaseFixtureIdentity(
    val entryCount: Int,
    val sha256: String,
) {
    init {
        require(entryCount > 0)
        require(sha256.matches(SHA_256_PATTERN))
    }
}

internal class CatalogDatabaseMeasurementReport(
    val schemaVersion: Int,
    val methodVersion: Int,
    val thresholdApplied: Boolean,
    val sourceCommit: String,
    val runnerLabel: String,
    val cacheState: String,
    val workload: CatalogDatabaseMeasurementWorkload,
    val environment: CatalogDatabaseMeasurementEnvironment,
    operations: List<CatalogDatabaseOperationReport>,
    val failureCount: Int,
    limitations: List<String>,
    val fixture: CatalogDatabaseFixtureIdentity = CatalogDatabaseFixtureIdentity(
        entryCount = workload.entryCount,
        sha256 = ZERO_SHA_256,
    ),
) {
    val operations: List<CatalogDatabaseOperationReport> = operations.toList()
    val limitations: List<String> = limitations.toList()

    init {
        require(schemaVersion > 0)
        require(methodVersion > 0)
        require(!thresholdApplied)
        require(sourceCommit.matches(SOURCE_COMMIT_PATTERN))
        require(runnerLabel.matches(RUNNER_LABEL_PATTERN))
        require(cacheState == "fresh-file-per-sample")
        require(fixture.entryCount == workload.entryCount)
        require(this.operations.isNotEmpty())
        require(this.operations.map(CatalogDatabaseOperationReport::operationId).distinct().size == this.operations.size)
        require(this.operations.all { it.samples.size == workload.measuredIterations })
        require(failureCount >= 0)
        require(this.limitations.isNotEmpty())
        require(this.limitations.all { it.isNotBlank() && it.length <= 256 })
    }

    override fun toString(): String =
        "CatalogDatabaseMeasurementReport(" +
            "schemaVersion=$schemaVersion, methodVersion=$methodVersion, " +
            "thresholdApplied=$thresholdApplied, sourceCommit=$sourceCommit, " +
            "runnerLabel=$runnerLabel, cacheState=$cacheState, " +
            "fixtureEntryCount=${fixture.entryCount}, operationCount=${operations.size}, " +
            "failureCount=$failureCount)"
}
