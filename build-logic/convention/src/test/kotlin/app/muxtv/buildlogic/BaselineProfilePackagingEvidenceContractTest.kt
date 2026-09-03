package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BaselineProfilePackagingEvidenceContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release packages committed baseline profile into exact-head release apk`() {
        val appBuild = repositoryRoot.resolve("app/tv/build.gradle.kts").readText()
        listOf(
            "libs.plugins.androidx.baselineprofile",
            "baselineProfile(project(\":benchmark:macrobenchmark\"))",
            "automaticGenerationDuringBuild = false",
            "saveInSrc = true",
            "optimization {",
            "enable = true",
        ).forEach { token -> assertContains(appBuild, token) }

        val sourceProfile = repositoryRoot.resolve(
            "app/tv/src/release/generated/baselineProfiles/baseline-prof.txt",
        )
        assertTrue(sourceProfile.isFile, "Missing committed release Baseline Profile.")
        assertTrue(sourceProfile.length() > 0L, "Committed release Baseline Profile is empty.")

        val script = repositoryRoot.resolve("tools/release/Invoke-BaselineProfilePackagingEvidence.ps1")
        assertTrue(script.isFile, "Missing Baseline Profile packaging evidence entry point.")
        val scriptText = script.readText()
        listOf(
            "Assert-EvidenceCommit.ps1",
            ":app:tv:assembleRelease",
            "app\\tv\\src\\release\\generated\\baselineProfiles\\baseline-prof.txt",
            "assets/dexopt/baseline.prof",
            "assets/dexopt/baseline.profm",
            "Get-FileHash",
            "SHA256",
            "sourceCommit",
            "sourceProfileSha256",
            "apkSha256",
            "packagedProfileSha256",
            "packagedProfileMetadataSha256",
            "baseline-profile-packaging.json",
        ).forEach { token -> assertContains(scriptText, token) }
        assertFalse(
            scriptText.contains("generateReleaseBaselineProfile"),
            "Packaging evidence must verify the committed profile, not regenerate it while building the release artifact.",
        )

        val workflow = repositoryRoot.resolve(".github/workflows/release-baseline-profile-packaging.yml")
        assertTrue(workflow.isFile, "Missing hosted Baseline Profile packaging evidence workflow.")
        val workflowText = workflow.readText()
        listOf(
            "workflow_dispatch:",
            "pull_request:",
            "runs-on: windows-latest",
            "persist-credentials: false",
            "uses: ./.github/actions/setup-muxtv-jdks",
            "Initialize-AndroidSdkEnvironment.ps1",
            "Invoke-BaselineProfilePackagingEvidence.ps1",
            "uses: ./.github/actions/upload-evidence-with-retry",
            "app/tv/build/outputs/apk/release/**",
            ".work/evidence/release-baseline-profile/**",
        ).forEach { token -> assertContains(workflowText, token) }
        assertFalse(
            Regex("(?m)^\\s*uses:\\s*actions/upload-artifact@").containsMatchIn(workflowText),
            "Baseline Profile evidence must use the shared bounded artifact uploader.",
        )
    }
}
