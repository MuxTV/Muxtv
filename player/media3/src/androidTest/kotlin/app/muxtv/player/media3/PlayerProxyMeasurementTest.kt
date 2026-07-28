package app.muxtv.player.media3

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@PlayerProxyMeasurement
@RunWith(AndroidJUnit4::class)
class PlayerProxyMeasurementTest {
    @Test
    fun producesThresholdFreeRequestSetupAndReconnectProxyEvidence() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = PlayerProxyMeasurementArguments.parse(InstrumentationRegistry.getArguments())
        val report = PlayerProxyMeasurementRunner(
            context = instrumentation.targetContext,
        ).run(arguments.spec)

        assertThat(report.buildMode).isEqualTo("debug-instrumentation")
        assertThat(report.thresholdApplied).isFalse()
        assertThat(report.failureCount).isEqualTo(0)
        assertThat(report.operations.map { it.operationId }).containsExactly(
            "request-construct",
            "setup-envelope-roundtrip",
            "coordinator-install-active-clear",
            "coordinator-cancel-before-install",
            "registry-disconnect-reacquire",
        ).inOrder()
        assertThat(report.operations).allSatisfy { operation ->
            assertThat(operation.samples).hasSize(arguments.spec.workload.measuredSamples)
            assertThat(operation.expectedSuccessfulResultCount)
                .isEqualTo(arguments.spec.workload.operationsPerSample)
            assertThat(operation.samples).allSatisfy { sample ->
                assertThat(sample.operationCount).isEqualTo(arguments.spec.workload.operationsPerSample)
                assertThat(sample.successfulResultCount).isEqualTo(arguments.spec.workload.operationsPerSample)
                assertThat(sample.batchWallTimeNanos).isGreaterThan(0L)
                assertThat(sample.normalizedNanosPerOperation).isGreaterThan(0L)
            }
        }

        val encoded = PlayerProxyMeasurementResultPublisher.publish(
            instrumentation = instrumentation,
            report = report,
        )
        assertThat(encoded).isNotEmpty()
    }
}
