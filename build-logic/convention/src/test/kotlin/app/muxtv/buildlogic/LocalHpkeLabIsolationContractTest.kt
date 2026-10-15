package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalHpkeLabIsolationContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test
    fun `experimental HPKE cannot be pulled into the app or plain HTTP transport`() {
        listOf(
            "app/tv/build.gradle.kts",
            "local-control/server/build.gradle.kts",
            "local-control/core/build.gradle.kts",
            "local-control/onboarding/build.gradle.kts",
        ).forEach { location ->
            val content = root.resolve(location).readText()
            assertFalse(content.contains("project(\":local-control:crypto-lab\")"),
                "Experiment must not become a production dependency: $location")
            assertFalse(content.contains("libs.tink"),
                "Do not accidentally ship Tink via this experimental lab: $location")
        }
    }

    @Test
    fun `lab uses audited HPKE not a custom elliptic curve or TLS implementation`() {
        val code = root.resolve(
            "local-control/crypto-lab/src/main/kotlin/app/muxtv/localcontrol/cryptolab/ExperimentalHpkeLab.kt",
        ).readText()
        val build = root.resolve("local-control/crypto-lab/build.gradle.kts").readText()
        assertContains(build, "implementation(libs.tink)")
        assertContains(code, "HybridEncrypt")
        assertContains(code, "HybridDecrypt")
        assertContains(code, "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM")
        listOf(
            "Cipher.getInstance(", "KeyAgreement.getInstance(", "Mac.getInstance(",
            "ServerSocket(", "embeddedServer(", "SourceOnboarding(", "SourcePreparationRequest(",
        ).forEach { assertFalse(code.contains(it), "The HPKE lab must stay isolated: $it") }
        assertTrue(root.resolve(".work/research/lc04-s2-hpke-lab-20261015.md").isFile)
    }
}
