package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * LC04-S2J3D2A: a real socket is not the same as source authorization.
 * The first APPDATA request must authenticate and be a fixed no-op proof.
 */
class Lc04SourceFirstRequestTcpContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile
    private val module =
        "local-control/mxh1-lan/src/main/kotlin/app/muxtv/localcontrol/mxh1lan/"
    private val testModule =
        "local-control/mxh1-lan/src/test/kotlin/app/muxtv/localcontrol/mxh1lan/"

    private fun read(path: String): String = root.resolve(path).readText()

    @Test fun `first TCP request is limited to AEAD authenticated cancel proof`() {
        val src = read(module + "Lc04SourceFirstRequestTcp.kt")
        for (required in listOf(
            "class Lc04SourceFirstRequestTv private constructor(",
            "class Lc04SourceFirstRequestPhone private constructor(",
            "owner.hasSyntheticMxH1Proof(current)",
            "records.openRequest(wire)",
            "command !is Lc04SourceCommand.Cancel",
            "command.sequence != 1L",
            "if (input.read() != -1 || !eligible()) return@use false",
            "Lc04SourceReply(1, Lc04SourceReplyCode.EMPTY)",
            "socket.shutdownOutput()",
            "records.sealRequest(Lc04SourceCommand.Cancel(1))",
            "records.openReply(response)",
            "input.read() != -1",
            "fun proveAuthenticatedFirstRequest(): Boolean",
            "if (!consumed.compareAndSet(false, true))",
            "private const val PROOF_TTL_MS = 30_000L",
            "private const val MAX_WIRE_BYTES = 6_734",
        )) assertContains(src, required)
        for (forbidden in listOf(
            "SourcePreparationRequest", "SourcePreparationHandle",
            "ProtectedSourcePrincipal", "PairingGrant", "PairingCapability",
            "CatalogRepository", "SharedPreferences", "HttpClient",
            "InetAddress.getByName(", "bindProcessToNetwork",
            "fun prepareSource(", "fun activateSource(",
            "Lc04SourceCommand.PrepareM3u(", "Lc04SourceCommand.PrepareXtream(",
            "Lc04SourceCommand.Activate(",
        )) assertFalse(src.contains(forbidden), forbidden)
    }

    @Test fun `real TCP proof enforces selected route and owns both sockets`() {
        val src = read(module + "Lc04SourceFirstRequestTcp.kt")
        for (required in listOf(
            "Lc04PrivateIpv4.parse(address)",
            "Lc04PrivateIpv4.parse(ip)",
            "Lc04PrivateIpv4.parse(socket.inetAddress.hostAddress)",
            "bindUnconnectedToSelectedNetwork(peer)",
            "peer.connect(endpoint, CONNECT_TIMEOUT_MS)",
            "peer.shutdownOutput()",
            "runCatching { active?.close() }",
            "records.close()",
            "owner.close()",
            "if (completed) {",
            "revokeOriginalPhoneOwner()",
            "return ByteArray(size).also { input.readFully(it) }",
        )) assertContains(src, required)
        for (bad in listOf("SourceOnboarding", "SocketFactory.getDefault",
            "InetAddress.getByName(", "0.0.0.0", "usesCleartextTraffic")) {
            // Comments may discuss the denied SourceOnboarding operation;
            // there must be no dependency/import/call to it.
            if (bad == "SourceOnboarding") assertFalse(src.contains("import app.muxtv.catalog"))
            else assertFalse(src.contains(bad), bad)
        }
        val appPhone = read("app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt")
        val appTv = read("app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt")
        for (installed in listOf(appPhone, appTv)) {
            assertFalse(installed.contains("Lc04SourceFirstRequestTv"))
            assertFalse(installed.contains("Lc04SourceFirstRequestPhone"))
        }
    }

    @Test fun `hostile original QR across two TCP sockets is mandatory in CI`() {
        val tests = read(testModule + "Lc04SourceFirstRequestTcpTest.kt")
        for (name in listOf(
            "originalTvQrWithTwoRealTcpConnectionsAcceptsAuthenticatedFirstAppRequest",
            "f.awaitTvRevoke() // success consumes the original TV QR as well",
            "noAppTcpListenerBeforeRealSixthAndOriginalTvApproval",
            "forgedFirstAppdataFrameRevokesOriginalTvQrWithoutReply",
            "validEncryptedFirstRequestWithTrailingByteIsRejectedBeforeReply",
            "authenticPrepareM3uCannotCrossFirstRequestOnlySourceGate",
            "sourceRouteRevokedBeforeConnectNeverBindsPhoneSocket",
            "oversizedFirstFrameFailsClosedBeforeAllocation",
            "tvOnStopClosesPartialFirstAppRequestAndOriginalKeys",
            "wrongOriginalQrSessionCannotAuthenticateFirstAppTcpRequest",
        )) assertContains(tests, name)
        val gradle = read("local-control/mxh1-lan/build.gradle.kts")
        assertContains(gradle, """:local-control:source-command-wire""")
        val wf = read(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
        assertContains(wf, "Lc04SourceFirstRequestTcpContractTest")
        assertContains(wf, ":local-control:mxh1-lan:test")
        assertContains(wf, ":app:tv:compileDebugKotlin :app:phone:compileDebugKotlin")
        assertTrue(root.resolve(
            ".work/specifications/lc04-s2j3d2a-first-appdata-tcp-proof-20261118.md",
        ).isFile)
    }
}
