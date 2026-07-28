package app.muxtv.database.measurement

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test

class CatalogDatabaseMeasurementStatisticsTest {
    @Test
    fun `nearest-rank summary retains every sample`() {
        val summary = CatalogDatabaseMeasurementStatistics.summarize(
            listOf(90L, 10L, 50L, 20L, 80L, 30L, 70L, 40L, 60L, 100L),
        )

        assertThat(summary.sampleCount).isEqualTo(10)
        assertThat(summary.minimum).isEqualTo(10L)
        assertThat(summary.p50).isEqualTo(50L)
        assertThat(summary.p90).isEqualTo(90L)
        assertThat(summary.p95).isEqualTo(100L)
        assertThat(summary.maximum).isEqualTo(100L)
    }

    @Test
    fun `report snapshots mutable operation and sample inputs`() {
        val mutableSamples = mutableListOf(
            sample(iteration = 1, wallTimeNanos = 10L),
            sample(iteration = 2, wallTimeNanos = 20L),
            sample(iteration = 3, wallTimeNanos = 30L),
            sample(iteration = 4, wallTimeNanos = 40L),
            sample(iteration = 5, wallTimeNanos = 50L),
        )
        val mutableOperations = mutableListOf(operation("stage-batch-250", mutableSamples))
        val report = report(mutableOperations)

        mutableSamples.clear()
        mutableOperations.clear()

        assertThat(report.operations).hasSize(1)
        assertThat(report.operations.single().samples).hasSize(5)
    }

    @Test
    fun `canonical JSON is LF only threshold free and payload redacted`() {
        val output = ByteArrayOutputStream()
        val report = report(
            operations = listOf(
                operation(
                    id = "stage-batch-250",
                    samples = listOf(
                        sample(iteration = 1, wallTimeNanos = 10L),
                        sample(iteration = 2, wallTimeNanos = 20L),
                        sample(iteration = 3, wallTimeNanos = 30L),
                        sample(iteration = 4, wallTimeNanos = 40L),
                        sample(iteration = 5, wallTimeNanos = 50L),
                    ),
                ),
            ),
        )

        CatalogDatabaseMeasurementJsonWriter.write(report, output)

        val json = output.toString(Charsets.UTF_8)
        assertThat(json).startsWith("{\n  \"schemaVersion\": 1,\n  \"methodVersion\": 1,")
        assertThat(json).endsWith("\n")
        assertThat(json).doesNotContain("\r")
        assertThat(json).contains("\"thresholdApplied\": false")
        assertThat(json).contains("\"rawSamples\": [")
        assertThat(json).contains("\"cacheState\": \"fresh-file-per-sample\"")
        assertThat(json).contains("\"fixture\": {")
        assertThat(json).contains("\"sha256\": \"0000000000000000000000000000000000000000000000000000000000000000\"")
        assertThat(json).doesNotContain("stream.example")
        assertThat(json).doesNotContain("Synthetic Channel")
        assertThat(json).doesNotContain("locator")
        assertThat(report.toString()).doesNotContain("stream.example")
    }

    private fun report(
        operations: List<CatalogDatabaseOperationReport>,
    ): CatalogDatabaseMeasurementReport = CatalogDatabaseMeasurementReport(
        schemaVersion = 1,
        methodVersion = 1,
        thresholdApplied = false,
        sourceCommit = SOURCE_COMMIT,
        runnerLabel = "unit-android-x64",
        cacheState = "fresh-file-per-sample",
        workload = CatalogDatabaseMeasurementWorkload(
            entryCount = 10_000,
            batchSize = 250,
            firstPageLimit = 100,
            sourceOverviewCount = 32,
            warmupIterations = 1,
            measuredIterations = 5,
        ),
        environment = CatalogDatabaseMeasurementEnvironment(
            manufacturer = "Android",
            model = "Synthetic",
            fingerprint = "synthetic/fingerprint",
            apiLevel = 36,
            supportedAbis = listOf("x86_64"),
            lowRamDevice = false,
            memoryClassMb = 2048,
            availableProcessors = 8,
        ),
        operations = operations,
        failureCount = 0,
        limitations = listOf(
            "Descriptive Android Room evidence only.",
            "Not a codec, startup, zapping or physical weak-TV claim.",
        ),
    )

    private fun operation(
        id: String,
        samples: List<CatalogDatabaseMeasurementSample>,
    ): CatalogDatabaseOperationReport = CatalogDatabaseOperationReport(
        operationId = id,
        expectedResultCount = 250,
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

    private fun sample(
        iteration: Int,
        wallTimeNanos: Long,
    ): CatalogDatabaseMeasurementSample = CatalogDatabaseMeasurementSample(
        iteration = iteration,
        wallTimeNanos = wallTimeNanos,
        resultCount = 250,
        databaseBytes = 4_096L,
        walBytes = 8_192L,
        shmBytes = 32_768L,
    )

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
