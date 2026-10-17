package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reject accidental authorization of legacy plaintext pairing or provider source handling
 * from the not-yet-networked LC04 Ed25519 proof-of-possession building block.
 */
class LocalControlPhoneEnrollmentIsolationTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `enrollment module has no source capability or transport dependencies`() {
        val gradle = root.resolve("local-control/enrollment/build.gradle.kts").readText()
        assertContains(gradle, """project(":local-control:qr-public")""")
        assertContains(gradle, "libs.tink")
        for (forbidden in listOf(
            ":local-control:server", ":local-control:onboarding", ":local-control:core",
            ":catalog:", ":core:credentials", "libs.ktor", "libs.okhttp",
        )) assertFalse(gradle.contains(forbidden), "Forbidden enrollment dependency: $forbidden")

        val file = root.resolve(
            "local-control/enrollment/src/main/kotlin/app/muxtv/localcontrol/enrollment/TvPhoneEnrollment.kt",
        ).readText()
        assertContains(file, "approveOnTv(")
        assertContains(file, "tvReceiverIsAlive")
        assertContains(file, "PhoneEnrollmentDenial.TV_CONSENT_REQUIRED")
        assertContains(file, "PublicKeyVerify")
        assertContains(file, "SignatureConfig.register()")
        assertContains(file, "MuxTV-LC04-PHONE-ENROLL-v1")
        for (forbidden in listOf(
            "SourceOnboarding", "SourcePreparationHandle", "PairingGrant",
            "PairingCapability.SOURCE_CREATE", "HttpClient", "TvLanServer", "route(", "post(",
        )) assertFalse(file.contains(forbidden), "Forbidden enrollment authority: $forbidden")
    }

    @Test fun `the legacy LAN transport cannot import enrollment or source credential handler`() {
        val server = root.resolve("local-control/server/src/main")
            .walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(server.isNotEmpty())
        for (file in server) {
            val text = file.readText()
            assertFalse(text.contains("localcontrol.enrollment"), file.path)
            assertFalse(text.contains("localcontrol.onboarding"), file.path)
            assertFalse(text.contains("TvPhoneEnrollment"), file.path)
        }
        val tv = root.resolve(
            "local-control/tv-qr/src/main/kotlin/app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt",
        ).readText()
        assertContains(tv, "phoneEnrollmentGate(")
        assertContains(tv, "pendingPhoneEnrollment?.close()")
    }

    @Test fun `proof CI uses exact tested source and only hosted runners`() {
        val workflow = root.resolve(".github/workflows/local-control-phone-enrollment.yml").readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":local-control:enrollment:test")
        assertContains(workflow, ":app:tv:compileDebugKotlin")
        assertFalse(workflow.contains("self-hosted"))
        assertFalse(workflow.contains("android-emulator-runner"))
    }
}
