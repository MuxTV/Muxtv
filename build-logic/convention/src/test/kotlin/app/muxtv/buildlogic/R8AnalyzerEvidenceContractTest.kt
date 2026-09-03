package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class R8AnalyzerEvidenceContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release archives exact-head R8 configuration analyzer evidence`() {
        val versions = repositoryRoot.resolve("gradle/libs.versions.toml").readText()
        assertContains(versions, "agp = \"9.3.0\"")

        val script = repositoryRoot.resolve("tools/release/Invoke-R8ConfigAnalyzerEvidence.ps1")
        assertTrue(script.isFile, "Missing release R8 Configuration Analyzer evidence entry point.")
        val scriptText = script.readText()
        listOf(
            "Assert-EvidenceCommit.ps1",
            ":app:tv:analyzeReleaseR8Config",
            "app\\tv\\build\\reports\\r8\\r8-config-analyzer-release.html",
            "Get-FileHash",
            "SHA256",
            "sourceCommit",
            "reportSha256",
            "r8-config-analyzer.json",
        ).forEach { token -> assertContains(scriptText, token) }
        assertFalse(
            scriptText.contains("muxtv.keep"),
            "Analyzer evidence collection must not mutate or rewrite keep rules.",
        )

        val workflow = repositoryRoot.resolve(".github/workflows/release-r8-config-analyzer.yml")
        assertTrue(workflow.isFile, "Missing hosted R8 Configuration Analyzer evidence workflow.")
        val workflowText = workflow.readText()
        listOf(
            "workflow_dispatch:",
            "pull_request:",
            "runs-on: windows-latest",
            "ref: \${{ github.sha }}",
            "persist-credentials: false",
            "uses: ./.github/actions/setup-muxtv-jdks",
            "Invoke-R8ConfigAnalyzerEvidence.ps1",
            "-SourceCommit \"\${{ github.sha }}\"",
            "uses: ./.github/actions/upload-evidence-with-retry",
            "app/tv/build/reports/r8/r8-config-analyzer-release.html",
            ".work/evidence/release-r8/**",
        ).forEach { token -> assertContains(workflowText, token) }
        assertFalse(
            Regex("(?m)^\\s*uses:\\s*actions/upload-artifact@").containsMatchIn(workflowText),
            "R8 evidence must use the shared bounded artifact uploader.",
        )
    }
}
