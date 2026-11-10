package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2J3B: source AEAD code is a standalone inert primitive, not permission
 * to handle IPTV credentials on an unreviewed Android/HTTP channel.
 */
class Lc04SourceAeadBoundaryContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private fun file(path: String): String = root.resolve(path).readText()

    @Test fun `source AEAD engine has no unsafe production factory or runtime ingress`() {
        val code = file(
            "local-control/source-aead/src/main/kotlin/" +
                "app/muxtv/localcontrol/sourceaead/Lc04SourceAeadRecords.kt",
        )
        for (required in listOf(
            "internal class Lc04SourceAeadRecords private constructor(",
            "internal fun isolatedHostPairForTests(",
            "Cipher.getInstance(\"AES/GCM/NoPadding\")",
            "GCMParameterSpec(128, nonce)",
            "MessageDigest.isEqual(binding, wireBinding)",
            "Lc04SourceCommandCodec.decodeRequest(plain)",
            "Lc04SourceCommandCodec.encodeReply(reply)",
            "if (command.sequence != nextIncoming) return failOpen()",
            "responsePending = true",
            "MAX_COMMANDS = 16L",
            "TTL_MS = 120_000L",
            "APPDATA-EXTRACT-v1",
            "APPDATA-AEAD-v1",
            "override fun toString(): String = \"Lc04SourceAeadRecords([REDACTED])\"",
        )) assertContains(code, required)
        for (forbidden in listOf(
            "SourceOnboarding", "PairingAuthority", "PairingGrant",
            "TvHpkeQrSession", "Lc04SyntheticDerivedKeys",
            "Lc04MxH1SyntheticProbePort", "Ktor", "HttpClient",
            "ServerSocket", "java.net.Socket", "android.net",
            "SharedPreferences", "Logger.", "System.out.",
        )) assertFalse(code.contains(forbidden), "Source AEAD bypass: " + forbidden)
        val module = file("local-control/source-aead/build.gradle.kts")
        assertContains(module, """implementation(project(":local-control:source-command-wire"))""")
        for (forbidden in listOf(
            ":local-control:protected-channel", ":local-control:onboarding",
            ":app:tv", ":app:phone", "libs.ktor", "libs.okhttp",
        )) assertFalse(module.contains(forbidden), forbidden)
        val phone = file("app/phone/build.gradle.kts")
        val tv = file("app/tv/build.gradle.kts")
        assertFalse(phone.contains(":local-control:source-aead"))
        assertFalse(tv.contains(":local-control:source-aead"))
        val legacy = file(
            "local-control/server/src/main/kotlin/app/muxtv/localcontrol/server/LanTransport.kt",
        )
        assertFalse(legacy.contains("Lc04SourceAeadRecords"))
    }

    @Test fun `exact head JVM crypto tests require negative cases and remain CI-only`() {
        val test = file(
            "local-control/source-aead/src/test/kotlin/" +
                "app/muxtv/localcontrol/sourceaead/Lc04SourceAeadRecordsTest.kt",
        )
        for (scenario in listOf(
            "m3uAndXtreamAreEncryptedAndAuthenticatedEndToEndInMemory",
            "requestTamperClosesKeysAndPreventsRetry",
            "replyTamperClosesPhoneKeyAndRejectsLegitimateResponse",
            "replayedRequestIsTerminalAndCanNeverRunSecondCommand",
            "reflectedRequestCannotAuthenticateAsTvReply",
            "wrongSessionBindingRejectsOtherwiseValidGcm",
            "headerVersionDirectionLengthFlagsAndNonceAreAllAuthenticated",
            "truncationAndPaddingAreRejectedTerminally",
            "expiryClockRollbackAndRouteRevocationZeroizeTerminally",
            "sixteenRequestReplyPairsAreValidButSeventeenthIsTerminal",
        )) assertContains(test, scenario)
        val workflow = file(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
        assertContains(workflow, ":local-control:source-aead:test")
        assertContains(workflow, "Lc04SourceAeadBoundaryContractTest")
        assertContains(workflow, "local-control/source-aead/build/test-results/test/**")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertFalse(workflow.contains("self-hosted"))
        val settings = file("settings.gradle.kts")
        val metadata = file(".work/meta/modules.yaml")
        assertContains(settings, """:local-control:source-aead""")
        assertContains(metadata, "gradle_path: :local-control:source-aead")
        assertTrue(root.resolve(
            ".work/specifications/lc04-s2j3b-source-aead-records-20261110.md",
        ).isFile)
    }
}
