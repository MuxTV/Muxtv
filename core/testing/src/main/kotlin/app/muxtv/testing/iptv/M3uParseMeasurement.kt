package app.muxtv.testing.iptv

import app.muxtv.catalog.ingest.M3uEntry
import app.muxtv.catalog.ingest.M3uParseLimits
import app.muxtv.catalog.ingest.M3uParseReport
import app.muxtv.catalog.ingest.M3uParseSink
import app.muxtv.catalog.ingest.M3uPlaylistHeader
import app.muxtv.catalog.ingest.M3uWarning
import app.muxtv.catalog.ingest.StreamingM3uParser
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import kotlin.math.ceil
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private val MEASUREMENT_SOURCE_COMMIT_PATTERN = Regex("[0-9a-f]{40}")
private val MEASUREMENT_RUNNER_LABEL_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}")

data class M3uParseMeasurementSpec(
    val profile: M3uCorpusProfile,
    val seed: Long,
    val sourceCommit: String,
    val warmupIterations: Int,
    val measuredIterations: Int,
    val runnerLabel: String,
) {
    init {
        require(sourceCommit.matches(MEASUREMENT_SOURCE_COMMIT_PATTERN))
        require(warmupIterations in 0..100)
        require(measuredIterations in 5..1_000)
        require(runnerLabel.matches(MEASUREMENT_RUNNER_LABEL_PATTERN))
    }
}

data class M3uParseMeasurementSample(
    val iteration: Int,
    val wallTimeNanos: Long,
    val allocatedBytes: Long?,
) {
    init {
        require(iteration > 0)
        require(wallTimeNanos > 0L)
        require(allocatedBytes == null || allocatedBytes >= 0L)
    }
}

