package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Prevent an incomplete QR-only Android companion from silently becoming
 * a credential-uploading LAN client before #401 bidirectional-auth review.
 */
class LocalPhoneQrScannerContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `phone camera app is offline and cannot reach pairing server or credential stores`() {
        val app = root.resolve("app/phone/build.gradle.kts").readText()
        assertContains(app, """project(":local-control:qr-public")""")
        assertContains(app, "libs.camera.camera2")
        assertContains(app, "libs.camera.lifecycle")
        assertContains(app, "libs.camera.view")
        for (prohibited in listOf(
            """:local-control:crypto-lab""", """:local-control:server""",
            """:local-control:onboarding""", """:core:credentials""",
            """:core:network""", "libs.okhttp", "libs.ktor",
        )) {
            assertFalse(app.contains(prohibited), "Unexpected phone runtime dependency: $prohibited")
        }
        val manifest = root.resolve("app/phone/src/main/AndroidManifest.xml").readText()
        assertContains(manifest, """android.permission.CAMERA""")
        assertFalse(Regex("""<uses-permission[^>]*android.permission.INTERNET""").containsMatchIn(manifest))
        assertFalse(Regex("""<uses-permission[^>]*android.permission.ACCESS_LOCAL_NETWORK""").containsMatchIn(manifest))
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
        assertContains(scanner, "Scan-only Android phone companion entry point.")
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
