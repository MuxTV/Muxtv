package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalNativePhonePreviewContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `phone preview has camera only and no credential or network access`() {
        val build = root.resolve("app/phone/build.gradle.kts").readText()
        val manifest = root.resolve("app/phone/src/main/AndroidManifest.xml").readText()
        val scanner = root.resolve(
            "app/phone/src/main/kotlin/app/muxtv/phone/PhoneQrPreviewActivity.kt"
        ).readText()
        assertContains(build, "applicationId = \"app.muxtv.phone.preview\"")
        assertContains(build, "implementation(libs.camera.core)")
        assertContains(build, "implementation(libs.zxing.core)")
        assertContains(manifest, "android.permission.CAMERA")
        assertContains(manifest, "android:allowBackup=\"false\"")
        assertContains(manifest, "android:usesCleartextTraffic=\"false\"")
        listOf("android.permission.INTERNET", "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE")
            .forEach { assertFalse(manifest.contains(it), "Unexpected phone permission: $it") }
        listOf("SourceOnboarding", "SourcePreparationRequest", "ProtectedSourcePrincipal",
            "Socket(", "HttpURLConnection(", "OkHttpClient(", "startActivity(Intent.ACTION_VIEW",
            "http://", "https://").forEach {
                assertFalse(scanner.contains(it), "Unsafe phone preview integration: $it")
            }
        assertContains(scanner, "WindowManager.LayoutParams.FLAG_SECURE")
        assertContains(scanner, "image.close()")
        assertTrue(root.resolve(".work/specifications/local-control-native-phone-preview.md").isFile)
    }

    @Test
    fun `preview QA is hosted exact-head and fails if network privilege appears`() {
        val workflow = root.resolve(
            ".github/workflows/local-control-native-phone-qr-preview.yml"
        ).readText()
        listOf("runs-on: ubuntu-latest", "persist-credentials: false",
            ":app:phone:assembleDebug", ":app:phone:testDebugUnitTest",
            "aapt\" dump permissions", "android.permission.(INTERNET",
            "uses: ./.github/actions/upload-evidence-with-retry").forEach {
            assertContains(workflow, it)
        }
        assertFalse(workflow.contains("self-hosted"))
    }
}
