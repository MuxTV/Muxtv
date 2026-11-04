package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A lab-only AEAD envelope does not grant a production source transport. */
class Lc04SyntheticProtectedChannelIsolationTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `synthetic only module cannot be called from Android applications`() {
        val gradle = root.resolve("local-control/protected-channel/build.gradle.kts").readText()
        assertContains(gradle, "implementation(libs.tink)")
        for (forbidden in listOf("project(", ":catalog:", ":core:credentials",
            ":local-control:onboarding", "libs.okhttp", "libs.ktor")) {
            assertFalse(gradle.contains(forbidden), forbidden)
        }
        val settings = root.resolve("settings.gradle.kts").readText()
        assertContains(settings, "\":local-control:protected-channel\"")
        val moduleInfo = root.resolve(".work/meta/modules.yaml").readText()
        assertContains(moduleInfo, "local-control-protected-channel:")
        for (app in listOf("app/phone/build.gradle.kts", "app/tv/build.gradle.kts")) {
            assertFalse(root.resolve(app).readText().contains(":local-control:protected-channel"))
        }
        val src = root.resolve("local-control/protected-channel/src/main/kotlin/" +
            "app/muxtv/localcontrol/protectedchannel/Lc04SyntheticDuplex.kt").readText()
        for (required in listOf("internal class Lc04SyntheticDuplexEndpoint",
            "sendSyntheticProbe()", "receiveSyntheticProbe(", "AeadConfig.register()",
            "EXPECTED_CIPHERTEXT_BYTES", "MessageDigest.isEqual",
            "nextIn + 1L", "MAX_PROBES", "TTL_MILLIS", "override fun close()")) {
            assertContains(src, required)
        }
        for (forbidden in listOf("SourceOnboarding", "SourcePreparationHandle",
            "SourcePreparationRequest", "Socket(", "HttpClient", "M3u(", "Xtream(",
            "android.content.", "SharedPreferences", "forPrivateIpv4(")) {
            assertFalse(src.contains(forbidden), forbidden)
        }
    }

    @Test fun `hosted gate must test real AEAD on exact head without permissions`() {
        val workflow = root.resolve(".github/workflows/lc04-synthetic-duplex-aead-ci.yml")
            .readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":local-control:protected-channel:test")
        assertContains(workflow, "local-control/protected-channel/build/test-results")
        assertFalse(workflow.contains("self-hosted"))
        assertFalse(workflow.contains("dryRunMode"))
        assertFalse(workflow.contains("android-emulator-runner"))
        val tests = root.resolve("local-control/protected-channel/src/test/kotlin/" +
            "app/muxtv/localcontrol/protectedchannel/Lc04SyntheticDuplexTest.kt").readText()
        for (required in listOf("repeatedPlaintextProbes", "duplicatedPacket",
            "reflectedPacket", "outOfOrderPacket", "wrongAuthenticatedBinding",
            "changingDirectionSessionCounterOrLength", "expiryAndMonotonic",
            "counterCapacityIsBounded", "cancellationIsPermanent")) {
            assertContains(tests, required)
        }
        assertTrue(root.resolve(
            ".work/specifications/lc04-synthetic-duplex-aead-20261104.md",
        ).isFile)
    }
}
