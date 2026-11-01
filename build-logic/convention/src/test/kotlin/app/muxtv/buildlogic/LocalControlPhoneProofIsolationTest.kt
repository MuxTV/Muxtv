package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Keep the phone cryptographic proof flow offline until the network slice is accepted. */
class LocalControlPhoneProofIsolationTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `phone orchestration grants no socket or IPTV source capability`() {
        val gradle = root.resolve("local-control/phone-flow/build.gradle.kts").readText()
        assertContains(gradle, """implementation(project(":local-control:qr-public"))""")
        assertContains(gradle, """implementation(project(":local-control:enrollment"))""")
        assertContains(gradle, """testImplementation(project(":local-control:native-transport"))""")
        for (blocked in listOf(
            """implementation(project(":local-control:native-transport"))""",
            ":local-control:server", ":local-control:onboarding",
            ":catalog:", ":core:credentials", "libs.ktor", "libs.okhttp",
        )) assertFalse(gradle.contains(blocked), "Forbidden proof module dependency: $blocked")
        val impl = root.resolve(
            "local-control/phone-flow/src/main/kotlin/app/muxtv/localcontrol/phoneflow/Lc04PhoneProofFlow.kt",
        ).readText()
        assertContains(impl, "Lc04PublicEnrollmentWire.decodeChallengeForPhone(")
        assertContains(impl, "PhoneProtectedProbeFactory(")
        assertContains(impl, "pending.verifyReply(reply)")
        assertContains(impl, "state.compareAndSet(State.VERIFYING, State.VERIFIED)")
        assertContains(impl, "state.getAndSet(State.CLOSED)")
        for (blocked in listOf(
            "Socket(", "HttpClient", "TvLanServer", "SourceOnboarding",
            "PairingGrant", "SOURCE_CREATE", "SOURCE_PREPARE", "getPassword(",
            "getUsername(", "approveOnTv(", "providerUrl", "android.content.",
        )) assertFalse(impl.contains(blocked), "Unauthorized phone proof capability: $blocked")
    }

    @Test fun `phone Android app remains scan-only and manifest without network permission`() {
        val manifest = root.resolve("app/phone/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("""<uses-permission android:name="android.permission.INTERNET" />"""))
        assertFalse(manifest.contains("""<uses-permission android:name="android.permission.ACCESS_LOCAL_NETWORK" />"""))
        val appGradle = root.resolve("app/phone/build.gradle.kts").readText()
        assertFalse(appGradle.contains(":local-control:phone-flow"))
        assertFalse(appGradle.contains(":local-control:native-transport"))
        val settings = root.resolve("settings.gradle.kts").readText()
        assertContains(settings, """":local-control:phone-flow"""")
    }

    @Test fun `phone protocol hosted gate checks exact tested head`() {
        val workflow = root.resolve(".github/workflows/local-control-phone-proof-flow.yml").readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":local-control:phone-flow:test")
        assertContains(workflow, ":app:phone:assembleDebug")
        assertFalse(workflow.contains("self-hosted"))
        assertFalse(workflow.contains("android-emulator-runner"))
    }
}