data class M3uParseMeasurementSummary(
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

object M3uParseMeasurementStatistics {
    fun summarize(values: List<Long>): M3uParseMeasurementSummary {
        require(values.isNotEmpty())
        require(values.all { it >= 0L })
        val sorted = values.sorted()
        return M3uParseMeasurementSummary(
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

data class M3uParseMeasurementEnvironment(
    val osName: String,
    val osVersion: String,
    val osArchitecture: String,
    val jvmVendor: String,
    val jvmVersion: String,
    val jvmRuntimeName: String,
    val availableProcessors: Int,
    val maxHeapBytes: Long,
    val allocationMeasurement: String,
) {
    init {
        require(availableProcessors > 0)
        require(maxHeapBytes > 0L)
        require(allocationMeasurement == "thread-allocated-bytes" || allocationMeasurement == "unavailable")
    }
}

class M3uParseMeasurementReport(
    val schemaVersion: Int,
    val methodVersion: Int,
    val thresholdApplied: Boolean,
    val runnerLabel: String,
    val sourceCommit: String,
    val profile: M3uCorpusProfile,
    val seed: Long,
    val warmupIterations: Int,
    val measuredIterations: Int,
    val corpusUtf8ByteCount: Long,
    val corpusSha256: String,
    val expectedParsedEntries: Int,
    val expectedSkippedEntries: Int,
    val expectedWarningCount: Int,
    val environment: M3uParseMeasurementEnvironment,
    samples: List<M3uParseMeasurementSample>,
    val wallTimeSummary: M3uParseMeasurementSummary,
    val allocationSummary: M3uParseMeasurementSummary?,
    val failureCount: Int,
) {
    val samples: List<M3uParseMeasurementSample> = samples.toList()

    init {
        require(schemaVersion > 0)
        require(methodVersion > 0)
        require(!thresholdApplied)
        require(runnerLabel.matches(MEASUREMENT_RUNNER_LABEL_PATTERN))
        require(sourceCommit.matches(MEASUREMENT_SOURCE_COMMIT_PATTERN))
        require(warmupIterations >= 0)
        require(measuredIterations >= 5)
        require(corpusUtf8ByteCount > 0L)
        require(corpusSha256.matches(Regex("[0-9a-f]{64}")))
        require(expectedParsedEntries > 0)
        require(expectedSkippedEntries >= 0)
        require(expectedWarningCount >= 0)
        require(this.samples.size == measuredIterations)
        require(wallTimeSummary.sampleCount == measuredIterations)
        require(allocationSummary == null || allocationSummary.sampleCount == measuredIterations)
        require(failureCount >= 0)
    }

    override fun toString(): String =
        "M3uParseMeasurementReport(" +
            "schemaVersion=$schemaVersion, methodVersion=$methodVersion, " +
            "thresholdApplied=$thresholdApplied, runnerLabel=$runnerLabel, " +
            "sourceCommit=$sourceCommit, profile=${profile.artifactId}, seed=$seed, " +
            "warmupIterations=$warmupIterations, measuredIterations=$measuredIterations, " +
            "corpusUtf8ByteCount=$corpusUtf8ByteCount, sampleCount=${samples.size}, " +
            "failureCount=$failureCount)"
}

class M3uParseMeasurementRunner(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun run(spec: M3uParseMeasurementSpec): M3uParseMeasurementReport {
        val corpusPath = Files.createTempFile("muxtv-m3u-parse-measurement-", ".m3u8")
        try {
            val manifest = Files.newOutputStream(corpusPath).use { output ->
                DeterministicM3uCorpusGenerator.generate(
                    spec = M3uCorpusSpec(
                        profile = spec.profile,
                        seed = spec.seed,
                        sourceCommit = spec.sourceCommit,
                    ),
                    output = output,
                )
            }
            val parser = StreamingM3uParser()
            val limits = M3uParseLimits(maxEntries = spec.profile.entryCount + 1)

            repeat(spec.warmupIterations) {
                parseAndVerify(corpusPath, parser, limits, manifest)
            }

            val allocationProbe = ThreadAllocationProbe.create()
            val samples = buildList(spec.measuredIterations) {
                repeat(spec.measuredIterations) { index ->
                    val allocationBefore = allocationProbe.currentThreadAllocatedBytes()
                    val startedAt = nanoTime()
                    parseAndVerify(corpusPath, parser, limits, manifest)
                    val completedAt = nanoTime()
                    val allocationAfter = allocationProbe.currentThreadAllocatedBytes()
                    add(
                        M3uParseMeasurementSample(
                            iteration = index + 1,
                            wallTimeNanos = (completedAt - startedAt).coerceAtLeast(1L),
                            allocatedBytes = allocationDelta(allocationBefore, allocationAfter),
                        ),
                    )
                }
            }
            val allocationValues = samples.mapNotNull(M3uParseMeasurementSample::allocatedBytes)
            val allocationSummary = if (allocationValues.size == samples.size) {
                M3uParseMeasurementStatistics.summarize(allocationValues)
            } else {
                null
            }

            return M3uParseMeasurementReport(
                schemaVersion = REPORT_SCHEMA_VERSION,
                methodVersion = METHOD_VERSION,
                thresholdApplied = false,
                runnerLabel = spec.runnerLabel,
                sourceCommit = spec.sourceCommit,
                profile = spec.profile,
                seed = spec.seed,
                warmupIterations = spec.warmupIterations,
                measuredIterations = spec.measuredIterations,
                corpusUtf8ByteCount = manifest.utf8ByteCount,
                corpusSha256 = manifest.sha256,
                expectedParsedEntries = manifest.expectedParsedEntries,
                expectedSkippedEntries = manifest.expectedSkippedEntries,
                expectedWarningCount = manifest.expectedWarningCount,
                environment = captureEnvironment(allocationProbe.isAvailable),
                samples = samples,
                wallTimeSummary = M3uParseMeasurementStatistics.summarize(
                    samples.map(M3uParseMeasurementSample::wallTimeNanos),
                ),
                allocationSummary = allocationSummary,
                failureCount = 0,
            )
        } finally {
            Files.deleteIfExists(corpusPath)
        }
    }

    private suspend fun parseAndVerify(
        corpusPath: Path,
        parser: StreamingM3uParser,
        limits: M3uParseLimits,
        manifest: M3uCorpusManifest,
    ) {
        val sink = CountingM3uParseSink()
        val report = Files.newInputStream(corpusPath).use { input ->
            parser.parse(
                input = input,
                sink = sink,
                limits = limits,
            )
        }
        verifyAgreement(report, sink, manifest)
    }

    private fun verifyAgreement(
        report: M3uParseReport,
        sink: CountingM3uParseSink,
        manifest: M3uCorpusManifest,
    ) {
        check(report.parsedEntries == manifest.expectedParsedEntries) {
            "M3U parse measurement count agreement failed."
        }
        check(report.skippedEntries == manifest.expectedSkippedEntries) {
            "M3U parse measurement count agreement failed."
        }
        check(report.warningCount == manifest.expectedWarningCount) {
            "M3U parse measurement count agreement failed."
        }
        check(sink.entryCount == manifest.expectedParsedEntries) {
            "M3U parse measurement sink agreement failed."
        }
        check(sink.warningCount == manifest.expectedWarningCount) {
            "M3U parse measurement sink agreement failed."
        }
        check(sink.headerCount == 1) {
            "M3U parse measurement header agreement failed."
        }
    }

    private fun allocationDelta(before: Long?, after: Long?): Long? =
        if (before == null || after == null) null else (after - before).coerceAtLeast(0L)

    private fun captureEnvironment(allocationAvailable: Boolean): M3uParseMeasurementEnvironment =
        M3uParseMeasurementEnvironment(
            osName = systemProperty("os.name"),
            osVersion = systemProperty("os.version"),
            osArchitecture = systemProperty("os.arch"),
            jvmVendor = systemProperty("java.vendor"),
            jvmVersion = systemProperty("java.version"),
            jvmRuntimeName = systemProperty("java.runtime.name"),
            availableProcessors = Runtime.getRuntime().availableProcessors(),
            maxHeapBytes = Runtime.getRuntime().maxMemory(),
            allocationMeasurement = if (allocationAvailable) {
                "thread-allocated-bytes"
            } else {
                "unavailable"
            },
        )

    private fun systemProperty(name: String): String =
        System.getProperty(name)?.take(MAX_ENVIRONMENT_VALUE_LENGTH).orEmpty().ifBlank { "unknown" }

    private companion object {
        const val REPORT_SCHEMA_VERSION: Int = 1
        const val METHOD_VERSION: Int = 1
        const val MAX_ENVIRONMENT_VALUE_LENGTH: Int = 256
    }
}

private class CountingM3uParseSink : M3uParseSink {
    var headerCount: Int = 0
        private set
    var entryCount: Int = 0
        private set
    var warningCount: Int = 0
        private set

    override suspend fun onHeader(header: M3uPlaylistHeader) {
        headerCount += 1
    }

    override suspend fun onEntry(entry: M3uEntry) {
        entryCount += 1
    }

    override suspend fun onWarning(warning: M3uWarning) {
        warningCount += 1
    }
}

private class ThreadAllocationProbe private constructor(
    private val bean: com.sun.management.ThreadMXBean?,
) {
    val isAvailable: Boolean = bean != null

    fun currentThreadAllocatedBytes(): Long? =
        bean?.getThreadAllocatedBytes(Thread.currentThread().id)?.takeIf { it >= 0L }

    companion object {
        fun create(): ThreadAllocationProbe {
            val extended = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
                ?: return ThreadAllocationProbe(null)
            return try {
                if (!extended.isThreadAllocatedMemorySupported) {
                    ThreadAllocationProbe(null)
                } else {
                    if (!extended.isThreadAllocatedMemoryEnabled) {
                        extended.isThreadAllocatedMemoryEnabled = true
                    }
                    ThreadAllocationProbe(extended.takeIf { it.isThreadAllocatedMemoryEnabled })
                }
            } catch (_: Exception) {
                ThreadAllocationProbe(null)
            }
        }
    }
}

object M3uParseMeasurementJsonWriter {
    fun write(
        report: M3uParseMeasurementReport,
        output: OutputStream,
    ) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": ${report.schemaVersion},\n")
            append("  \"methodVersion\": ${report.methodVersion},\n")
            append("  \"thresholdApplied\": ${report.thresholdApplied},\n")
            append("  \"runnerLabel\": ").appendJsonString(report.runnerLabel).append(",\n")
            append("  \"sourceCommit\": ").appendJsonString(report.sourceCommit).append(",\n")
            append("  \"profile\": ").appendJsonString(report.profile.artifactId).append(",\n")
            append("  \"seed\": ${report.seed},\n")
            append("  \"warmupIterations\": ${report.warmupIterations},\n")
            append("  \"measuredIterations\": ${report.measuredIterations},\n")
            append("  \"measurementScope\": \"local-file-open-plus-streaming-parser-no-retention-sink\",\n")
            append("  \"corpus\": {\n")
            append("    \"utf8ByteCount\": ${report.corpusUtf8ByteCount},\n")
            append("    \"sha256\": ").appendJsonString(report.corpusSha256).append("\n")
            append("  },\n")
            append("  \"expected\": {\n")
            append("    \"parsedEntries\": ${report.expectedParsedEntries},\n")
            append("    \"skippedEntries\": ${report.expectedSkippedEntries},\n")
            append("    \"warningCount\": ${report.expectedWarningCount}\n")
            append("  },\n")
            append("  \"environment\": {\n")
            append("    \"osName\": ").appendJsonString(report.environment.osName).append(",\n")
            append("    \"osVersion\": ").appendJsonString(report.environment.osVersion).append(",\n")
            append("    \"osArchitecture\": ").appendJsonString(report.environment.osArchitecture).append(",\n")
            append("    \"jvmVendor\": ").appendJsonString(report.environment.jvmVendor).append(",\n")
            append("    \"jvmVersion\": ").appendJsonString(report.environment.jvmVersion).append(",\n")
            append("    \"jvmRuntimeName\": ").appendJsonString(report.environment.jvmRuntimeName).append(",\n")
            append("    \"availableProcessors\": ${report.environment.availableProcessors},\n")
            append("    \"maxHeapBytes\": ${report.environment.maxHeapBytes},\n")
            append("    \"allocationMeasurement\": ")
                .appendJsonString(report.environment.allocationMeasurement)
                .append("\n")
            append("  },\n")
            append("  \"wallTimeSummaryNanos\": ")
            appendSummary(report.wallTimeSummary, indent = "  ")
            append(",\n")
            append("  \"allocationSummaryBytes\": ")
            if (report.allocationSummary == null) {
                append("null")
            } else {
                appendSummary(report.allocationSummary, indent = "  ")
            }
            append(",\n")
            append("  \"rawSamples\": [\n")
            report.samples.forEachIndexed { index, sample ->
                append("    {\n")
                append("      \"iteration\": ${sample.iteration},\n")
                append("      \"wallTimeNanos\": ${sample.wallTimeNanos},\n")
                append("      \"allocatedBytes\": ${sample.allocatedBytes ?: "null"}\n")
                append("    }")
                if (index != report.samples.lastIndex) append(',')
                append('\n')
            }
            append("  ],\n")
            append("  \"failureCount\": ${report.failureCount}\n")
            append("}\n")
        }
        output.write(json.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun StringBuilder.appendSummary(
        summary: M3uParseMeasurementSummary,
        indent: String,
    ) {
        append("{\n")
        append(indent).append("  \"sampleCount\": ${summary.sampleCount},\n")
        append(indent).append("  \"minimum\": ${summary.minimum},\n")
        append(indent).append("  \"p50\": ${summary.p50},\n")
        append(indent).append("  \"p90\": ${summary.p90},\n")
        append(indent).append("  \"p95\": ${summary.p95},\n")
        append(indent).append("  \"maximum\": ${summary.maximum}\n")
        append(indent).append('}')
    }

    private fun StringBuilder.appendJsonString(value: String): StringBuilder {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u").append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
        return this
    }
}

class M3uParseMeasurementCommand private constructor(
    private val runner: M3uParseMeasurementRunner,
) {
    constructor() : this(M3uParseMeasurementRunner())

    fun run(
        args: List<String>,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        val parsed = parseArguments(args)
        if (parsed !is MeasurementCommandParseResult.Ready) {
            stderr.writeMeasurementLine("Invalid M3U parse measurement arguments.")
            stderr.writeMeasurementLine(USAGE)
            return EXIT_USAGE
        }

        return try {
            val report = runBlocking { runner.run(parsed.spec) }
            writeReportAtomically(parsed.outputPath, report)
            stdout.writeMeasurementLine("MuxTV M3U parse measurement completed.")
            stdout.writeMeasurementLine("report=${parsed.outputPath.fileName}")
            stdout.writeMeasurementLine("profile=${report.profile.artifactId}")
            stdout.writeMeasurementLine("samples=${report.samples.size}")
            stdout.writeMeasurementLine("p50Nanos=${report.wallTimeSummary.p50}")
            stdout.writeMeasurementLine("p95Nanos=${report.wallTimeSummary.p95}")
            stdout.writeMeasurementLine("thresholdApplied=${report.thresholdApplied}")
            EXIT_SUCCESS
        } catch (_: FileAlreadyExistsException) {
            stderr.writeMeasurementLine("Measurement report already exists.")
            EXIT_REPORT
        } catch (_: java.io.IOException) {
            stderr.writeMeasurementLine("Measurement report publication failed.")
            EXIT_REPORT
        } catch (_: Exception) {
            stderr.writeMeasurementLine("M3U parse measurement failed.")
            EXIT_INTERNAL
        }
    }

    private fun writeReportAtomically(
        outputPath: Path,
        report: M3uParseMeasurementReport,
    ) {
        val absoluteOutput = outputPath.toAbsolutePath().normalize()
        val parent = absoluteOutput.parent ?: Path.of(".").toAbsolutePath().normalize()
        Files.createDirectories(parent)
        if (Files.exists(absoluteOutput)) {
            throw FileAlreadyExistsException(absoluteOutput.toString())
        }
        val staged = Files.createTempFile(parent, ".muxtv-m3u-parse-", ".tmp")
        try {
            Files.newOutputStream(staged).use { output ->
                M3uParseMeasurementJsonWriter.write(report, output)
            }
            try {
                Files.move(staged, absoluteOutput, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(staged, absoluteOutput)
            }
        } finally {
            Files.deleteIfExists(staged)
        }
    }

    private fun parseArguments(args: List<String>): MeasurementCommandParseResult {
        if (args.isEmpty()) return MeasurementCommandParseResult.Invalid
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val option = args[index]
            if (option !in VALUE_OPTIONS || values.containsKey(option)) {
                return MeasurementCommandParseResult.Invalid
            }
            val valueIndex = index + 1
            if (valueIndex >= args.size || args[valueIndex].startsWith("--")) {
                return MeasurementCommandParseResult.Invalid
            }
            values[option] = args[valueIndex]
            index += 2
        }
        if (values.keys != VALUE_OPTIONS) {
            return MeasurementCommandParseResult.Invalid
        }

        val profile = M3uCorpusProfile.entries.firstOrNull {
            it.artifactId == values.getValue("--profile")
        } ?: return MeasurementCommandParseResult.Invalid
        val seed = values.getValue("--seed").toLongOrNull()
            ?: return MeasurementCommandParseResult.Invalid
        val warmups = values.getValue("--warmups").toIntOrNull()
            ?: return MeasurementCommandParseResult.Invalid
        val iterations = values.getValue("--iterations").toIntOrNull()
            ?: return MeasurementCommandParseResult.Invalid
        val outputPath = try {
            Path.of(values.getValue("--output"))
        } catch (_: InvalidPathException) {
            return MeasurementCommandParseResult.Invalid
        }
        if (outputPath.fileName == null) {
            return MeasurementCommandParseResult.Invalid
        }

        val spec = try {
            M3uParseMeasurementSpec(
                profile = profile,
                seed = seed,
                sourceCommit = values.getValue("--source-commit"),
                warmupIterations = warmups,
                measuredIterations = iterations,
                runnerLabel = values.getValue("--runner-label"),
            )
        } catch (_: IllegalArgumentException) {
            return MeasurementCommandParseResult.Invalid
        }
        return MeasurementCommandParseResult.Ready(spec, outputPath)
    }

    internal companion object {
        const val EXIT_SUCCESS: Int = 0
        const val EXIT_USAGE: Int = 2
        const val EXIT_REPORT: Int = 3
        const val EXIT_INTERNAL: Int = 4

        private val VALUE_OPTIONS = linkedSetOf(
            "--profile",
            "--seed",
            "--source-commit",
            "--warmups",
            "--iterations",
            "--runner-label",
            "--output",
        )
        private const val USAGE =
            "Usage: --profile <small-1k|medium-10k|large-50k> --seed <long> " +
                "--source-commit <40-char-lowercase-sha> --warmups <0..100> " +
                "--iterations <5..1000> --runner-label <safe-label> --output <report.json>"
    }
}

private sealed interface MeasurementCommandParseResult {
    data object Invalid : MeasurementCommandParseResult

    data class Ready(
        val spec: M3uParseMeasurementSpec,
        val outputPath: Path,
    ) : MeasurementCommandParseResult
}

private fun Appendable.writeMeasurementLine(value: String) {
    append(value)
    append('\n')
}

object M3uParseMeasurementMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val exitCode = M3uParseMeasurementCommand().run(
            args = args.toList(),
            stdout = System.out,
            stderr = System.err,
        )
        if (exitCode != M3uParseMeasurementCommand.EXIT_SUCCESS) {
            exitProcess(exitCode)
        }
    }
}
