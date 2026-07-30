package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.junit.Test

class MeasurementSeriesCommandTest {
    @Test
    fun `command rejects missing duplicate and unknown options without echoing supplied values`() {
        val missing = runCommand(arrayOf())
        assertThat(missing.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.USAGE.code)
        assertThat(missing.stderr).contains("usage-error")

        val privateValue = "C:\\Users\\Dmitry\\private-series.json"
        val duplicate = runCommand(
            arrayOf(
                "--request", privateValue,
                "--request", privateValue,
                "--input-directory", "input",
                "--output-directory", "output",
            ),
        )
        assertThat(duplicate.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.USAGE.code)
        assertThat(duplicate.stderr).doesNotContain("Dmitry")
        assertThat(duplicate.stderr).doesNotContain(privateValue)

        val unknown = runCommand(
            arrayOf(
                "--request", "request.json",
                "--input-directory", "input",
                "--output-directory", "output",
                "--private-token", "secret-value",
            ),
        )
        assertThat(unknown.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.USAGE.code)
        assertThat(unknown.stderr).doesNotContain("secret-value")
        assertThat(unknown.stderr).doesNotContain("private-token")
    }

    @Test
    fun `command publishes canonical variance and basename-only audit manifest`() {
        val root = Files.createTempDirectory("muxtv-series-command")
        val input = Files.createDirectories(root.resolve("input"))
        val output = Files.createDirectories(root.resolve("output"))
        val firstBytes = m3uReport(listOf(100L, 110L, 120L, 130L, 140L)).toByteArray()
        val secondBytes = m3uReport(listOf(120L, 130L, 140L, 150L, 160L)).toByteArray()
        Files.write(input.resolve("m3u-current-01.json"), firstBytes)
        Files.write(input.resolve("m3u-current-02.json"), secondBytes)
        val request = root.resolve("request.json")
        Files.writeString(
            request,
            requestJson(
                family = "m3u-parse",
                outputName = "m3u-current-variance.json",
                runs = listOf(
                    "private-run-a" to "m3u-current-01.json",
                    "private-run-b" to "m3u-current-02.json",
                ),
                androidProfile = "null",
            ),
        )

        val result = runCommand(commandArgs(request, input, output))

        assertThat(result.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.SUCCESS.code)
        assertThat(result.stdout).contains("status=passed")
        assertThat(result.stdout).contains("report=m3u-current-variance.json")
        assertThat(result.stdout).contains("manifest=m3u-current-variance.manifest.json")
        assertThat(result.stdout).doesNotContain(root.toString())
        assertThat(result.stderr).isEmpty()

        val variance = Files.readString(output.resolve("m3u-current-variance.json"))
        assertThat(variance).startsWith("{\n  \"schemaVersion\": 1,")
        assertThat(variance).endsWith("\n")
        assertThat(variance).contains("\"thresholdApplied\": false")
        assertThat(variance).contains("\"seriesCount\": 2")
        assertThat(variance).contains(sha256(firstBytes))
        assertThat(variance).contains(sha256(secondBytes))
        assertThat(variance).doesNotContain("private-run-a")
        assertThat(variance).doesNotContain(root.toString())

        val manifest = Files.readString(output.resolve("m3u-current-variance.manifest.json"))
        assertThat(manifest).startsWith("{\n  \"schemaVersion\": 1,")
        assertThat(manifest).endsWith("\n")
        assertThat(manifest).contains("\"family\": \"m3u-parse\"")
        assertThat(manifest).contains("\"reportName\": \"m3u-current-01.json\"")
        assertThat(manifest).contains("\"sha256\": \"${sha256(firstBytes)}\"")
        assertThat(manifest).doesNotContain("private-run-a")
        assertThat(manifest).doesNotContain(root.toString())
    }

