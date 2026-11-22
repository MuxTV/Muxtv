package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Allow ONLY the reviewed QR-pinned, user-triggered native proof transport.
 * Provider secrets, browsers and legacy HTTP are still forbidden.
 */
class LocalPhoneQrScannerContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `phone runtime has only QR-pinned proof and no provider credential dependencies`() {
        val app = root.resolve("app/phone/build.gradle.kts").readText()
        assertContains(app, """project(":local-control:qr-public")""")
        assertContains(app, "libs.camera.camera2")
        assertContains(app, "libs.camera.lifecycle")
        assertContains(app, "libs.camera.view")
        assertContains(app, """implementation(project(":local-control:phone-flow"))""")
        assertContains(app, """implementation(project(":local-control:native-transport"))""")
        for (prohibited in listOf(
            """:local-control:crypto-lab""", """:local-control:server""",
            """:local-control:onboarding""", """:core:credentials""",
            """:core:network""", "libs.okhttp", "libs.ktor",
        )) {
            assertFalse(app.contains(prohibited), "Unexpected phone runtime dependency: $prohibited")
        }
        val manifest = root.resolve("app/phone/src/main/AndroidManifest.xml").readText()
        assertContains(manifest, """android.permission.CAMERA""")
        for (required in listOf("INTERNET", "ACCESS_NETWORK_STATE", "ACCESS_LOCAL_NETWORK")) {
            assertContains(manifest, """<uses-permission android:name="android.permission.$required" />""")
        }
        assertContains(manifest, """android:usesCleartextTraffic="false" """.trim())
        assertContains(manifest, """android:allowBackup="false" """.trim())
    }

    @Test
    fun `phone scan accepts only parsed public-key QR not old plaintext URL`() {
        val scanner = root.resolve(
            "app/phone/src/main/kotlin/app/muxtv/phone/PhoneQrActivity.kt"
        ).readText()
        val parser = root.resolve(
            "local-control/qr-public/src/main/kotlin/app/muxtv/localcontrol/qr/TvQrPublicKey.kt"
        ).readText()
        assertContains(scanner, "TvQrPublicKeyParser.parseOrNull(value)")
        assertContains(scanner, "QR-pinned phone companion.")
        assertContains(scanner, "startNativeFromGesture()")
        assertContains(scanner, "Manifest.permission.ACCESS_LOCAL_NETWORK")
        assertContains(scanner, "Build.VERSION.SDK_INT >= 37")
        assertContains(scanner, "nativeSession?.close()")
        val client = root.resolve(
            "app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt",
        ).readText()
        assertContains(client, "Lc04TcpPhoneExchange.forPrivateIpv4OnNetwork(")
        assertContains(client, "selected.bindSocket(socket)")
        assertContains(client, "manager.registerDefaultNetworkCallback(callback)")
        assertContains(client, "phase.getAndSet(Phase.CLOSED)")
        assertContains(client, "attempt.finishAfterTvAction()")
        assertFalse(client.contains("SourceOnboarding"))
        assertFalse(client.contains("http://"))
        assertFalse(client.contains("SharedPreferences"))
        assertContains(parser, """private const val PREFIX = "MXLC04:1:"""")
        assertContains(parser, "parseKeysetWithoutSecret(raw)")
        assertContains(parser, "HpkeAead.AES_256_GCM")
        assertFalse(scanner.contains("SourceOnboarding"))
        assertFalse(scanner.contains("SourcePreparationRequest"))
        assertFalse(scanner.contains("http://"))
        assertFalse(scanner.contains("startActivity(Intent.ACTION_VIEW"))
        assertFalse(scanner.contains("SharedPreferences"))
        assertTrue(root.resolve(".work/specifications/local-control-native-phone-qr.md").isFile)
    }

    @Test
    fun `phone QR qualification is exact-head on github hosted without third TV AVD`() {
        val workflow = root.resolve(".github/workflows/local-control-phone-qr.yml").readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":app:phone:assembleDebug")
        assertContains(workflow, ":local-control:qr-public:test")
        assertContains(workflow, ":app:phone:testDebugUnitTest")
        assertFalse(workflow.contains("self-hosted"))
        assertFalse(workflow.contains("android-emulator-runner"))
    }
}
