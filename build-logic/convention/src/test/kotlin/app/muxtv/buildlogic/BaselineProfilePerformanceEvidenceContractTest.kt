package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaselineProfilePerformanceEvidenceContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release compares cold startup with and without required Baseline Profile`() {
        val comparison = repositoryRoot.resolve(
            "benchmark/macrobenchmark/src/main/kotlin/app/muxtv/benchmark/BaselineProfilePerformanceComparison.kt",
        )
        assertTrue(comparison.isFile, "Missing dedicated Baseline Profile performance comparison benchmark.")
        val comparisonText = comparison.readText()
        listOf(
            "coldStartupWithoutBaselineProfile",
            "coldStartupWithBaselineProfile",
            "StartupTimingMetric()",
            "StartupMode.COLD",
            "CompilationMode.None()",
            "CompilationMode.Partial(",
            "BaselineProfileMode.Require",
            "iterations = 10",
            "measureRepeated",
        ).forEach { token -> assertContains(comparisonText, token) }
        assertFalse(
            comparisonText.contains("BaselineProfileMode.UseIfAvailable"),
            "Release comparison must fail closed when the packaged Baseline Profile is unavailable.",
        )

        val runner = repositoryRoot.resolve("tools/ci/Run-HostedBaselineProfilePerformance.sh")
        assertTrue(runner.isFile, "Missing hosted Baseline Profile performance runner.")
        val runnerText = runner.readText()
        listOf(
            "connectedBenchmarkReleaseAndroidTest",
            "BaselineProfilePerformanceComparison",
            "androidx.benchmark.enabledRules=Macrobenchmark",
            "Assert-AndroidTestResults.ps1",
            "MUXTV_BENCHMARK_EVIDENCE",
            "source_commit",
        ).forEach { token -> assertContains(runnerText, token) }
        assertFalse(
            runnerText.contains("androidx.benchmark.dryRunMode.enable=true"),
            "Release performance evidence must execute real Macrobenchmark iterations, not dry-run mode.",
        )

        val workflow = repositoryRoot.resolve(".github/workflows/release-baseline-profile-performance.yml")
        assertTrue(workflow.isFile, "Missing hosted Baseline Profile performance evidence workflow.")
        val workflowText = workflow.readText()
        listOf(
            "workflow_dispatch:",
            "pull_request:",
            "runs-on: ubuntu-latest",
            "persist-credentials: false",
            "uses: ./.github/actions/setup-muxtv-jdks",
            "Enable-HostedAndroidKvm.sh",
            "ReactiveCircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d",
            "api-level: 36",
            "target: android-tv",
            "avd-name: MuxTV_TV_CURRENT_API36",
            "Run-HostedBaselineProfilePerformance.sh",
            "uses: ./.github/actions/upload-evidence-with-retry",
            "benchmark/macrobenchmark/build/outputs/connected_android_test_additional_output/**",
            ".work/evidence/release-baseline-profile-performance/**",
        ).forEach { token -> assertContains(workflowText, token) }
        assertFalse(
            Regex("(?m)^\\s*uses:\\s*actions/upload-artifact@").containsMatchIn(workflowText),
            "Performance evidence must use the shared bounded artifact uploader.",
        )
    }
}