    @Test
    fun `command rejects duplicate report bytes and mixed comparison identity`() {
        val root = Files.createTempDirectory("muxtv-series-invalid")
        val input = Files.createDirectories(root.resolve("input"))
        val output = Files.createDirectories(root.resolve("output"))
        val first = m3uReport(listOf(100L, 110L, 120L, 130L, 140L)).toByteArray()
        Files.write(input.resolve("first.json"), first)
        Files.write(input.resolve("duplicate.json"), first)
        val duplicateRequest = root.resolve("duplicate-request.json")
        Files.writeString(
            duplicateRequest,
            requestJson(
                family = "m3u-parse",
                outputName = "duplicate.json",
                runs = listOf("one" to "first.json", "two" to "duplicate.json"),
                androidProfile = "null",
            ),
        )

        val duplicate = runCommand(commandArgs(duplicateRequest, input, output))
        assertThat(duplicate.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.ANALYSIS.code)
        assertThat(duplicate.stderr).contains("analysis-error")
        assertThat(Files.list(output).use { it.count() }).isEqualTo(0)

        Files.write(
            input.resolve("second.json"),
            m3uReport(
                samples = listOf(120L, 130L, 140L, 150L, 160L),
                sourceCommit = "1".repeat(40),
            ).toByteArray(),
        )
        val mixedRequest = root.resolve("mixed-request.json")
        Files.writeString(
            mixedRequest,
            requestJson(
                family = "m3u-parse",
                outputName = "mixed.json",
                runs = listOf("one" to "first.json", "two" to "second.json"),
                androidProfile = "null",
            ),
        )

        val mixed = runCommand(commandArgs(mixedRequest, input, output))
        assertThat(mixed.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.ANALYSIS.code)
        assertThat(mixed.stderr).contains("analysis-error")
        assertThat(mixed.stderr).doesNotContain("111111")
    }

    @Test
    fun `request manifest rejects paths invalid counts and family profile mismatch`() {
        val root = Files.createTempDirectory("muxtv-series-request")
        val input = Files.createDirectories(root.resolve("input"))
        val output = Files.createDirectories(root.resolve("output"))
        Files.writeString(input.resolve("one.json"), m3uReport(DEFAULT_SAMPLES))
        Files.writeString(input.resolve("two.json"), m3uReport(DEFAULT_SAMPLES.map { it + 10 }))

        val invalidPath = root.resolve("invalid-path.json")
        Files.writeString(
            invalidPath,
            requestJson(
                family = "m3u-parse",
                outputName = "../outside.json",
                runs = listOf("one" to "one.json", "two" to "two.json"),
                androidProfile = "null",
            ),
        )
        assertThat(runCommand(commandArgs(invalidPath, input, output)).exitCode)
            .isEqualTo(MeasurementSeriesCommandExitCode.INPUT.code)

        val oneRun = root.resolve("one-run.json")
        Files.writeString(
            oneRun,
            requestJson(
                family = "m3u-parse",
                outputName = "one-run.json",
                runs = listOf("one" to "one.json"),
                androidProfile = "null",
            ),
        )
        assertThat(runCommand(commandArgs(oneRun, input, output)).exitCode)
            .isEqualTo(MeasurementSeriesCommandExitCode.INPUT.code)

        val unexpectedProfile = root.resolve("unexpected-profile.json")
        Files.writeString(
            unexpectedProfile,
            requestJson(
                family = "m3u-parse",
                outputName = "profile.json",
                runs = listOf("one" to "one.json", "two" to "two.json"),
                androidProfile = androidProfileJson(),
            ),
        )
        assertThat(runCommand(commandArgs(unexpectedProfile, input, output)).exitCode)
            .isEqualTo(MeasurementSeriesCommandExitCode.INPUT.code)

        val missingProfile = root.resolve("missing-profile.json")
        Files.writeString(
            missingProfile,
            requestJson(
                family = "catalog-database",
                outputName = "room.json",
                runs = listOf("one" to "one.json", "two" to "two.json"),
                androidProfile = "null",
            ),
        )
        assertThat(runCommand(commandArgs(missingProfile, input, output)).exitCode)
            .isEqualTo(MeasurementSeriesCommandExitCode.INPUT.code)
    }

    @Test
    fun `command refuses implicit overwrite without damaging existing artifacts`() {
        val root = Files.createTempDirectory("muxtv-series-overwrite")
        val input = Files.createDirectories(root.resolve("input"))
        val output = Files.createDirectories(root.resolve("output"))
        Files.writeString(input.resolve("one.json"), m3uReport(DEFAULT_SAMPLES))
        Files.writeString(input.resolve("two.json"), m3uReport(DEFAULT_SAMPLES.map { it + 10 }))
        val request = root.resolve("request.json")
        Files.writeString(
            request,
            requestJson(
                family = "m3u-parse",
                outputName = "existing.json",
                runs = listOf("one" to "one.json", "two" to "two.json"),
                androidProfile = "null",
            ),
        )
        Files.writeString(output.resolve("existing.json"), "preserve-report")
        Files.writeString(output.resolve("existing.manifest.json"), "preserve-manifest")

        val result = runCommand(commandArgs(request, input, output))

        assertThat(result.exitCode).isEqualTo(MeasurementSeriesCommandExitCode.PUBLICATION.code)
        assertThat(result.stderr).contains("publication-error")
        assertThat(Files.readString(output.resolve("existing.json"))).isEqualTo("preserve-report")
        assertThat(Files.readString(output.resolve("existing.manifest.json"))).isEqualTo("preserve-manifest")
        assertThat(Files.list(output).use { it.map(Path::getFileName).map(Path::toString).toList() })
            .containsExactly("existing.json", "existing.manifest.json")
    }

