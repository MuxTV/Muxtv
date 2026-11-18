package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S2J3D2B2: full APPDATA source TCP requires exact original F6/EOF ownership. */
class Lc04SourceCommandTcpContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private val dir = "local-control/mxh1-lan/src/main/kotlin/app/muxtv/localcontrol/mxh1lan/"
    private fun file(path: String): String = root.resolve(path).readText()

    @Test fun `TCP source server cannot bypass real F6 or TV-owned dispatcher`() {
        val code = file(dir + "Lc04SourceCommandTcpSession.kt")
        for (must in listOf(
            "class Lc04SourceCommandTcpTv private constructor(",
            "startAfterCompletedF6OnApprovedPrivateIpv4(",
            "completedHandshake.takeSourceKeyPortAfterRealF6OutputClose()",
            "Lc04TvOriginalAeadCommandOwner",
            "fromOriginalTvRecordsAfterRealF6(",
            "bridge.acceptAuthenticatedRecord(request)",
            "Lc04SourceFirstFrameIo.read(input)",
            "Lc04SourceFirstFrameIo.write(output, response)",
            "selectedRouteEligible() && isTvVisible()",
            "peer.shutdownOutput()",
            "private const val MAX_COMMANDS = 16",
            "cleanupResult = try {",
            "bridge.discardPreparedAfterTvStop()",
            "bridge.close()",
        )) assertContains(code, must)
        for (forbidden in listOf(
            "import app.muxtv.catalog",
            "PairingGrant", "PairingCapability", "HttpClient",
            "SharedPreferences", "ServerSocket(0, 1, InetAddress.getLoopbackAddress())",
            "System.out.print", "printStackTrace", "getByName(",
            "bindProcessToNetwork", "fun sendPlaintext(",
        )) assertFalse(code.contains(forbidden), forbidden)
        val legacy = file(
            "local-control/server/src/main/kotlin/app/muxtv/localcontrol/server/LanTransport.kt",
        )
        assertFalse(legacy.contains("Lc04SourceCommandTcpTv"))
        assertFalse(legacy.contains("Lc04SourceCommandTcpPhone"))
        assertFalse(legacy.contains("Lc04TvOriginalAeadCommandOwner"))
    }

    @Test fun `phone binds one real selected Network socket before credential transmission`() {
        val src = file(dir + "Lc04SourceCommandTcpSession.kt")
        for (must in listOf(
            "class Lc04SourceCommandTcpPhone private constructor(",
            "completedHandshake.completeSourceKeyHandoffAfterTvEof()",
            "Lc04PrivateIpv4.parse(address)",
            "bindUnconnectedToSelectedNetwork(peer)",
            "peer.connect(endpoint, CONNECT_TIMEOUT_MS)",
            "records.sealRequest(build(seq))",
            "records.openReply(response)",
            "prepareM3u(locator: String)",
            "fun prepareXtream(",
            "fun requestActivate(",
            "fun cancel()",
            "private val sendLock = Any()",
            "if (!stopped.compareAndSet(false, true)) return",
            "runCatching { socket?.close() }",
            "records.close()",
            "revokeOriginalPhoneOwner()",
            "MAX_COMMANDS = 16L",
        )) assertContains(src, must)
        val build = file("local-control/mxh1-lan/build.gradle.kts")
        assertContains(build, """:local-control:onboarding""")
        assertFalse(build.contains("implementation(project(\":catalog:api\"))"))
        val appPhone = file("app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt")
        val appTv = file("app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt")
        assertFalse(appPhone.contains("Lc04SourceCommandTcpPhone"))
        assertFalse(appTv.contains("Lc04SourceCommandTcpTv"))
    }

    @Test fun `genuine two socket source command and hostile tests are hosted`() {
        val tests = file("local-control/mxh1-lan/src/test/kotlin/" +
            "app/muxtv/localcontrol/mxh1lan/Lc04SourceCommandTcpSessionTest.kt")
        for (name in listOf(
            "originalF6AndSecondTcpM3uPrepareTvDpadActivate",
            "originalF6AndSecondTcpXtreamPasswordSecretSafeCancel",
            "forgedAppdataRecordOnSecondTcpNeverCallsOnboarding",
            "replayedValidCiphertextNeverPreparesTwice",
            "phoneRouteLossOnStopReleasesPreparedSource",
            "tvInvisibleBeforeDpadApprovalRevokesAllSourceAuthority",
            "oversizedAppdataFrameIsTerminalBeforeAllocation",
            "sourceCommandSessionRejectsSeventeenthRequest",
        )) assertContains(tests, name)
        val wf = file(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
        assertContains(wf, ":local-control:mxh1-lan:test")
        assertContains(wf, ":local-control:onboarding:test")
        assertContains(wf, "Lc04SourceCommandTcpContractTest")
        assertContains(wf, ":app:tv:compileDebugKotlin :app:phone:compileDebugKotlin")
        assertTrue(root.resolve(
            ".work/specifications/lc04-s2j3d2b2-multicommand-source-tcp-20261119.md",
        ).isFile)
    }
}
