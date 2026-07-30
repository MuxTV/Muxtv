package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementVarianceTest {
    @Test
    fun `comparison fingerprint is deterministic across map and ABI order`() {
        val first = identity(
            supportedAbis = listOf("x86_64", "arm64-v8a"),
            runtimeIdentity = linkedMapOf(
                "jvm-version" to "26.0.1",
                "os-version" to "10.0.19045",
            ),
            workload = linkedMapOf("entries" to "10000", "batch" to "250"),
        )
        val second = identity(
            supportedAbis = listOf("arm64-v8a", "x86_64"),
            runtimeIdentity = linkedMapOf(
                "os-version" to "10.0.19045",
                "jvm-version" to "26.0.1",
            ),
            workload = linkedMapOf("batch" to "250", "entries" to "10000"),
        )

        assertThat(first.fingerprintSha256).isEqualTo(second.fingerprintSha256)
        assertThat(first.fingerprintSha256).matches("[0-9a-f]{64}")
    }

    @Test
    fun `comparison fingerprint changes for every comparability boundary`() {
        val baseline = identity()

        assertThat(identity(sourceCommit = "1".repeat(40)).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(runnerLabel = "second-runner").fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(apiLevel = 28).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(configuredRamMb = 1024).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(runtimeIdentity = mapOf("host-cpu" to "second-host")).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(workload = mapOf("entries" to "50000")).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
        assertThat(identity(fixtureSha256 = "1".repeat(64)).fingerprintSha256)
            .isNotEqualTo(baseline.fingerprintSha256)
    }

    @Test
    fun `comparison identity owns unmodifiable normalized snapshots`() {
        val supportedAbis = mutableListOf("x86_64", "arm64-v8a")
        val runtimeIdentity = linkedMapOf("host-cpu" to "self-hosted-x64")
        val workload = linkedMapOf("entries" to "10000")
        val comparison = identity(
            supportedAbis = supportedAbis,
            runtimeIdentity = runtimeIdentity,
            workload = workload,
        )
        val fingerprint = comparison.fingerprintSha256

        supportedAbis.clear()
        runtimeIdentity.clear()
        workload.clear()

        assertThat(comparison.supportedAbis).containsExactly("arm64-v8a", "x86_64").inOrder()
        assertThat(comparison.runtimeIdentity).containsExactly("host-cpu", "self-hosted-x64")
        assertThat(comparison.workload).containsExactly("entries", "10000")
        assertThat(comparison.fingerprintSha256).isEqualTo(fingerprint)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (comparison.supportedAbis as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (comparison.runtimeIdentity as MutableMap<String, String>)["changed"] = "value"
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (comparison.workload as MutableMap<String, String>)["changed"] = "value"
        }
    }

    @Test
    fun `series run owns unmodifiable raw sample snapshots`() {
        val comparison = identity()
        val samples = mutableListOf(10L, 20L, 30L)
        val operations = linkedMapOf("stage-total-10k" to samples)
        val series = run(
            repetitionId = "current-01",
            comparison = comparison,
            samples = samples,
            operations = operations,
        )

        samples.clear()
        operations.clear()

        assertThat(series.operations.getValue("stage-total-10k"))
            .containsExactly(10L, 20L, 30L).inOrder()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (series.operations.getValue("stage-total-10k") as MutableList<Long>).add(40L)
        }
    }

    @Test
    fun `analyzer calculates cross-run distributions from retained raw samples`() {
        val comparison = identity()
        val report = MeasurementVarianceAnalyzer.analyze(
            identity = comparison,
            runs = listOf(
                run("current-01", comparison, listOf(10L, 20L, 30L, 40L, 50L)),
                run("current-02", comparison, listOf(20L, 30L, 40L, 50L, 60L)),
                run("current-03", comparison, listOf(30L, 40L, 50L, 60L, 70L)),
            ),
        )

        val operation = report.operations.single()
        assertThat(report.family).isEqualTo(comparison.family)
        assertThat(report.sourceCommit).isEqualTo(comparison.sourceCommit)
        assertThat(report.runnerLabel).isEqualTo(comparison.runnerLabel)
        assertThat(report.identityFingerprintSha256).isEqualTo(comparison.fingerprintSha256)
        assertThat(report.seriesCount).isEqualTo(3)
        assertThat(report.inputReportSha256s).hasSize(3)
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
    fun `analyzer rejects incompatible failed thresholded duplicate or unbounded series`() {
        val firstIdentity = identity()
        val secondIdentity = identity(apiLevel = 28)
        val first = run("one", firstIdentity, listOf(1L, 2L, 3L))

        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                identity = firstIdentity,
                runs = listOf(first, first.copy(repetitionId = "one")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                identity = firstIdentity,
                runs = listOf(
                    first,
                    run(
                        repetitionId = "two",
                        comparison = firstIdentity,
                        samples = listOf(1L, 2L, 3L),
                        sourceReportSha256 = first.sourceReportSha256,
                    ),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                identity = firstIdentity,
                runs = listOf(first, run("two", secondIdentity, listOf(1L, 2L, 3L))),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                identity = firstIdentity,
                runs = listOf(
                    first,
                    run("two", firstIdentity, listOf(1L, 2L, 3L), thresholdApplied = true),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                identity = firstIdentity,
                runs = listOf(
                    first,
                    run("two", firstIdentity, listOf(1L, 2L, 3L), failureCount = 1),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MeasurementVarianceAnalyzer.analyze(
                identity = firstIdentity,
                runs = List(101) { index ->
                    run("series-${index.toString().padStart(3, '0')}", firstIdentity, listOf(1L))
                },
            )
        }
    }

    @Test
    fun `variance report owns unmodifiable audit and operation snapshots`() {
        val comparison = identity()
        val report = MeasurementVarianceAnalyzer.analyze(
            identity = comparison,
            runs = listOf(
                run("series-01", comparison, listOf(1L, 2L, 3L)),
                run("series-02", comparison, listOf(2L, 3L, 4L)),
            ),
        )

        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (report.inputReportSha256s as MutableList<String>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (report.operations as MutableList<MeasurementVarianceOperationReport>).clear()
        }
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (report.operations.single().perRunMedians as MutableList<Long>).clear()
        }
    }

    @Test
    fun `canonical report retains audit hashes and redacts repetition and path values`() {
        val comparison = identity()
        val report = MeasurementVarianceAnalyzer.analyze(
            identity = comparison,
            runs = listOf(
                run("series-01", comparison, listOf(1L, 2L, 3L, 4L, 5L)),
                run("series-02", comparison, listOf(2L, 3L, 4L, 5L, 6L)),
            ),
        )
        val output = ByteArrayOutputStream()

        MeasurementVarianceJsonWriter.write(report, output)

        val json = output.toString(Charsets.UTF_8)
        assertThat(json).startsWith("{\n  \"schemaVersion\": 1,")
        assertThat(json).endsWith("\n")
        assertThat(json).doesNotContain("\r")
        assertThat(json).contains("\"thresholdApplied\": false")
        assertThat(json).contains("\"sourceCommit\": \"${comparison.sourceCommit}\"")
        assertThat(json).contains("\"runnerLabel\": \"${comparison.runnerLabel}\"")
        assertThat(json).contains("\"inputReportSha256s\": [")
        assertThat(json).contains("\"perRunMedians\": [")
        assertThat(json).doesNotContain("series-01")
        assertThat(json).doesNotContain("series-02")
        assertThat(json).doesNotContain("C:\\")
        assertThat(json).doesNotContain("/home/")
        assertThat(report.toString()).doesNotContain("series-01")
    }

    private fun identity(
        sourceCommit: String = "0123456789abcdef0123456789abcdef01234567",
        runnerLabel: String = "self-hosted-windows-x64",
        apiLevel: Int = 36,
        configuredRamMb: Int = 2048,
        supportedAbis: List<String> = listOf("x86_64"),
        runtimeIdentity: Map<String, String> = mapOf(
            "host-cpu" to "self-hosted-x64",
            "os-version" to "10.0.19045",
        ),
        workload: Map<String, String> = mapOf("entries" to "10000"),
        fixtureSha256: String = "0".repeat(64),
    ): MeasurementComparisonIdentity = MeasurementComparisonIdentity(
        family = "catalog-database",
        schemaVersion = 1,
        methodVersion = 1,
        sourceCommit = sourceCommit,
        fixtureSha256 = fixtureSha256,
        runnerLabel = runnerLabel,
        apiLevel = apiLevel,
        systemImage = "system-images;android-$apiLevel;android-tv;x86_64",
        supportedAbis = supportedAbis,
        configuredRamMb = configuredRamMb,
        cpuCores = 2,
        lowRamDevice = configuredRamMb <= 1024,
        memoryClassMb = if (configuredRamMb <= 1024) 128 else 192,
        buildMode = "debug-instrumentation",
        runtimeIdentity = runtimeIdentity,
        workload = workload,
    )

    private fun run(
        repetitionId: String,
        comparison: MeasurementComparisonIdentity,
        samples: List<Long>,
        operations: Map<String, List<Long>> = mapOf("stage-total-10k" to samples),
        sourceReportSha256: String = sha256(repetitionId),
        thresholdApplied: Boolean = false,
        failureCount: Int = 0,
    ): MeasurementSeriesRun = MeasurementSeriesRun(
        repetitionId = repetitionId,
        sourceReportSha256 = sourceReportSha256,
        identityFingerprintSha256 = comparison.fingerprintSha256,
        thresholdApplied = thresholdApplied,
        failureCount = failureCount,
        operations = operations,
    )

    private fun MeasurementSeriesRun.copy(
        repetitionId: String = this.repetitionId,
    ): MeasurementSeriesRun = MeasurementSeriesRun(
        repetitionId = repetitionId,
        sourceReportSha256 = sourceReportSha256,
        identityFingerprintSha256 = identityFingerprintSha256,
        thresholdApplied = thresholdApplied,
        failureCount = failureCount,
        operations = operations,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
