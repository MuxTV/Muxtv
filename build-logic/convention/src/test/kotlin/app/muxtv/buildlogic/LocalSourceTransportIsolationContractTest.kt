package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Intentionally persistent negative contract: the untrusted HTTP bootstrap MUST NEVER grow
 * provider-credential routes by an incidental refactor. Once #401 approves a protected
 * transport, change this gate only with targeted secure-channel evidence.
 */
class LocalSourceTransportIsolationContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `plaintext Ktor HTTP module cannot import source preparation ports`() {
        val transport = root.resolve("local-control/server/src/main/kotlin")
        assertTrue(transport.isDirectory)
        val sources = transport.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        sources.forEach { file ->
            val code = file.readText()
            listOf(
                "SourceOnboarding", "SourcePreparationRequest", "SourcePreparationHandle",
                "ProtectedSourcePrincipal", "ProtectedSourceStaging",
                "/v1/source", "/v1/provider", "/v1/catalog",
            ).forEach { forbidden ->
                assertFalse(code.contains(forbidden),
                    "Unprotected HTTP code ${file.relativeTo(root)} must not contain $forbidden")
            }
        }
    }

    @Test
    fun `new staging module has only catalog port and pure pairing dependencies`() {
        val build = root.resolve("local-control/onboarding/build.gradle.kts").readText()
        assertContains(build, """project(":catalog:api")""")
        assertContains(build, """project(":local-control:core")""")
        assertFalse(build.contains("""project(":local-control:server")"""))
        val server = root.resolve("local-control/server/build.gradle.kts").readText()
        assertFalse(server.contains("""project(":local-control:onboarding")"""))
        val app = root.resolve("app/tv/build.gradle.kts").readText()
        assertFalse(app.contains("""project(":local-control:onboarding")"""))
    }

    @Test
    fun `protected transport principal cannot be publicly constructed`() {
        val source = root.resolve(
            "local-control/onboarding/src/main/kotlin/app/muxtv/localcontrol/onboarding/ProtectedSourceStaging.kt"
        ).readText()
        assertContains(source, "class ProtectedSourcePrincipal internal constructor(")
        assertContains(source, "SourceStagingResult.TV_HTTP_APPROVAL_REQUIRED")
        assertContains(source, "SourceStagingResult.LOCAL_NETWORK_PERMISSION_REQUIRED")
        assertContains(source, "onboarding.prepare(request)")
        assertContains(source, "onboarding.activate(handle, sourceName)")
        assertContains(source, "onboarding.cancel(handle)")
        assertFalse(source.contains("fun approveInsecureHttp("))
    }
}
