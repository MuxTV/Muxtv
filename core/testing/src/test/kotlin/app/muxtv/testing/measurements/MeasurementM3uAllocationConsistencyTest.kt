package app.muxtv.testing.measurements

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MeasurementM3uAllocationConsistencyTest {
    @Test
    fun `thread allocation mode requires complete allocation samples and summary`() {
        val missingSummary = report(
            allocationMeasurement = "thread-allocated-bytes",
            allocationSummary = "null",
            allocatedBytes = "null",
        )

        assertInvalid(missingSummary)
    }

    @Test
    fun `unavailable allocation mode forbids allocation samples and summary`() {
        val unexpectedValues = report(
            allocationMeasurement = "unavailable",
            allocationSummary = summaryJson(5, 10, 10, 10),
            allocatedBytes = "10",
        )

        assertInvalid(unexpectedValues)
    }

    @Test
    fun `complete allocation data contributes only to runtime method identity`() {
        val adapted = MeasurementReportAdapter.adapt(
            request(
                report(
                    allocationMeasurement = "thread-allocated-bytes",
                    allocationSummary = summaryJson(5, 10, 10, 10),
                    allocatedBytes = "10",
                ),
            ),
        )

        assertThat(adapted.identity.runtimeIdentity)
            .containsEntry("allocation-measurement", "thread-allocated-bytes")
        assertThat(adapted.run.operations).containsKey("parse-wall-time")
        assertThat(adapted.run.operations).doesNotContainKey("allocated-bytes")
    }

    private fun assertInvalid(json: String) {
        val failure = assertThrows(MeasurementReportAdaptationException::class.java) {
            MeasurementReportAdapter.adapt(request(json))
        }
        assertThat(failure.code).isEqualTo(MeasurementReportAdaptationFailure.INVALID_REPORT)
    }

    private fun request(json: String): MeasurementAdaptationRequest = MeasurementAdaptationRequest(
        family = MeasurementReportFamily.M3U_PARSE,
        repetitionId = "host-allocation-01",
        reportBytes = json.toByteArray(Charsets.UTF_8),
    )

    private fun report(
        allocationMeasurement: String,
        allocationSummary: String,
        allocatedBytes: String,
    ): String = """
        {
          "schemaVersion": 1,
          "methodVersion": 1,
          "thresholdApplied": false,
          "runnerLabel": "self-hosted-windows-x64",
          "sourceCommit": "0123456789abcdef0123456789abcdef01234567",
          "profile": "small-1k",
          "seed": 20260728,
          "warmupIterations": 2,
          "measuredIterations": 5,
          "measurementScope": "local-file-open-plus-streaming-parser-no-retention-sink",
          "corpus": {
            "utf8ByteCount": 269079,
            "sha256": "${"0f".repeat(32)}"
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
            "allocationMeasurement": "$allocationMeasurement"
          },
          "wallTimeSummaryNanos": ${summaryJson(5, 100, 120, 140)},
          "allocationSummaryBytes": $allocationSummary,
          "rawSamples": [
            {"iteration": 1, "wallTimeNanos": 100, "allocatedBytes": $allocatedBytes},
            {"iteration": 2, "wallTimeNanos": 110, "allocatedBytes": $allocatedBytes},
            {"iteration": 3, "wallTimeNanos": 120, "allocatedBytes": $allocatedBytes},
            {"iteration": 4, "wallTimeNanos": 130, "allocatedBytes": $allocatedBytes},
            {"iteration": 5, "wallTimeNanos": 140, "allocatedBytes": $allocatedBytes}
          ],
          "failureCount": 0
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
}
