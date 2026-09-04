package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseReproducibilityEvidenceContractTest {
    private val repositoryRoot = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `alpha release compares two clean exact-head APK and AAB content graphs`() {
        val script = repositoryRoot.resolve("tools/release/Invoke-ReleaseReproducibilityEvidence.ps1")
        assertTrue(script.isFile, "Missing release reproducibility evidence entry point.")
        val scriptText = script.readText()

        listOf(
            "Assert-EvidenceCommit.ps1",
            "Invoke-CleanReleaseBuild",
            ":app:tv:assembleRelease",
            ":app:tv:bundleRelease",
            "--no-build-cache",
            "--rerun-tasks",
            "build1",
            "build2",
            "Get-FileHash",
            "SHA256",
            "Get-ArchiveContentManifest",
            "entrySha256",
            "contentGraphSha256",
            "contentGraphIdentical",
            "rawByteIdentical",
            "version-control-info.textproto",
            "sourceCommit",
            "release-reproducibility-evidence.json",
        ).forEach { token -> assertContains(scriptText, token) }

        assertTrue(
            scriptText.indexOf("build1") < scriptText.indexOf("build2"),
            "The evidence script must preserve the first clean build before producing the second one.",
        )
        assertFalse(
            scriptText.contains("SOURCE_DATE_EPOCH"),
            "Do not mask AGP/ZIP nondeterminism by mutating timestamps; report container-byte differences separately.",
        )

        val workflow = repositoryRoot.resolve(".github/workflows/release-reproducibility-evidence.yml")
        assertTrue(workflow.isFile, "Missing hosted release reproducibility workflow.")
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
            "Invoke-ReleaseReproducibilityEvidence.ps1",
            "uses: ./.github/actions/upload-evidence-with-retry",
            ".work/evidence/release-reproducibility/**",
        ).forEach { token -> assertContains(workflowText, token) }
        assertFalse(workflowText.contains("contents: write"), "Release reproducibility workflow must be read-only.")
        assertFalse(
            Regex("(?m)^\\s*uses:\\s*actions/upload-artifact@").containsMatchIn(workflowText),
            "Release reproducibility evidence must use the shared bounded artifact uploader.",
        )
    }
}
