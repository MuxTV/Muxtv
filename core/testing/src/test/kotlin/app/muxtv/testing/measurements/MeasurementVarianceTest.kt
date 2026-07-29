package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementVarianceTest {
    @Test
    fun `environment fingerprint is deterministic across map and ABI order`() {
        val first = identity(
            supportedAbis = listOf("x86_64", "arm64-v8a"),
            workload = linkedMapOf("entries" to "10000", "batch" to "250"),
        )
        val second = identity(
            supportedAbis = listOf("arm64-v8a", "x86_64"),
            workload = linkedMapOf("batch" to "250", "entries" to "10000"),
        )

        assertThat(first.fingerprintSha256).isEqualTo(second.fingerprintSha256)
        assertThat(first.fingerprintSha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun `environment fingerprint changes for comparable boundary changes`() {
        val baseline = identity()

        assertThat(identity(apiLevel = 28).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(configuredRamMb = 1024).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(workload = mapOf("entries" to "50000")).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(fixtureSha256 = "1".repeat(64)).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
    }

    @Test
    fun `analyzer calculates cross-run distributions from retained raw samples`() {
        val fingerprint = identity().fingerprintSha256
        val report = MeasurementVarianceAnalyzer.analyze(
            family = "catalog-database",
            identityFingerprintSha256 = fingerprint,
            runs = listOf(
                run("current-01", fingerprint, listOf(10L, 20L, 30L, 40L, 50L)),
                run("current-02", fingerprint, listOf(20L, 30L, 40L, 50L, 60L)),
                run("current-03", fingerprint, listOf(30L, 40L, 50L, 60L, 70L)),
            ),
        )

        val operation = report.operations.single()
        assertThat(report.seriesCount).isEqualTo(3)
        assertThat(operation.operationId).isEqualTo("stage-total-10k")
        assertThat(operation.totalRawSampleCount).isEqualTo(15)
        assertThat(operation.perRunMedians).containsExactly(30L, 40L, 50L).inOrder()
        assertThat(operation.medianOfRunMedians).isEqualTo(40L)
        assertThat(operation.minimumRunMedian).isEqualTo(30L)
        assertThat(operation.maximumRunMedian).isEqualTo(50L)
        assertThat(operation.absoluteRange).isEqualTo(20L)
        assertThat(operation.percentageRangeBasisPoints).isEqualTo(5_000L)
        assertThat(operation.meanRunMedian).isWithin(0.0001).of(40.0)
        assertThat(operation.sampleStandardDeviation).isWithin(0.0001).of(10.0)
        assertThat(operation.coefficientOfVariationBasisPoints).isEqualTo(2_500L)
        assertThat(operation.worstObservedP95).isEqualTo(70L)
    }

    @Test
    fun `analyzer rejects incompatible failed thresholded or duplicate series`() {
        val firstFingerprint = identity().fingerprintSha256
        val secondFingerprint = identity(apiLevel = 28).fingerprintSha256

        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                family = "catalog-database",
                identityFingerprintSha256 = firstFingerprint,
                runs = listOf(
                    run("duplicate", firstFingerprint, listOf(1L, 2L, 3L)),
                    run("duplicate", firstFingerprint, listOf(1L, 2L, 3L)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                family = "catalog-database",
                identityFingerprintSha256 = firstFingerprint,
                runs = listOf(
                    run("one", firstFingerprint, listOf(1L, 2L, 3L)),
                    run("two", secondFingerprint, listOf(1L, 2L, 3L)),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                family = "catalog-database",
                identityFingerprintSha256 = firstFingerprint,
                runs = listOf(
                    run("one", firstFingerprint, listOf(1L, 2L, 3L)),
                    run("two", firstFingerprint, listOf(1L, 2L, 3L), thresholdApplied = true),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                family = "catalog-database",
                identityFingerprintSha256 = firstFingerprint,
                runs = listOf(
                    run("one", firstFingerprint, listOf(1L, 2L, 3L)),
                    run("two", firstFingerprint, listOf(1L, 2L, 3L), failureCount = 1),
                ),
            )
        }
    }

    @Test
    fun `canonical report is LF only threshold free and path redacted`() {
        val fingerprint = identity().fingerprintSha256
        val report = MeasurementVarianceAnalyzer.analyze(
            family = "player-proxy",
            identityFingerprintSha256 = fingerprint,
            runs = listOf(
                run("series-01", fingerprint, listOf(1L, 2L, 3L, 4L, 5L)),
                run("series-02", fingerprint, listOf(2L, 3L, 4L, 5L, 6L)),
            ),
        )
        val output = ByteArrayOutputStream()

        MeasurementVarianceJsonWriter.write(report, output)

        val json = output.toString(Charsets.UTF_8)
        assertThat(json).startsWith("{\n  \"schemaVersion\": 1,")
        assertThat(json).endsWith("\n")
        assertThat(json).doesNotContain("\r")
        assertThat(json).contains("\"thresholdApplied\": false")
        assertThat(json).contains("\"perRunMedians\": [")
        assertThat(json).doesNotContain("C:\\")
        assertThat(json).doesNotContain("/home/")
        assertThat(report.toString()).doesNotContain("series-01")
    }

    private fun identity(
        apiLevel: Int = 36,
        configuredRamMb: Int = 2048,
        supportedAbis: List<String> = listOf("x86_64"),
        workload: Map<String, String> = mapOf("entries" to "10000"),
        fixtureSha256: String = "0".repeat(64),
    ): MeasurementComparisonIdentity = MeasurementComparisonIdentity(
        family = "catalog-database",
        schemaVersion = 1,
        methodVersion = 1,
        fixtureSha256 = fixtureSha256,
        apiLevel = apiLevel,
        systemImage = "system-images;android-$apiLevel;android-tv;x86_64",
        supportedAbis = supportedAbis,
        configuredRamMb = configuredRamMb,
        cpuCores = 2,
        lowRamDevice = configuredRamMb <= 1024,
        memoryClassMb = if (configuredRamMb <= 1024) 128 else 192,
        buildMode = "debug-instrumentation",
        workload = workload,
    )

    private fun run(
        repetitionId: String,
        fingerprint: String,
        samples: List<Long>,
        thresholdApplied: Boolean = false,
        failureCount: Int = 0,
    ): MeasurementSeriesRun = MeasurementSeriesRun(
        repetitionId = repetitionId,
        identityFingerprintSha256 = fingerprint,
        thresholdApplied = thresholdApplied,
        failureCount = failureCount,
        operations = mapOf("stage-total-10k" to samples),
    )
}
