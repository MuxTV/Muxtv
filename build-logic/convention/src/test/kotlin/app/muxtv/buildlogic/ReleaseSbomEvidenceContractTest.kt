package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseSbomEvidenceContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release generates bounded direct CycloneDX SBOM evidence`() {
        val versionCatalog = repositoryRoot.resolve("gradle/libs.versions.toml").readText()
        assertContains(versionCatalog, "cyclonedx = \"3.4.1\"")
        assertContains(versionCatalog, "id = \"org.cyclonedx.bom\"")

        val appBuild = repositoryRoot.resolve("app/tv/build.gradle.kts").readText()
        listOf(
            "libs.plugins.cyclonedx",
            "CyclonedxDirectTask",
            "Component.Type.APPLICATION",
            "Version.VERSION_16",
            "includeConfigs = listOf(\"releaseRuntimeClasspath\")",
            "testConfigs = emptyList()",
            "includeBuildEnvironment = false",
            "includeBomSerialNumber = false",
            "includeBuildSystem = false",
            "muxtv-tv-release.cdx.json",
        ).forEach { token -> assertContains(appBuild, token) }
        assertFalse(
            appBuild.contains("cyclonedxBom"),
            "The release SBOM boundary must use app:tv's Direct SBOM, not an aggregate multi-project BOM.",
        )

        val script = repositoryRoot.resolve("tools/release/Invoke-ReleaseSbomEvidence.ps1")
        assertTrue(script.isFile, "Missing release SBOM evidence entry point.")
        val scriptText = script.readText()
        listOf(
            "Assert-EvidenceCommit.ps1",
            ":app:tv:cyclonedxDirectBom",
            "releaseRuntimeClasspath",
            "muxtv-tv-release.cdx.json",
            "ConvertFrom-Json",
            "bomFormat",
            "CycloneDX",
            "specVersion",
            "1.6",
            "components",
            "dependencies",
            "Get-FileHash",
            "SHA256",
            "sourceCommit",
            "sbomSha256",
            "release-sbom-evidence.json",
            "cyclonedx-generation.log",
            "Unable to resolve POM for",
            "metadataResolutionWarningCount",
            "metadataResolutionWarnings",
            "metadataResolutionWarningComponentsPresent",
        ).forEach { token -> assertContains(scriptText, token) }
        assertFalse(
            scriptText.contains(":benchmark:"),
            "Release SBOM evidence must not resolve benchmark configurations.",
        )

        val workflow = repositoryRoot.resolve(".github/workflows/release-sbom-evidence.yml")
        assertTrue(workflow.isFile, "Missing hosted release SBOM evidence workflow.")
        val workflowText = workflow.readText()
        listOf(
            "workflow_dispatch:",
            "pull_request:",
            "contents: read",
            "actions: read",
            "github.event.pull_request.head.sha",
            "persist-credentials: false",
            "uses: ./.github/actions/setup-muxtv-jdks",
            "Invoke-ReleaseSbomEvidence.ps1",
            "uses: ./.github/actions/upload-evidence-with-retry",
            ".work/evidence/release-sbom/**",
            "app/tv/build/reports/sbom/**",
        ).forEach { token -> assertContains(workflowText, token) }
        assertFalse(workflowText.contains("contents: write"), "Release SBOM workflow must be read-only.")
        assertFalse(
            Regex("(?m)^\\s*uses:\\s*actions/upload-artifact@").containsMatchIn(workflowText),
            "Release SBOM evidence must use the shared bounded artifact uploader.",
        )
    }
}
