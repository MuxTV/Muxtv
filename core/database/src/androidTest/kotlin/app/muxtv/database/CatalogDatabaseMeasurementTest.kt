package app.muxtv.database

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.database.measurement.CatalogDatabaseMeasurementArguments
import app.muxtv.database.measurement.CatalogDatabaseMeasurementJsonWriter
import app.muxtv.database.measurement.CatalogDatabaseMeasurementReportPublisher
import app.muxtv.database.measurement.CatalogDatabaseMeasurementRunnerV4
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@CatalogDatabaseMeasurement
@RunWith(AndroidJUnit4::class)
class CatalogDatabaseMeasurementTest {
    @Test
    fun producesThresholdFreeRoomStageActivateAndQueryEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = CatalogDatabaseMeasurementArguments.parse(InstrumentationRegistry.getArguments())
        val report = CatalogDatabaseMeasurementRunnerV4(
            context = instrumentation.targetContext,
            progress = { message -> Log.i(PROGRESS_TAG, message) },
        ).run(arguments.spec)

        assertThat(report.thresholdApplied).isFalse()
        assertThat(report.failureCount).isEqualTo(0)
        assertThat(report.operations.map { it.operationId })
            .containsExactlyElementsIn(EXPECTED_RESULT_COUNTS.keys)
            .inOrder()
        assertThat(report.operations.all { it.samples.size == arguments.spec.workload.measuredIterations }).isTrue()
        assertThat(report.operations.associate { it.operationId to it.expectedResultCount })
            .containsExactlyEntriesIn(EXPECTED_RESULT_COUNTS)
        assertThat(report.queryPlans.map { it.operationId }).containsExactly(
            "search-candidate-resolution",
            "search-summary-materialization-ranking",
            "search-published-now-next",
        ).inOrder()

        val published = CatalogDatabaseMeasurementReportPublisher.publish(
            context = instrumentation.context,
            report = report,
            outputName = arguments.outputName,
        )
        assertThat(published.isFile).isTrue()
        assertThat(published.name).isEqualTo(arguments.outputName)

        val output = ByteArrayOutputStream()
        CatalogDatabaseMeasurementJsonWriter.write(report, output)
        val encodedReport = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        instrumentation.addResults(
            Bundle().apply {
                putString(RESULT_REPORT_BASE64, encodedReport)
            },
        )
    }

    private companion object {
        const val PROGRESS_TAG = "MuxTvM0Measurement"
        const val RESULT_REPORT_BASE64 = "catalogDatabaseMeasurementReportBase64"
        val EXPECTED_RESULT_COUNTS = buildMap {
            put("stage-batch-250", 250)
            put("stage-total-50k", 50_000)
            put("activate-50k", 50_000)
            put("active-channel-first-page", 100)
            put("source-overview-32", 32)
            val scenarios = linkedMapOf(
                "search-exact-number" to listOf(1, 1, 1),
                "search-selective-multi-token" to listOf(803, 1, 1),
                "search-broad-multi-token" to listOf(2_402, 800, 100),
                "search-broad-top-100" to listOf(801, 800, 100),
                "search-programme-title" to listOf(1, 1, 1),
                "search-cross-document" to listOf(803, 1, 1),
            )
            val phases = listOf(
                "candidate-resolution",
                "summary-materialization-ranking",
                "published-now-next",
            )
            scenarios.forEach { (scenario, counts) ->
                phases.forEachIndexed { index, phase -> put("$scenario-$phase", counts[index]) }
            }
        }
    }
}
