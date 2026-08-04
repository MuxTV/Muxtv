package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeasurementRoomV2AdapterTest {
    @Test
    fun `Room method v2 keeps Search operations in comparison identity`() {
        val adapted = MeasurementReportAdapter.adapt(
            MeasurementAdaptationRequest(
                family = MeasurementReportFamily.CATALOG_DATABASE,
                repetitionId = "room-v2-01",
                reportBytes = validRoomV2Report().toByteArray(Charsets.UTF_8),
                androidProfile = AndroidMeasurementProfileContext(
                    requestedApiLevel = 36,
                    systemImage = "system-images;android-36;android-tv;x86_64",
                    configuredRamMb = 2048,
                    configuredCpuCores = 2,
                    fallbackUsed = false,
                ),
            ),
        )

        assertThat(adapted.identity.methodVersion).isEqualTo(2)
        assertThat(adapted.run.operations.keys).containsExactly(
            "activate-10k",
            "active-channel-first-page",
            "search-exact-number-10k",
            "search-selective-seed-10k",
            "source-overview-32",
            "stage-batch-250",
            "stage-total-10k",
        ).inOrder()
        assertThat(adapted.run.operations.getValue("search-exact-number-10k"))
            .containsExactly(500L, 510L, 520L, 530L, 540L).inOrder()
        assertThat(adapted.run.operations.getValue("search-selective-seed-10k"))
            .containsExactly(600L, 610L, 620L, 630L, 640L).inOrder()
    }

    private fun validRoomV2Report(): String = """
        {
          "schemaVersion": 1,
          "methodVersion": 2,
          "buildMode": "debug-instrumentation",
          "thresholdApplied": false,
          "sourceCommit": "0123456789abcdef0123456789abcdef01234567",
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
          "fixture": {
            "entryCount": 10000,
            "sha256": "${"0f".repeat(32)}"
          },
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
            ${roomOperation("search-exact-number-10k", 1, 500)},
            ${roomOperation("search-selective-seed-10k", 1, 600)},
            ${roomOperation("source-overview-32", 32, 700)}
          ],
          "failureCount": 0,
          "limitations": ["descriptive debug-instrumentation evidence only"]
        }
    """.trimIndent()

    private fun roomOperation(
        operationId: String,
        expectedResultCount: Int,
        baseWallTime: Int,
    ): String {
        val samples = (1..5).joinToString(",\n") { iteration ->
            val wallTime = baseWallTime + (iteration - 1) * 10
            """
                {
                  "iteration": $iteration,
                  "wallTimeNanos": $wallTime,
                  "resultCount": $expectedResultCount,
                  "databaseBytes": 1000,
                  "walBytes": 0,
                  "shmBytes": 0
                }
            """.trimIndent()
        }
        return """
            {
              "operationId": "$operationId",
              "expectedResultCount": $expectedResultCount,
              "wallTimeNanos": ${summaryJson(baseWallTime)},
              "databaseBytes": ${constantSummaryJson(1000)},
              "walBytes": ${constantSummaryJson(0)},
              "shmBytes": ${constantSummaryJson(0)},
              "rawSamples": [
                $samples
              ]
            }
        """.trimIndent()
    }

    private fun summaryJson(baseWallTime: Int): String {
        val values = (0..4).map { baseWallTime + it * 10 }
        return """
            {
              "sampleCount": ${values.size},
              "minimum": ${values.first()},
              "p50": ${values[2]},
              "p90": ${values.last()},
              "p95": ${values.last()},
              "maximum": ${values.last()}
            }
        """.trimIndent()
    }

    private fun constantSummaryJson(value: Int): String = """
        {
          "sampleCount": 5,
          "minimum": $value,
          "p50": $value,
          "p90": $value,
          "p95": $value,
          "maximum": $value
        }
    """.trimIndent()
}
