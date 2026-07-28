package app.muxtv.testing.iptv

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

class M3uParseMeasurementTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("muxtv-m3u-measurement-")
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `nearest-rank summary retains all samples and reports descriptive percentiles`() {
        val summary = M3uParseMeasurementStatistics.summarize(
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
    fun `real runner measures parser only and preserves corpus agreement`() = runTest {
        val report = M3uParseMeasurementRunner().run(
            M3uParseMeasurementSpec(
                profile = M3uCorpusProfile.SMALL_1K,
                seed = 20260728L,
                sourceCommit = SOURCE_COMMIT,
                warmupIterations = 1,
                measuredIterations = 5,
                runnerLabel = "unit-windows-x64",
            ),
        )

        assertThat(report.schemaVersion).isEqualTo(1)
        assertThat(report.methodVersion).isEqualTo(1)
        assertThat(report.profile).isEqualTo(M3uCorpusProfile.SMALL_1K)
        assertThat(report.sourceCommit).isEqualTo(SOURCE_COMMIT)
        assertThat(report.warmupIterations).isEqualTo(1)
        assertThat(report.samples).hasSize(5)
        assertThat(report.failureCount).isEqualTo(0)
        assertThat(report.expectedParsedEntries).isEqualTo(1_000)
        assertThat(report.expectedSkippedEntries).isEqualTo(1)
        assertThat(report.expectedWarningCount).isEqualTo(2)
        assertThat(report.corpusUtf8ByteCount).isGreaterThan(0L)
        assertThat(report.corpusSha256).hasLength(64)
        assertThat(report.samples.map { it.iteration }).containsExactly(1, 2, 3, 4, 5).inOrder()
        assertThat(report.samples.all { it.wallTimeNanos > 0L }).isTrue()
        assertThat(report.wallTimeSummary.sampleCount).isEqualTo(5)
        assertThat(report.wallTimeSummary.minimum).isAtMost(report.wallTimeSummary.maximum)
        assertThat(report.environment.availableProcessors).isGreaterThan(0)
        assertThat(report.environment.maxHeapBytes).isGreaterThan(0L)
        assertThat(report.toString()).doesNotContain("stream.example")
        assertThat(report.toString()).doesNotContain("Synthetic Channel")
    }

    @Test
    fun `canonical report JSON contains raw samples but no payload or paths`() = runTest {
        val report = M3uParseMeasurementRunner().run(
            M3uParseMeasurementSpec(
                profile = M3uCorpusProfile.SMALL_1K,
                seed = 7L,
                sourceCommit = SOURCE_COMMIT,
                warmupIterations = 0,
                measuredIterations = 5,
                runnerLabel = "local",
            ),
        )
        val output = ByteArrayOutputStream()

        M3uParseMeasurementJsonWriter.write(report, output)

        val json = output.toString(Charsets.UTF_8)
        assertThat(json).startsWith("{\n  \"schemaVersion\": 1,\n  \"methodVersion\": 1,")
        assertThat(json).endsWith("\n")
        assertThat(json).doesNotContain("\r")
        assertThat(json).contains("\"runnerLabel\": \"local\"")
        assertThat(json).contains("\"rawSamples\": [")
        assertThat(json).contains("\"wallTimeNanos\"")
        assertThat(json).contains("\"allocationMeasurement\"")
        assertThat(json).contains("\"thresholdApplied\": false")
        assertThat(json).doesNotContain(root.toString())
        assertThat(json).doesNotContain("stream.example")
        assertThat(json).doesNotContain("Synthetic Channel")
        assertThat(json).doesNotContain("locator")
    }

    @Test
    fun `command writes a report and emits only safe basename summary`() {
        val reportPath = root.resolve("parse-report.json")
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = M3uParseMeasurementCommand().run(
            args = validArgs(reportPath),
            stdout = stdout,
            stderr = stderr,
        )

        assertThat(exitCode).isEqualTo(M3uParseMeasurementCommand.EXIT_SUCCESS)
        assertThat(stderr.toString()).isEmpty()
        assertThat(Files.isRegularFile(reportPath)).isTrue()
        assertThat(stdout.toString()).contains("report=parse-report.json")
        assertThat(stdout.toString()).contains("profile=small-1k")
        assertThat(stdout.toString()).contains("samples=5")
        assertThat(stdout.toString()).contains("thresholdApplied=false")
        assertThat(stdout.toString()).doesNotContain(root.toString())
        assertThat(Files.readString(reportPath)).contains("\"rawSamples\"")
    }

    @Test
    fun `invalid command arguments return stable safe usage failure`() {
        val secretLike = "https://secret.example/live?token=do-not-print"
        val invalidCases = listOf(
            emptyList(),
            validArgs(root.resolve("report.json")).dropLast(2),
            validArgs(root.resolve("report.json")) + listOf("--profile", "large-50k"),
            validArgs(root.resolve("report.json")).map { if (it == "small-1k") secretLike else it },
            validArgs(root.resolve("report.json")).map { if (it == "5") "4" else it },
            validArgs(root.resolve("report.json")) + listOf("--unknown", secretLike),
        )

        invalidCases.forEach { args ->
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val exitCode = M3uParseMeasurementCommand().run(args, stdout, stderr)

            assertThat(exitCode).isEqualTo(M3uParseMeasurementCommand.EXIT_USAGE)
            assertThat(stdout.toString()).isEmpty()
            assertThat(stderr.toString()).contains("Usage:")
            assertThat(stderr.toString()).doesNotContain(secretLike)
            assertThat(stderr.toString()).doesNotContain(root.toString())
        }
    }

    private fun validArgs(reportPath: Path): List<String> = listOf(
        "--profile",
        "small-1k",
        "--seed",
        "20260728",
        "--source-commit",
        SOURCE_COMMIT,
        "--warmups",
        "1",
        "--iterations",
        "5",
        "--runner-label",
        "unit-windows-x64",
        "--output",
        reportPath.toString(),
    )

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
