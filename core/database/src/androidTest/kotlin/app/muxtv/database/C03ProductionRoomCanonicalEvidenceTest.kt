package app.muxtv.database

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.database.measurement.C03ProductionMeasurementVariant
import app.muxtv.database.measurement.C03ProductionRoomEvidenceArguments
import app.muxtv.database.measurement.C03ProductionRoomEvidenceRunner
import app.muxtv.database.measurement.C03ProductionRoomMeasurementJsonWriter
import app.muxtv.database.measurement.C03ProductionRoomMeasurementReportPublisher
import app.muxtv.database.measurement.C03ProductionScenario
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@CatalogDatabaseMeasurement
@RunWith(AndroidJUnit4::class)
class C03ProductionRoomCanonicalEvidenceTest {
    @Test
    fun producesCanonicalProductionRoomEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = C03ProductionRoomEvidenceArguments.parse(
            InstrumentationRegistry.getArguments(),
        )
        val report = C03ProductionRoomEvidenceRunner(
            instrumentation.targetContext,
        ).run(arguments.spec)

        assertThat(report.schemaVersion).isEqualTo(1)
        assertThat(report.methodVersion).contains("physical-mutations")
        assertThat(report.sourceCommit).isEqualTo(arguments.spec.sourceCommit)
        assertThat(report.warmupIterations).isEqualTo(1)
        assertThat(report.measuredIterations).isAtLeast(5)
        assertThat(report.entryCount).isEqualTo(10_000)
        assertThat(report.redactionPassed).isTrue()
        assertThat(report.scenarios.map { it.scenarioId }).containsExactly(
            "delta-0",
            "delta-1",
            "delta-10",
            "delta-100",
            "reorder",
            "remove-10",
            "token-churn",
        ).inOrder()

        report.scenarios.forEach { scenario ->
            assertThat(scenario.variants.map { it.variant }).containsExactly(
                C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION,
                C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE,
            ).inOrder()
            scenario.variants.forEach { variant ->
                assertThat(variant.samples).hasSize(report.measuredIterations)
                assertThat(variant.samples.all { it.searchNanos > 0L }).isTrue()
                assertThat(variant.correctnessCount).isEqualTo(scenario.expectedCorrectnessCount)
                assertThat(variant.correctnessDigestSha256)
                    .isEqualTo(scenario.expectedCorrectnessDigestSha256)
            }
            val candidate = scenario.variants.single {
                it.variant == C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE
            }
            assertThat(candidate.queryPlans).isNotEmpty()
            assertThat(candidate.queryPlans.all { it.indexed }).isTrue()
        }

        assertThat(report.repeatedRevisionStorage.map { it.variant to it.revisionCount })
            .containsExactly(
                C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION to 5,
                C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE to 5,
                C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION to 10,
                C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE to 10,
                C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION to 20,
                C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE to 20,
            )

        assertThat(report.safety.map { it.variant }).containsExactly(
            C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION,
            C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE,
        )
        report.safety.forEach { safety ->
            assertThat(safety.previousGoodPreserved).isTrue()
            assertThat(safety.supersededRejected).isTrue()
            assertThat(safety.partialFailurePublished).isFalse()
            assertThat(safety.cancellationLatePublished).isFalse()
            assertThat(safety.cleanupBounded).isTrue()
            assertThat(safety.cancellationCleanupNanos).isGreaterThan(0L)
        }

        val published = C03ProductionRoomMeasurementReportPublisher.publish(
            context = instrumentation.context,
            report = report,
            outputName = arguments.outputName,
        )
        assertThat(published.isFile).isTrue()

        val output = ByteArrayOutputStream()
        C03ProductionRoomMeasurementJsonWriter.write(report, output)
        val json = output.toString(Charsets.UTF_8.name())
        assertThat(json).doesNotContain("https://stream.invalid")
        assertThat(json).doesNotContain("token=")
        assertThat(json).doesNotContain("session=")

        instrumentation.addResults(
            Bundle().apply {
                putString(
                    RESULT_REPORT_BASE64,
                    Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP),
                )
            },
        )
    }

    companion object {
        const val RESULT_REPORT_BASE64 = "c03ProductionRoomEvidenceReportBase64"
    }
}