    private fun runCommand(args: Array<String>): CommandResult {
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val exitCode = MeasurementSeriesCommand.run(args, stdout, stderr)
        return CommandResult(exitCode, stdout.toString(), stderr.toString())
    }

    private fun commandArgs(request: Path, input: Path, output: Path): Array<String> = arrayOf(
        "--request", request.toString(),
        "--input-directory", input.toString(),
        "--output-directory", output.toString(),
    )

    private fun requestJson(
        family: String,
        outputName: String,
        runs: List<Pair<String, String>>,
        androidProfile: String,
    ): String = buildString {
        append("{\n")
        append("  \"schemaVersion\": 1,\n")
        append("  \"family\": \"").append(family).append("\",\n")
        append("  \"outputName\": \"").append(outputName).append("\",\n")
        append("  \"runs\": [\n")
        runs.forEachIndexed { index, (repetitionId, reportName) ->
            append("    {\"repetitionId\": \"").append(repetitionId)
                .append("\", \"reportName\": \"").append(reportName).append("\"}")
            if (index != runs.lastIndex) append(',')
            append('\n')
        }
        append("  ],\n")
        append("  \"androidProfile\": ").append(androidProfile).append('\n')
        append("}\n")
    }

    private fun androidProfileJson(): String = """
        {
          "requestedApiLevel": 36,
          "systemImage": "system-images;android-36;android-tv;x86_64",
          "configuredRamMb": 2048,
          "configuredCpuCores": 2,
          "fallbackUsed": false
        }
    """.trimIndent()

    private fun m3uReport(
        samples: List<Long>,
        sourceCommit: String = SOURCE_COMMIT,
    ): String {
        val sorted = samples.sorted()
        return """
            {
              "schemaVersion": 1,
              "methodVersion": 1,
              "thresholdApplied": false,
              "runnerLabel": "self-hosted-windows-x64",
              "sourceCommit": "$sourceCommit",
              "profile": "small-1k",
              "seed": 20260728,
              "warmupIterations": 2,
              "measuredIterations": ${samples.size},
              "measurementScope": "local-file-open-plus-streaming-parser-no-retention-sink",
              "corpus": {
                "utf8ByteCount": 269079,
                "sha256": "$FIXTURE_SHA"
              },
              "expected": {
                "parsedEntries": 1000,
                "skippedEntries": 1,
                "warningCount": 2
              },
              "environment": {
                "osName": "Windows 10",
                "osVersion": "10.0.19045",
                "osArchitecture": "amd64",
                "jvmVendor": "Eclipse Adoptium",
                "jvmVersion": "26.0.1",
                "jvmRuntimeName": "OpenJDK Runtime Environment",
                "availableProcessors": 4,
                "maxHeapBytes": 1073741824,
                "allocationMeasurement": "unavailable"
              },
              "wallTimeSummaryNanos": {
                "sampleCount": ${samples.size},
                "minimum": ${sorted.first()},
                "p50": ${sorted.nearestRank(50)},
                "p90": ${sorted.nearestRank(90)},
                "p95": ${sorted.nearestRank(95)},
                "maximum": ${sorted.last()}
              },
              "allocationSummaryBytes": null,
              "rawSamples": [
                ${samples.mapIndexed { index, value -> "{\"iteration\": ${index + 1}, \"wallTimeNanos\": $value, \"allocatedBytes\": null}" }.joinToString(",\n                ")}
              ],
              "failureCount": 0
            }
        """.trimIndent()
    }

    private fun List<Long>.nearestRank(percentile: Int): Long {
        val rank = kotlin.math.ceil(percentile / 100.0 * size).toInt().coerceIn(1, size)
        return this[rank - 1]
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
        val FIXTURE_SHA = "2b".repeat(32)
        val DEFAULT_SAMPLES = listOf(100L, 110L, 120L, 130L, 140L)
    }
}
