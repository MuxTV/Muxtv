package app.muxtv.database

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.database.measurement.CatalogDatabaseMeasurementArguments
import app.muxtv.database.measurement.CatalogDatabaseMeasurementJsonWriter
import app.muxtv.database.measurement.CatalogDatabaseMeasurementReportPublisher
import app.muxtv.database.measurement.CatalogDatabaseMeasurementRunner
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
        val report = CatalogDatabaseMeasurementRunner(
            context = instrumentation.targetContext,
        ).run(arguments.spec)

        assertThat(report.thresholdApplied).isFalse()
        assertThat(report.failureCount).isEqualTo(0)
        assertThat(report.operations.map { it.operationId }).containsExactly(
            "stage-batch-250",
            "stage-total-10k",
            "activate-10k",
            "active-channel-first-page",
            "source-overview-32",
        ).inOrder()
        assertThat(report.operations.all { it.samples.size == arguments.spec.workload.measuredIterations }).isTrue()
        assertThat(report.operations.single { it.operationId == "stage-batch-250" }.expectedResultCount)
            .isEqualTo(250)
        assertThat(report.operations.single { it.operationId == "stage-total-10k" }.expectedResultCount)
            .isEqualTo(arguments.spec.workload.entryCount)
        assertThat(report.operations.single { it.operationId == "activate-10k" }.expectedResultCount)
            .isEqualTo(arguments.spec.workload.entryCount)
        assertThat(report.operations.single { it.operationId == "active-channel-first-page" }.expectedResultCount)
            .isEqualTo(arguments.spec.workload.firstPageLimit)
        assertThat(report.operations.single { it.operationId == "source-overview-32" }.expectedResultCount)
            .isEqualTo(arguments.spec.workload.sourceOverviewCount)

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
        const val RESULT_REPORT_BASE64 = "catalogDatabaseMeasurementReportBase64"
    }
}
