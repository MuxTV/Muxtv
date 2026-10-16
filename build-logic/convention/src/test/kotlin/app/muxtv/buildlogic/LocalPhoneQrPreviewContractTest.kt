package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPhoneQrPreviewContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `phone APK is offline only and cannot provision a source before channel authorization`() {
        val manifest = root.resolve("app/phone/src/main/AndroidManifest.xml").readText()
        val build = root.resolve("app/phone/build.gradle.kts").readText()
        val screen = root.resolve("app/phone/src/main/kotlin/app/muxtv/phone/PhoneQrPreviewActivity.kt").readText()
        listOf(
            "android.permission.INTERNET",
            "uses-permission",
            "android.permission.CAMERA",
        ).forEach { forbidden ->
            // Only XML actual declarations matter; comments may document denied permissions.
            assertFalse(Regex("<uses-permission\\b[^>]*" + Regex.escape(forbidden)).containsMatchIn(manifest))
        }
        assertContains(build, """project(":local-control:qr-contract")""")
        listOf(
            """project(":local-control:crypto-lab")""",
            """project(":local-control:onboarding")""",
            """project(":local-control:server")""",
            """project(":core:credentials")""",
            """project(":app:tv")""",
        ).forEach { assertFalse(build.contains(it)) }
        assertContains(screen, "TvPublicQr.inspect(")
        assertContains(screen, "ActivityResultContracts.OpenDocument")
        assertContains(screen, "8 * 1024 * 1024")
        assertContains(screen, "getString(R.string.warning)")
        assertFalse(screen.contains("HttpURLConnection"))
        assertFalse(screen.contains("SourceOnboarding"))
    }

    @Test
    fun `public QR parsing never returns a bearer secret or silently confers TV trust`() {
        val src = root.resolve(
            "local-control/qr-contract/src/main/kotlin/app/muxtv/localcontrol/qrcontract/TvPublicQr.kt"
        ).readText()
        val build = root.resolve("local-control/qr-contract/build.gradle.kts").readText()
        assertContains(src, "TinkProtoKeysetFormat.parseKeysetWithoutSecret")
        assertContains(src, "KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC")
        assertContains(src, "HpkeKem.DHKEM_X25519_HKDF_SHA256")
        assertContains(src, "TvQrPreview(")
        assertContains(build, "testImplementation(project(\":local-control:crypto-lab\"))")
        assertFalse(build.contains("implementation(project(\":local-control:crypto-lab\"))"))
        assertFalse(src.contains("SourceOnboarding"))
        assertFalse(src.contains("grantPermission"))
        assertTrue(root.resolve(".work/specifications/local-control-phone-preview-20261016.md").isFile)
    }
}
