package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAndroidCryptoDeviceEvidenceContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `Android crypto compatibility has zero product runtime dependency`() {
        val module = root.resolve("local-control/crypto-android-compat/build.gradle.kts").readText()
        assertContains(module, "androidTestImplementation(project(\":local-control:crypto-lab\"))")
        assertContains(module, "androidTestImplementation(libs.tink)")
        assertFalse(module.contains("implementation(project(\":local-control:crypto-lab\"))"))
        for (path in listOf(
            "app/tv/build.gradle.kts",
            "local-control/server/build.gradle.kts",
            "local-control/onboarding/build.gradle.kts",
        )) {
            val build = root.resolve(path).readText()
            assertFalse(build.contains("crypto-android-compat"), path)
            assertFalse(build.contains("crypto-lab"), path)
        }
        val manifest = root.resolve("local-control/crypto-android-compat/src/main/AndroidManifest.xml").readText()
        assertFalse(manifest.contains("android.permission.INTERNET"))
    }

    @Test
    fun `API26 and API36 device matrix is exact-head and does not claim product encryption`() {
        val ci = root.resolve(".github/workflows/local-control-android-crypto-compat.yml").readText()
        val test = root.resolve(
            "local-control/crypto-android-compat/src/androidTest/kotlin/app/muxtv/localcontrol/cryptoandroidcompat/AndroidCryptoProviderTest.kt"
        ).readText()
        listOf(
            "runs-on: ubuntu-latest",
            "persist-credentials: false",
            "api: 26",
            "api: 36",
            "Local Control Android crypto provider matrix",
            "Run-AndroidCryptoProviderTests.sh",
            "uses: ./.github/actions/upload-evidence-with-retry",
        ).forEach { assertContains(ci, it) }
        listOf(
            "tinkHpkeX25519EncryptDecryptRunsOnTheActualAndroidProvider",
            "tinkEd25519SigningAndVerificationDoesNotRequireAndroidPlatformEd25519",
            "nativePlatformEd25519MustNotBeAssumedOnApi26",
        ).forEach { assertContains(test, it) }
        assertTrue(root.resolve(".work/research/lc04-s2-android-provider-20261015.md").isFile)
        assertFalse(ci.contains("self-hosted"))
        assertFalse(test.contains("SourceOnboarding"))
        assertFalse(test.contains("password"))
    }
}
