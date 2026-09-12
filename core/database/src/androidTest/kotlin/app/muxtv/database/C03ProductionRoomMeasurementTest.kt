package app.muxtv.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.database.measurement.C03ProductionMeasurementVariant
import app.muxtv.database.measurement.C03ProductionRoomMeasurementRunner
import app.muxtv.database.measurement.C03ProductionRoomMeasurementSpec
import app.muxtv.database.measurement.C03ProductionScenario
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class C03ProductionRoomMeasurementTest {
    @Test
    fun smokeProducesThresholdFreeFileBackedAbEvidence() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
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

        val report = C03ProductionRoomMeasurementRunner(context).run(spec)

        assertThat(report.schemaVersion).isEqualTo(1)
        assertThat(report.methodVersion).isNotEmpty()
        assertThat(report.sourceCommit).isEqualTo(spec.sourceCommit)
        assertThat(report.warmupIterations).isEqualTo(0)
        assertThat(report.measuredIterations).isEqualTo(1)
        assertThat(report.entryCount).isEqualTo(64)
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
        }
    }
}
