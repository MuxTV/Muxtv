package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementReportAdapterTest {
    @Test
    fun `adapter rejects empty and over-limit reports without exposing input values`() {
        val empty = assertFailure(
            request = MeasurementAdaptationRequest(
                family = MeasurementReportFamily.M3U_PARSE,
                repetitionId = "host-secret-01",
                reportBytes = byteArrayOf(),
            ),
            expected = MeasurementReportAdaptationFailure.EMPTY_REPORT,
        )
        assertThat(empty.message).doesNotContain("host-secret-01")

        val secretPayload = "token=private".repeat(100_000).toByteArray()
        val large = assertFailure(
            request = MeasurementAdaptationRequest(
                family = MeasurementReportFamily.M3U_PARSE,
                repetitionId = "host-secret-02",
                reportBytes = secretPayload,
            ),
            expected = MeasurementReportAdaptationFailure.REPORT_TOO_LARGE,
        )
        assertThat(large.message).doesNotContain("private")
        assertThat(large.toString()).doesNotContain("host-secret-02")
    }

    @Test
    fun `M3U adapter keeps exact byte provenance and host JVM comparison identity`() {
        val bytes = validM3uReport().toByteArray(Charsets.UTF_8)

        val adapted = MeasurementReportAdapter.adapt(
            MeasurementAdaptationRequest(
                family = MeasurementReportFamily.M3U_PARSE,
                repetitionId = "host-01",
                reportBytes = bytes,
            ),
        )

        assertThat(adapted.run.repetitionId).isEqualTo("host-01")
        assertThat(adapted.run.sourceReportSha256).isEqualTo(sha256(bytes))
        assertThat(adapted.identity.family).isEqualTo("m3u-parse")
        assertThat(adapted.identity.sourceCommit).isEqualTo(SOURCE_COMMIT)
        assertThat(adapted.identity.fixtureSha256).isEqualTo(FIXTURE_SHA)
        assertThat(adapted.identity.runnerLabel).isEqualTo("self-hosted-windows-x64")
        assertThat(adapted.identity.apiLevel).isNull()
        assertThat(adapted.identity.systemImage).isNull()
        assertThat(adapted.identity.configuredRamMb).isNull()
        assertThat(adapted.identity.lowRamDevice).isNull()
        assertThat(adapted.identity.memoryClassMb).isNull()
        assertThat(adapted.identity.cpuCores).isEqualTo(4)
        assertThat(adapted.identity.supportedAbis).containsExactly("x86_64")
        assertThat(adapted.identity.buildMode).isEqualTo("jvm-measurement")
        assertThat(adapted.identity.runtimeIdentity).containsEntry("jvm-version", "26.0.1")
        assertThat(adapted.identity.runtimeIdentity).containsEntry("max-heap-bytes", "1073741824")
        assertThat(adapted.identity.workload).containsEntry("profile", "small-1k")
        assertThat(adapted.identity.workload).containsEntry("measured-iterations", "5")
        assertThat(adapted.run.operations).containsExactly(
            "parse-wall-time",
            listOf(100L, 110L, 120L, 130L, 140L),
        )
    }

    @Test
    fun `M3U rejects Android profile while Android reports require one`() {
        assertFailure(
            request = MeasurementAdaptationRequest(
                family = MeasurementReportFamily.M3U_PARSE,
                repetitionId = "host-01",
                reportBytes = validM3uReport().toByteArray(),
                androidProfile = androidProfile(),
            ),
            expected = MeasurementReportAdaptationFailure.UNEXPECTED_ANDROID_PROFILE,
        )
        assertFailure(
            request = MeasurementAdaptationRequest(
                family = MeasurementReportFamily.CATALOG_DATABASE,
                repetitionId = "room-01",
                reportBytes = validRoomReport().toByteArray(),
            ),
            expected = MeasurementReportAdaptationFailure.ANDROID_PROFILE_REQUIRED,
        )
        assertFailure(
            request = MeasurementAdaptationRequest(
                family = MeasurementReportFamily.PLAYER_PROXY,
                repetitionId = "player-01",
                reportBytes = validPlayerReport().toByteArray(),
            ),
            expected = MeasurementReportAdaptationFailure.ANDROID_PROFILE_REQUIRED,
        )
    }

    @Test
    fun `Room adapter validates profile and retains operation wall-time samples only`() {
        val bytes = validRoomReport().toByteArray(Charsets.UTF_8)

        val adapted = MeasurementReportAdapter.adapt(
            MeasurementAdaptationRequest(
                family = MeasurementReportFamily.CATALOG_DATABASE,
                repetitionId = "room-01",
                reportBytes = bytes,
                androidProfile = androidProfile(),
            ),
        )

        assertThat(adapted.run.sourceReportSha256).isEqualTo(sha256(bytes))
        assertThat(adapted.identity.family).isEqualTo("catalog-database")
        assertThat(adapted.identity.apiLevel).isEqualTo(36)
        assertThat(adapted.identity.systemImage)
            .isEqualTo("system-images;android-36;android-tv;x86_64")
        assertThat(adapted.identity.configuredRamMb).isEqualTo(2048)
        assertThat(adapted.identity.cpuCores).isEqualTo(2)
        assertThat(adapted.identity.lowRamDevice).isFalse()
        assertThat(adapted.identity.memoryClassMb).isEqualTo(192)
        assertThat(adapted.identity.runtimeIdentity).containsEntry("fallback-used", "false")
        assertThat(adapted.identity.workload).containsEntry("cache-state", "fresh-file-per-sample")
        assertThat(adapted.run.operations.keys).containsExactlyElementsIn(ROOM_OPERATIONS)
        assertThat(adapted.run.operations.getValue("stage-total-10k"))
            .containsExactly(200L, 210L, 220L, 230L, 240L).inOrder()
        assertThat(adapted.run.operations).doesNotContainKey("database-bytes")
    }

    @Test
    fun `Room adapter rejects API CPU and fallback context disagreement`() {
        assertFailure(
            request = roomRequest(androidProfile(requestedApiLevel = 28)),
            expected = MeasurementReportAdaptationFailure.PROFILE_MISMATCH,
        )
        assertFailure(
            request = roomRequest(androidProfile(configuredCpuCores = 4)),
            expected = MeasurementReportAdaptationFailure.PROFILE_MISMATCH,
        )
        assertFailure(
            request = roomRequest(androidProfile(fallbackUsed = true)),
            expected = MeasurementReportAdaptationFailure.PROFILE_MISMATCH,
        )
    }

    @Test
    fun `Player adapter uses normalized operation costs and validates successful counts`() {
        val bytes = validPlayerReport().toByteArray(Charsets.UTF_8)

        val adapted = MeasurementReportAdapter.adapt(
            MeasurementAdaptationRequest(
                family = MeasurementReportFamily.PLAYER_PROXY,
                repetitionId = "player-01",
                reportBytes = bytes,
                androidProfile = androidProfile(),
            ),
        )

        assertThat(adapted.run.sourceReportSha256).isEqualTo(sha256(bytes))
        assertThat(adapted.identity.family).isEqualTo("player-proxy")
        assertThat(adapted.identity.fixtureSha256).isEqualTo(PLAYER_PROFILE_SHA)
        assertThat(adapted.identity.workload).containsEntry("operations-per-sample", "1000")
        assertThat(adapted.run.operations.keys).containsExactlyElementsIn(PLAYER_OPERATIONS)
        assertThat(adapted.run.operations.getValue("request-construct"))
            .containsExactly(10L, 11L, 12L, 13L, 14L).inOrder()

        assertFailure(
            request = playerRequest(
                report = validPlayerReport(successfulResultCount = 999),
                profile = androidProfile(),
            ),
            expected = MeasurementReportAdaptationFailure.INVALID_REPORT,
        )
    }

    @Test
    fun `adapter rejects schema threshold failures unknown fields and sample disagreement`() {
        assertFailure(
            request = m3uRequest(validM3uReport(schemaVersion = 2)),
            expected = MeasurementReportAdaptationFailure.UNSUPPORTED_SCHEMA,
        )
        assertFailure(
            request = m3uRequest(validM3uReport(thresholdApplied = true)),
            expected = MeasurementReportAdaptationFailure.INVALID_REPORT,
        )
        assertFailure(
            request = m3uRequest(validM3uReport(failureCount = 1)),
            expected = MeasurementReportAdaptationFailure.INVALID_REPORT,
        )
        assertFailure(
            request = m3uRequest(validM3uReport(extraTopLevel = "\"unexpected\": true,")),
            expected = MeasurementReportAdaptationFailure.UNSUPPORTED_SCHEMA,
        )
        assertFailure(
            request = m3uRequest(validM3uReport(measuredIterations = 6)),
            expected = MeasurementReportAdaptationFailure.INVALID_REPORT,
        )
        assertFailure(
            request = m3uRequest("[]"),
            expected = MeasurementReportAdaptationFailure.INVALID_JSON,
        )
    }

    @Test
    fun `adapter failure diagnostics never contain JSON payload paths or repetition identities`() {
        val payload = """
            {
              "schemaVersion": 1,
              "privatePath": "C:\\Users\\Dmitry\\playlist.m3u8",
              "locator": "https://provider.example/live.m3u8?token=secret"
            }
        """.trimIndent()

        val failure = assertFailure(
            request = m3uRequest(payload, repetitionId = "private-series-01"),
            expected = MeasurementReportAdaptationFailure.UNSUPPORTED_SCHEMA,
        )

        assertThat(failure.message).doesNotContain("Dmitry")
        assertThat(failure.message).doesNotContain("provider.example")
        assertThat(failure.message).doesNotContain("private-series-01")
        assertThat(failure.toString()).doesNotContain("token=secret")
    }

    private fun m3uRequest(
        report: String,
        repetitionId: String = "host-01",
    ): MeasurementAdaptationRequest = MeasurementAdaptationRequest(
        family = MeasurementReportFamily.M3U_PARSE,
        repetitionId = repetitionId,
        reportBytes = report.toByteArray(Charsets.UTF_8),
    )

    private fun roomRequest(
        profile: AndroidMeasurementProfileContext?,
        report: String = validRoomReport(),
    ): MeasurementAdaptationRequest = MeasurementAdaptationRequest(
        family = MeasurementReportFamily.CATALOG_DATABASE,
        repetitionId = "room-01",
        reportBytes = report.toByteArray(Charsets.UTF_8),
        androidProfile = profile,
    )

    private fun playerRequest(
        report: String,
        profile: AndroidMeasurementProfileContext?,
    ): MeasurementAdaptationRequest = MeasurementAdaptationRequest(
        family = MeasurementReportFamily.PLAYER_PROXY,
        repetitionId = "player-01",
        reportBytes = report.toByteArray(Charsets.UTF_8),
        androidProfile = profile,
    )

    private fun androidProfile(
        requestedApiLevel: Int = 36,
        configuredCpuCores: Int = 2,
        fallbackUsed: Boolean = false,
    ): AndroidMeasurementProfileContext = AndroidMeasurementProfileContext(
        requestedApiLevel = requestedApiLevel,
        systemImage = "system-images;android-$requestedApiLevel;android-tv;x86_64",
        configuredRamMb = 2048,
        configuredCpuCores = configuredCpuCores,
        fallbackUsed = fallbackUsed,
    )

    private fun assertFailure(
        request: MeasurementAdaptationRequest,
        expected: MeasurementReportAdaptationFailure,
    ): MeasurementReportAdaptationException {
        val failure = assertThrows(MeasurementReportAdaptationException::class.java) {
            MeasurementReportAdapter.adapt(request)
        }
        assertThat(failure.code).isEqualTo(expected)
        return failure
    }

    private fun validM3uReport(
        schemaVersion: Int = 1,
        thresholdApplied: Boolean = false,
        measuredIterations: Int = 5,
        failureCount: Int = 0,
        extraTopLevel: String = "",
    ): String = """
        {
          $extraTopLevel
          "schemaVersion": $schemaVersion,
          "methodVersion": 1,
          "thresholdApplied": $thresholdApplied,
          "runnerLabel": "self-hosted-windows-x64",
          "sourceCommit": "$SOURCE_COMMIT",
          "profile": "small-1k",
          "seed": 20260728,
          "warmupIterations": 2,
          "measuredIterations": $measuredIterations,
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
          "wallTimeSummaryNanos": ${summaryJson(5, 100, 120, 140)},
          "allocationSummaryBytes": null,
          "rawSamples": [
            {"iteration": 1, "wallTimeNanos": 100, "allocatedBytes": null},
            {"iteration": 2, "wallTimeNanos": 110, "allocatedBytes": null},
            {"iteration": 3, "wallTimeNanos": 120, "allocatedBytes": null},
            {"iteration": 4, "wallTimeNanos": 130, "allocatedBytes": null},
            {"iteration": 5, "wallTimeNanos": 140, "allocatedBytes": null}
          ],
          "failureCount": $failureCount
        }
    """.trimIndent()

    private fun validRoomReport(): String = """
        {
          "schemaVersion": 1,
          "methodVersion": 1,
          "buildMode": "debug-instrumentation",
          "thresholdApplied": false,
          "sourceCommit": "$SOURCE_COMMIT",
          "runnerLabel": "android-tv-api36-x86_64",
          "cacheState": "fresh-file-per-sample",
          "workload": {
            "entryCount": 10000,
            "batchSize": 250,
            "firstPageLimit": 100,
            "sourceOverviewCount": 32,
            "warmupIterations": 2,
            "measuredIterations": 5
          },
          "fixture": {"entryCount": 10000, "sha256": "$FIXTURE_SHA"},
          "environment": {
            "manufacturer": "Google",
            "model": "sdk_google_atv64_x86_64",
            "fingerprint": "google/sdk/google:36/test-keys",
            "apiLevel": 36,
            "supportedAbis": ["x86_64"],
            "lowRamDevice": false,
            "memoryClassMb": 192,
            "availableProcessors": 2
          },
          "operations": [
            ${roomOperation("stage-batch-250", 250, 100)},
            ${roomOperation("stage-total-10k", 10000, 200)},
            ${roomOperation("activate-10k", 10000, 300)},
            ${roomOperation("active-channel-first-page", 100, 400)},
            ${roomOperation("source-overview-32", 32, 500)}
          ],
          "failureCount": 0,
          "limitations": ["Descriptive Android Room evidence only."]
        }
    """.trimIndent()

    private fun validPlayerReport(
        successfulResultCount: Int = 1000,
    ): String = """
        {
          "schemaVersion": 1,
          "methodVersion": 1,
          "buildMode": "debug-instrumentation",
          "thresholdApplied": false,
          "sourceCommit": "$SOURCE_COMMIT",
          "runnerLabel": "android-tv-api36-x86_64",
          "workload": {
            "warmupSamples": 2,
            "measuredSamples": 5,
            "operationsPerSample": 1000
          },
          "requestProfileSha256": "$PLAYER_PROFILE_SHA",
          "environment": {
            "manufacturer": "Google",
            "model": "sdk_google_atv64_x86_64",
            "fingerprint": "google/sdk/google:36/test-keys",
            "apiLevel": 36,
            "supportedAbis": ["x86_64"],
            "lowRamDevice": false,
            "memoryClassMb": 192,
            "availableProcessors": 2
          },
          "operations": [
            ${playerOperation("request-construct", 10, successfulResultCount)},
            ${playerOperation("setup-envelope-roundtrip", 20, successfulResultCount)},
            ${playerOperation("coordinator-install-active-clear", 30, successfulResultCount)},
            ${playerOperation("coordinator-cancel-before-install", 40, successfulResultCount)},
            ${playerOperation("registry-disconnect-reacquire", 50, successfulResultCount)}
          ],
          "failureCount": 0,
          "limitations": ["Control-plane proxy evidence only."]
        }
    """.trimIndent()

    private fun roomOperation(
        id: String,
        expectedResultCount: Int,
        base: Long,
    ): String = """
        {
          "operationId": "$id",
          "expectedResultCount": $expectedResultCount,
          "wallTimeNanos": ${summaryJson(5, base, base + 20, base + 40)},
          "databaseBytes": ${summaryJson(5, 1000, 1200, 1400)},
          "walBytes": ${summaryJson(5, 0, 0, 0)},
          "shmBytes": ${summaryJson(5, 0, 0, 0)},
          "rawSamples": [
            {"iteration": 1, "wallTimeNanos": $base, "resultCount": $expectedResultCount, "databaseBytes": 1000, "walBytes": 0, "shmBytes": 0},
            {"iteration": 2, "wallTimeNanos": ${base + 10}, "resultCount": $expectedResultCount, "databaseBytes": 1100, "walBytes": 0, "shmBytes": 0},
            {"iteration": 3, "wallTimeNanos": ${base + 20}, "resultCount": $expectedResultCount, "databaseBytes": 1200, "walBytes": 0, "shmBytes": 0},
            {"iteration": 4, "wallTimeNanos": ${base + 30}, "resultCount": $expectedResultCount, "databaseBytes": 1300, "walBytes": 0, "shmBytes": 0},
            {"iteration": 5, "wallTimeNanos": ${base + 40}, "resultCount": $expectedResultCount, "databaseBytes": 1400, "walBytes": 0, "shmBytes": 0}
          ]
        }
    """.trimIndent()

    private fun playerOperation(
        id: String,
        base: Long,
        successfulResultCount: Int,
    ): String = """
        {
          "operationId": "$id",
          "expectedSuccessfulResultCount": 1000,
          "batchWallTimeNanos": ${summaryJson(5, base * 1000, (base + 2) * 1000, (base + 4) * 1000)},
          "normalizedNanosPerOperation": ${summaryJson(5, base, base + 2, base + 4)},
          "rawSamples": [
            {"sampleIndex": 1, "batchWallTimeNanos": ${base * 1000}, "operationCount": 1000, "normalizedNanosPerOperation": $base, "successfulResultCount": $successfulResultCount},
            {"sampleIndex": 2, "batchWallTimeNanos": ${(base + 1) * 1000}, "operationCount": 1000, "normalizedNanosPerOperation": ${base + 1}, "successfulResultCount": $successfulResultCount},
            {"sampleIndex": 3, "batchWallTimeNanos": ${(base + 2) * 1000}, "operationCount": 1000, "normalizedNanosPerOperation": ${base + 2}, "successfulResultCount": $successfulResultCount},
            {"sampleIndex": 4, "batchWallTimeNanos": ${(base + 3) * 1000}, "operationCount": 1000, "normalizedNanosPerOperation": ${base + 3}, "successfulResultCount": $successfulResultCount},
            {"sampleIndex": 5, "batchWallTimeNanos": ${(base + 4) * 1000}, "operationCount": 1000, "normalizedNanosPerOperation": ${base + 4}, "successfulResultCount": $successfulResultCount}
          ]
        }
    """.trimIndent()

    private fun summaryJson(
        count: Int,
        minimum: Long,
        p50: Long,
        maximum: Long,
    ): String = """
        {
          "sampleCount": $count,
          "minimum": $minimum,
          "p50": $p50,
          "p90": $maximum,
          "p95": $maximum,
          "maximum": $maximum
        }
    """.trimIndent()

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
        val FIXTURE_SHA = "0f".repeat(32)
        val PLAYER_PROFILE_SHA = "1a".repeat(32)
        val ROOM_OPERATIONS = listOf(
            "stage-batch-250",
            "stage-total-10k",
            "activate-10k",
            "active-channel-first-page",
            "source-overview-32",
        )
        val PLAYER_OPERATIONS = listOf(
            "request-construct",
            "setup-envelope-roundtrip",
            "coordinator-install-active-clear",
            "coordinator-cancel-before-install",
            "registry-disconnect-reacquire",
        )
    }
}
