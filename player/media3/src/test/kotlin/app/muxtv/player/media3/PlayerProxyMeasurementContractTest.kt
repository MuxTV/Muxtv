package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Test

class PlayerProxyMeasurementContractTest {
    @Test
    fun `nearest-rank summary retains sample count`() {
        val summary = PlayerProxyMeasurementStatistics.summarize(
            listOf(100L, 10L, 50L, 20L, 90L, 30L, 80L, 40L, 70L, 60L),
        )

        assertThat(summary.sampleCount).isEqualTo(10)
        assertThat(summary.minimum).isEqualTo(10L)
        assertThat(summary.p50).isEqualTo(50L)
        assertThat(summary.p90).isEqualTo(90L)
        assertThat(summary.p95).isEqualTo(100L)
        assertThat(summary.maximum).isEqualTo(100L)
    }

    @Test
    fun `report snapshots mutable samples and operations`() {
        val mutableSamples = mutableListOf(
            sample(index = 1, batchNanos = 10_000L),
            sample(index = 2, batchNanos = 20_000L),
            sample(index = 3, batchNanos = 30_000L),
            sample(index = 4, batchNanos = 40_000L),
            sample(index = 5, batchNanos = 50_000L),
        )
        val mutableOperations = mutableListOf(operation("request-construct", mutableSamples))
        val report = report(mutableOperations)

        mutableSamples.clear()
        mutableOperations.clear()

        assertThat(report.operations).hasSize(1)
        assertThat(report.operations.single().samples).hasSize(5)
    }

    @Test
    fun `canonical JSON is threshold free LF only and payload redacted`() {
        val output = ByteArrayOutputStream()
        val report = report(
            listOf(
                operation(
                    id = "request-construct",
                    samples = listOf(
                        sample(1, 10_000L),
                        sample(2, 20_000L),
                        sample(3, 30_000L),
                        sample(4, 40_000L),
                        sample(5, 50_000L),
                    ),
                ),
            ),
        )

        PlayerProxyMeasurementJsonWriter.write(report, output)

        val json = output.toString(Charsets.UTF_8)
        assertThat(json).startsWith("{\n  \"schemaVersion\": 1,\n  \"methodVersion\": 1,")
        assertThat(json).endsWith("\n")
        assertThat(json).doesNotContain("\r")
        assertThat(json).contains("\"buildMode\": \"debug-instrumentation\"")
        assertThat(json).contains("\"thresholdApplied\": false")
        assertThat(json).contains("\"rawSamples\": [")
        assertThat(json).contains("\"requestProfileSha256\": \"${"0".repeat(64)}\"")
        assertThat(json).doesNotContain("stream.example")
        assertThat(json).doesNotContain("Secret Agent")
        assertThat(json).doesNotContain("channel-secret")
        assertThat(report.toString()).doesNotContain("stream.example")
    }

    private fun report(
        operations: List<PlayerProxyOperationReport>,
    ): PlayerProxyMeasurementReport = PlayerProxyMeasurementReport(
        schemaVersion = 1,
        methodVersion = 1,
        buildMode = "debug-instrumentation",
        thresholdApplied = false,
        sourceCommit = "0123456789abcdef0123456789abcdef01234567",
        runnerLabel = "unit-android-x64",
        workload = PlayerProxyMeasurementWorkload(
            warmupSamples = 2,
            measuredSamples = 5,
            operationsPerSample = 1_000,
        ),
        requestProfileSha256 = "0".repeat(64),
        environment = PlayerProxyMeasurementEnvironment(
            manufacturer = "Android",
            model = "Synthetic",
            fingerprint = "synthetic/fingerprint",
            apiLevel = 36,
            supportedAbis = listOf("x86_64"),
            lowRamDevice = false,
            memoryClassMb = 192,
            availableProcessors = 2,
        ),
        operations = operations,
        failureCount = 0,
        limitations = listOf(
            "Control-plane proxy evidence only.",
            "Not a decoder, network, zapping or first-frame claim.",
        ),
    )

    private fun operation(
        id: String,
        samples: List<PlayerProxyMeasurementSample>,
    ): PlayerProxyOperationReport = PlayerProxyOperationReport(
        operationId = id,
        expectedSuccessfulResultCount = 1_000,
        samples = samples,
        batchWallTimeNanos = PlayerProxyMeasurementStatistics.summarize(
            samples.map(PlayerProxyMeasurementSample::batchWallTimeNanos),
        ),
        normalizedNanosPerOperation = PlayerProxyMeasurementStatistics.summarize(
            samples.map(PlayerProxyMeasurementSample::normalizedNanosPerOperation),
        ),
    )

    private fun sample(
        index: Int,
        batchNanos: Long,
    ): PlayerProxyMeasurementSample = PlayerProxyMeasurementSample(
        sampleIndex = index,
        batchWallTimeNanos = batchNanos,
        operationCount = 1_000,
        normalizedNanosPerOperation = batchNanos / 1_000,
        successfulResultCount = 1_000,
    )
}
