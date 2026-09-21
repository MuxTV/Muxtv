package app.muxtv.database

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.database.measurement.C03ProductionMeasurementVariant
import app.muxtv.database.measurement.C03ProductionRoomEvidenceArguments
import app.muxtv.database.measurement.C03ProductionRoomEvidenceRunner
import app.muxtv.database.measurement.C03ProductionRoomMeasurementJsonWriter
import app.muxtv.database.measurement.C03ProductionRoomMeasurementReportPublisher
import app.muxtv.database.measurement.C03ProductionRoomMeasurementSpec
import app.muxtv.database.measurement.C03ProductionScenario
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C03ProductionRoomMeasurementTest {
    @Test
    fun smokeProducesThresholdFreeFileBackedAbEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val spec = C03ProductionRoomMeasurementSpec(
            sourceCommit = "instrumentation-smoke",
            warmupIterations = 0,
            measuredIterations = 1,
            entryCount = 64,
            scenarios = listOf(
                C03ProductionScenario.DELTA_0,
                C03ProductionScenario.REORDER,
                C03ProductionScenario.TOKEN_CHURN,
            ),
        )

        val report = C03ProductionRoomEvidenceRunner(context).run(spec)

        assertThat(report.schemaVersion).isEqualTo(2)
        assertThat(report.methodVersion).contains("physical-mutations")
        assertThat(report.sourceCommit).isEqualTo(spec.sourceCommit)
        assertThat(report.warmupIterations).isEqualTo(0)
        assertThat(report.measuredIterations).isEqualTo(1)
        assertThat(report.entryCount).isEqualTo(64)
        assertThat(report.batchSize).isEqualTo(250)
        assertThat(report.redactionPassed).isTrue()
        assertThat(report.scenarios.map { it.scenarioId }).containsExactly(
            "delta-0",
            "reorder",
            "token-churn",
        ).inOrder()

        report.scenarios.forEach { scenario ->
            assertThat(scenario.variants.map { it.variant }).containsExactly(
                C03ProductionMeasurementVariant.A_CURRENT_PRODUCTION,
                C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE,
            ).inOrder()
            assertThat(scenario.variants.all { it.samples.size == 1 }).isTrue()
            assertThat(scenario.variants.all { it.correctnessCount == scenario.expectedCorrectnessCount }).isTrue()
            assertThat(scenario.variants.all {
                it.correctnessDigestSha256 == scenario.expectedCorrectnessDigestSha256
            }).isTrue()
            assertThat(scenario.variants.all { it.writes.sourceMetadataWrites > 0L }).isTrue()
            assertThat(scenario.variants.all { it.samples.all { sample -> sample.searchNanos > 0L } }).isTrue()

            val candidate = scenario.variants.single {
                it.variant == C03ProductionMeasurementVariant.B_IMMUTABLE_REUSE
            }
            assertThat(candidate.queryPlans).isNotEmpty()
            assertThat(candidate.queryPlans.all { it.indexed }).isTrue()
            assertThat(
                candidate.queryPlans.flatMap { it.details }.all { detail ->
                    !detail.contains("https://") &&
                        !detail.contains("token=") &&
                        !detail.contains("session=")
                },
            ).isTrue()
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
        report.repeatedRevisionStorage.forEach { storage ->
            assertThat(storage.compactionNanos).isAtLeast(0L)
            assertThat(storage.orphanPayloadRows).isEqualTo(0)
            assertThat(storage.orphanSearchPayloadRows).isEqualTo(0)
        }

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

        val output = ByteArrayOutputStream()
        C03ProductionRoomMeasurementJsonWriter.write(report, output)
        val json = output.toString(Charsets.UTF_8.name())
        assertThat(json).contains("\"sourceCommit\": \"instrumentation-smoke\"")
        assertThat(json).contains("\"redactionPassed\": true")
        assertThat(json).doesNotContain("https://")

        val published = C03ProductionRoomMeasurementReportPublisher.publish(
            context = instrumentation.context,
            report = report,
            outputName = "c03-production-room-smoke.json",
        )
        assertThat(published.isFile).isTrue()
        assertThat(published.length()).isGreaterThan(0L)
    }

    @Test
    fun canonicalEvidenceArgumentsUseThePreregisteredRegularMatrix() {
        val arguments = C03ProductionRoomEvidenceArguments.parse(
            Bundle().apply {
                putString(C03ProductionRoomEvidenceArguments.ARGUMENT_SOURCE_COMMIT, SOURCE_COMMIT)
                putString(C03ProductionRoomEvidenceArguments.ARGUMENT_WARMUPS, "1")
                putString(C03ProductionRoomEvidenceArguments.ARGUMENT_ITERATIONS, "5")
                putString(C03ProductionRoomEvidenceArguments.ARGUMENT_ENTRY_COUNT, "10000")
                putString(
                    C03ProductionRoomEvidenceArguments.ARGUMENT_OUTPUT_NAME,
                    "c03-production-room-evidence.json",
                )
            },
        )

        assertThat(arguments.spec.sourceCommit).isEqualTo(SOURCE_COMMIT)
        assertThat(arguments.spec.warmupIterations).isEqualTo(1)
        assertThat(arguments.spec.measuredIterations).isEqualTo(5)
        assertThat(arguments.spec.entryCount).isEqualTo(10_000)
        assertThat(arguments.spec.scenarios).containsExactlyElementsIn(C03ProductionScenario.entries).inOrder()
        assertThat(arguments.outputName).isEqualTo("c03-production-room-evidence.json")
    }

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
