package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LC04-S2J3A: plaintext source commands must remain structurally isolated
 * from raw LAN/HTTP transport until a reviewed app-data AEAD owner exists.
 */
class Lc04SourceCommandBoundaryContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    private fun source(path: String): String = root.resolve(path).readText()

    @Test fun `typed bounded source records are not an unauthenticated transport`() {
        val code = source(
            "local-control/protected-channel/src/main/kotlin/" +
                "app/muxtv/localcontrol/protectedchannel/Lc04SourceCommandCodec.kt",
        )
        for (required in listOf(
            "sealed class Lc04SourceCommand",
            "class PrepareM3u(",
            "class PrepareXtream(",
            "class Activate(",
            "class Cancel(",
            "object Lc04SourceCommandCodec",
            "MAX_REQUEST_BYTES = 6_656",
            "CodingErrorAction.REPORT",
            "if (input.hasRemaining()) null else command",
            "output.writeShort(0)",
            "final override fun toString(): String = \"Lc04SourceCommand([REDACTED])\"",
            "TV_CONFIRMATION_REQUIRED",
            "ACTIVATION_OUTCOME_UNKNOWN",
        )) assertContains(code, required)
        for (forbidden in listOf(
            "SourceOnboarding", "PairingGrant", "HttpClient",
            "ServerSocket", "Socket(", "Network.bindSocket",
            "android.content", "android.net", "SharedPreferences",
        )) assertFalse(code.contains(forbidden), "Forbidden app-data codec dependency: " + forbidden)
    }

    @Test fun `TV dispatcher never obtains source authority from synthetic proof or HTTP`() {
        val code = source(
            "local-control/onboarding/src/main/kotlin/" +
                "app/muxtv/localcontrol/onboarding/Lc04TvSourceCommandSession.kt",
        )
        for (required in listOf(
            "internal class Lc04TvSourceCommandSession(",
            "private val principal: ProtectedSourcePrincipal",
            "consumeTvActivationApproval: () -> Boolean = { false }",
            "staging.prepare(",
            "staging.activate(",
            "staging.cancel(",
            "insecureHttpApproved = false",
            "command.sequence != nextSequence",
            "if (!consumeTvActivationApproval())",
            "ACTIVATION_OUTCOME_UNKNOWN",
            "discardAfterOwnerRevocation()",
        )) assertContains(code, required)
        for (forbidden in listOf(
            "hasAuthenticatedSyntheticExchange",
            "hasSyntheticMxH1Proof",
            "TvLanServer", "HttpClient", "ServerSocket", "Socket(",
            "ktor", "SharedPreferences", "System.out.print",
        )) assertFalse(code.contains(forbidden), "Forbidden TV authority bypass: " + forbidden)
        val stage = source(
            "local-control/onboarding/src/main/kotlin/" +
                "app/muxtv/localcontrol/onboarding/ProtectedSourceStaging.kt",
        )
        assertContains(stage, "prepared.remove(principal.sessionId)")
        assertContains(stage, "return@withLock SourceStagingResult.ACTIVATION_OUTCOME_UNKNOWN")
        val app = source("app/phone/build.gradle.kts")
        assertFalse(app.contains("project(\":local-control:onboarding\")"))
        val legacy = source(
            "local-control/server/src/main/kotlin/app/muxtv/localcontrol/server/LanTransport.kt",
        )
        assertFalse(legacy.contains("Lc04TvSourceCommandSession"))
        assertFalse(legacy.contains("Lc04SourceCommandCodec"))
    }

    @Test fun `exact head tests and stop specification are mandatory`() {
        val workflow = source(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
        for (required in listOf(
            ":local-control:onboarding:test",
            ":local-control:protected-channel:test",
            "Lc04SourceCommandBoundaryContractTest",
            "Lc04SourceCommandCodecTest",
            "Lc04TvSourceCommandSessionTest",
        )) assertContains(workflow, required)
        assertFalse(workflow.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-s2j3a-source-command-boundary-20261110.md",
        ).isFile)
    }
}
