package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalBrowserOriginEvidenceContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `browser feasibility is hosted read-only exact-head and independently version-pinned`() {
        val workflow = root.resolve(
            ".github/workflows/local-control-browser-origin-lab.yml"
        ).readText()
        val pkg = root.resolve("tools/local-control/browser-probe/package.json").readText()
        listOf(
            "runs-on: ubuntu-latest",
            "persist-credentials: false",
            "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"",
            "browser: [chromium, firefox, webkit]",
            "Local Control browser origin lab",
            "uses: ./.github/actions/upload-evidence-with-retry",
            "github-token:",
        ).forEach { assertContains(workflow, it) }
        assertContains(pkg, "\"playwright\": \"1.64.1\"")
        assertFalse(workflow.contains("self-hosted"))
        assertFalse(workflow.contains("deploy-pages"))
        assertFalse(workflow.contains("npm install playwright@latest"))
    }

    @Test
    fun `probe must never capture provider credentials or create production authority`() {
        val path = "tools/local-control/browser-probe/run.mjs"
        val probe = root.resolve(path).readText()
        listOf(
            "isSecureContext",
            "globalThis.crypto?.subtle",
            "ignoreHTTPSErrors: true",
            "targetAddressSpace: 'local'",
            "noProviderCredentialsTransmitted: true",
            "productionPhoneCompatibility: 'NOT_QUALIFIED'",
            "productionCredentialTransport: 'NOT_IMPLEMENTED'",
            "localRequestOutcome",
        ).forEach { assertContains(probe, it) }
        listOf(
            "SourceOnboarding", "SourcePreparationRequest", "ProtectedSourcePrincipal",
            "app.muxtv", "xtream", "m3u", "password", "Bearer ",
            "Access-Control-Allow-Origin': '*'",
        ).forEach {
            assertFalse(probe.contains(it), "Research-only probe unexpectedly references $it")
        }
        val app = root.resolve("app/tv/build.gradle.kts").readText()
        val server = root.resolve("local-control/server/build.gradle.kts").readText()
        assertFalse(app.contains("browser-probe"))
        assertFalse(server.contains("browser-probe"))
        assertTrue(root.resolve(".work/research/lc04-s2-browser-origin-20261015.md").isFile)
    }
}
