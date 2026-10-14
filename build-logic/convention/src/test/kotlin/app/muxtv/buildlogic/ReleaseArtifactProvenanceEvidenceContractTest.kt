package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseArtifactProvenanceEvidenceContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release records exact-head apk aab dependency and signing provenance`() {
        val script = repositoryRoot.resolve("tools/release/Invoke-ReleaseArtifactProvenanceEvidence.ps1")
        assertTrue(script.isFile, "Missing release artifact provenance evidence entry point.")
        val scriptText = script.readText()

        listOf(
            "Assert-EvidenceCommit.ps1",
            ":app:tv:assembleRelease",
            ":app:tv:bundleRelease",
            "releaseRuntimeClasspath",
            "Get-FileHash",
            "SHA256",
            "apkSha256",
            "aabSha256",
            "apkSizeBytes",
            "aabSizeBytes",
            "dependencyReportPath",
            "sourceCommit",
            "signingStatus",
            "signingGateStatus",
            "UNSIGNED",
            "PENDING",
            "apksigner",
            "certificate SHA-256 digest",
            "signerExitCode = ",
            "LASTEXITCODE = 0",
            "release-artifact-provenance.json",
            "exit 0",
        ).forEach { token -> assertContains(scriptText, token) }

        val signerCapture = scriptText.indexOf("signerExitCode = ")
        val nativeExitReset = scriptText.indexOf("LASTEXITCODE = 0", startIndex = signerCapture.coerceAtLeast(0))
        assertTrue(signerCapture >= 0, "The apksigner native exit code must be captured before normalization.")
        assertTrue(nativeExitReset > signerCapture, "The expected unsigned apksigner exit must be neutralized after it is captured.")

        val metadataWrite = scriptText.indexOf("metadata | ConvertTo-Json")
        val explicitSuccessExit = scriptText.lastIndexOf("exit 0")
        assertTrue(metadataWrite >= 0, "Release provenance metadata must be written before the success boundary.")
        assertTrue(
            explicitSuccessExit > metadataWrite,
            "The script must explicitly return exit 0 only after release provenance metadata is written.",
        )

        listOf(
            "debug.keystore",
            "signingConfigs.getByName(\"debug\")",
            "signingConfig = signingConfigs.debug",
        ).forEach { forbidden ->
            assertFalse(scriptText.contains(forbidden), "Release provenance must never substitute debug signing: $forbidden")
        }

        val workflow = repositoryRoot.resolve(".github/workflows/release-artifact-provenance.yml")
        assertTrue(workflow.isFile, "Missing hosted release artifact provenance workflow.")
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
            "Invoke-ReleaseArtifactProvenanceEvidence.ps1",
            "uses: ./.github/actions/upload-evidence-with-retry",
            "app/tv/build/outputs/apk/release/**",
            "app/tv/build/outputs/bundle/release/**",
            ".work/evidence/release-artifact-provenance/**",
        ).forEach { token -> assertContains(workflowText, token) }
        assertFalse(
            workflowText.contains("cancel-in-progress: false"),
            "Stale PR provenance runs must not block a newer exact-head evidence run.",
        )
        assertFalse(workflowText.contains("contents: write"), "Release provenance workflow must be read-only.")
        assertFalse(
            Regex("(?m)^\\s*uses:\\s*actions/upload-artifact@").containsMatchIn(workflowText),
            "Release provenance evidence must use the shared bounded artifact uploader.",
        )
    }
}
