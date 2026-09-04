package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseLicenseReportEvidenceContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release derives fail closed third party license report from exact head SBOM`() {
        val overrides = repositoryRoot.resolve("config/release/license-overrides.json")
        assertTrue(overrides.isFile, "Missing curated release license override file.")
        val overridesText = overrides.readText()
        listOf(
            "androidx.media3:media3-ui-compose:1.11.0",
            "Apache-2.0",
            "android.googlesource.com/platform/frameworks/support",
            "CycloneDX POM metadata-resolution fallback only",
        ).forEach { token -> assertContains(overridesText, token) }

        val script = repositoryRoot.resolve("tools/release/Invoke-ReleaseLicenseReportEvidence.ps1")
        assertTrue(script.isFile, "Missing release license report evidence entry point.")
        val scriptText = script.readText()
        listOf(
            "Assert-EvidenceCommit.ps1",
            "Invoke-ReleaseSbomEvidence.ps1",
            "muxtv-tv-release.cdx.json",
            "ConvertFrom-Json",
            "project_path",
            "project_path=",
            "UnescapeDataString",
            "MuxTV.",
            "licenses",
            "expression",
            "licenseSource",
            "curated-override",
            "thirdPartyComponentCount",
            "unknownThirdPartyLicenseCount",
            "firstPartyComponentCount",
            "release-license-report.json",
            "release-license-report.md",
            "Get-FileHash",
            "SHA256",
            "Unknown third-party licenses remain",
        ).forEach { token -> assertContains(scriptText, token) }
        assertFalse(
            scriptText.contains("androidx.*Apache-2.0"),
            "License evidence must not use a blanket AndroidX license assumption.",
        )

        val workflow = repositoryRoot.resolve(".github/workflows/release-license-report-evidence.yml")
        assertTrue(workflow.isFile, "Missing hosted release license report workflow.")
        val workflowText = workflow.readText()
        listOf(
            "workflow_dispatch:",
            "pull_request:",
            "contents: read",
            "actions: read",
            "github.event.pull_request.head.sha",
            "persist-credentials: false",
            "cancel-in-progress: true",
            "uses: ./.github/actions/setup-muxtv-jdks",
            "Initialize-AndroidSdkEnvironment.ps1",
            "Invoke-ReleaseLicenseReportEvidence.ps1",
            "continue-on-error: true",
            "if: always()",
            "uses: ./.github/actions/upload-evidence-with-retry",
            ".work/evidence/release-license/**",
            "License report gate failed after evidence upload",
        ).forEach { token -> assertContains(workflowText, token) }
        assertFalse(workflowText.contains("contents: write"), "Release license workflow must be read-only.")
        assertFalse(
            Regex("(?m)^\\s*uses:\\s*actions/upload-artifact@").containsMatchIn(workflowText),
            "Release license evidence must use the shared bounded artifact uploader.",
        )
    }
}
