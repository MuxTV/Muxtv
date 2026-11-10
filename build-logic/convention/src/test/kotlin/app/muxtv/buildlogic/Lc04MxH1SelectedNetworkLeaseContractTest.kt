package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2J2C1 architectural STOP: a route-eligible socket lease is not an
 * authenticated transport, permission grant or SourceOnboarding capability.
 */
class Lc04MxH1SelectedNetworkLeaseContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `selected network lease has no payload or credential capability`() {
        val path = "local-control/mxh1-lan/src/main/kotlin/" +
            "app/muxtv/localcontrol/mxh1lan/Lc04MxH1SelectedNetworkLease.kt"
        val src = root.resolve(path).readText()
        for (required in listOf(
            "internal class Lc04MxH1SelectedNetworkLease",
            "Lc04PrivateIpv4.parse(numericIp)",
            "port in 1..65535",
            "phase = Phase.BINDING",
            "bindToSelectedNetwork(unconnected)",
            "if (!check(Phase.BINDING)) return false",
            "phase = Phase.BOUND",
            "fun checkpoint(): Boolean = check(Phase.BOUND)",
            "fun revokeSelectedNetwork() = close()",
            "activeSocket?.isClosed",
            "now - started >= TTL_MILLIS",
            "revokeOriginalQrAndKeys()",
        )) assertContains(src, required)
        for (blocked in listOf(
            "SourceOnboarding", "SourcePreparationRequest", "SourcePreparationHandle",
            "Xtream", "m3u", "HttpClient", "SharedPreferences",
            "setProcessDefaultNetwork", "bindProcessToNetwork", "SocketFactory",
            "fun encrypt(", "fun send(", "fun receive(",
            "ANDROID_ID", "android.Manifest",
        )) assertFalse(src.contains(blocked), blocked)
        assertFalse(src.contains("public class Lc04MxH1SelectedNetworkLease"))
    }

    @Test fun `installed TV and phone paths cannot use unreviewed six frame lease`() {
        for (path in listOf(
            "app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt",
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt",
            "local-control/mxh1-lan/src/main/kotlin/app/muxtv/localcontrol/mxh1lan/Lc04MxH1PrivateLan.kt",
        )) {
            assertFalse(root.resolve(path).readText().contains(
                "Lc04MxH1SelectedNetworkLease",
            ), path)
        }
        // Existing manifest/targetSdk policy is a separate migration
        // decision; this experimental lease must not mutate Android apps.
        // Do not assert absence of ACCESS_LOCAL_NETWORK here: current
        // product manifests already declare it.
        val tests = root.resolve("local-control/mxh1-lan/src/test/kotlin/" +
            "app/muxtv/localcontrol/mxh1lan/Lc04MxH1SelectedNetworkLeaseTest.kt")
            .readText()
        for (required in listOf(
            "one unconnected socket can claim a private route and revocation closes it",
            "route or permission revoked after bind closes active socket synchronously",
            "onStop revokes promptly even while Android binder callback is blocked",
            "120 second TTL and elapsed clock rollback revoke original owner",
            "prebound socket is never allowed to bypass selected network bind",
        )) assertContains(tests, required)
        val workflow = root.resolve(
            ".github/workflows/lc04-mxh1-android-private-lan-ci.yml",
        ).readText()
        assertContains(workflow, "Lc04MxH1SelectedNetworkLeaseContractTest")
        assertContains(workflow, ":local-control:mxh1-lan:test")
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-selected-network-lease-20261109.md",
        ).isFile)
    }
}
