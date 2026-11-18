package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Additional static STOP gate. Real AES-GCM/HPKE/socket tests are mandatory. */
class Lc04MxH1LeasedSealedPhoneContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `candidate only connects after the selected route lease grants endpoint`() {
        val path = "local-control/mxh1-lan/src/main/kotlin/app/muxtv/" +
            "localcontrol/mxh1lan/Lc04MxH1LeasedSealedPhone.kt"
        val src = root.resolve(path).readText()
        for (mustHave in listOf(
            "internal class Lc04MxH1LeasedSealedPhone",
            "lease.bindOnce(connected)",
            "lease.endpointIfBoundAndEligible() ?: return false",
            "connected.connect(destination, CONNECT_TIMEOUT_MS)",
            "lease.checkpoint()",
            "phone.createSignedSeedOffer()",
            "phone.acceptTvConfirmationAwaitingFinished(tvConfirm)",
            "phone.acceptTvFinishedForProbeLab(",
            "phone.sealMxH1SyntheticProbeForLab()",
            "connected.shutdownOutput()",
            "phone.acceptMxH1SyntheticProbeForLab(sixth)",
            "phone.hasAuthenticatedTvSyntheticProof()",
            "input.read() == -1",
            "private const val RECORD_BYTES = 104",
            "phone.close()",
            "lease.close()",
            "newUnconnectedSocket: () -> Socket",
            "withTestSocketForHostileJVM(",
        )) assertContains(src, mustHave)
        for (forbidden in listOf(
            "SourceOnboarding", "SourcePreparationRequest", "SourcePreparationHandle",
            "Xtream", "M3U", "SharedPreferences", "HttpClient",
            "InetAddress.getByName", "setProcessDefaultNetwork", "bindProcessToNetwork",
            "SocketFactory", "fun encrypt(", "fun sendPlaintext(",
        )) assertFalse(src.contains(forbidden), forbidden)
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-leased-sealed-phone-20261110.md",
        ).isFile)
    }

    @Test fun `test-only redirect and six-frame candidate are absent from both Android apps`() {
        for (path in listOf(
            "app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt",
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt",
            "local-control/mxh1-lan/src/main/kotlin/" +
                "app/muxtv/localcontrol/mxh1lan/Lc04MxH1PrivateLan.kt",
        )) {
            val src = root.resolve(path).readText()
            for (dangerous in listOf(
                "Lc04MxH1LeasedSealedPhone",
                "withTestSocketForHostileJVM(",
                "Lc04MxH1LeasedSealedFrameIo",
            )) assertFalse(src.contains(dangerous), "$path: $dangerous")
        }

        val tests = root.resolve("local-control/mxh1-lan/src/test/kotlin/" +
            "app/muxtv/localcontrol/mxh1lan/Lc04MxH1LeasedSealedPhoneTest.kt")
            .readText()
        for (required in listOf(
            "six actual TCP frames succeed only through exact selected endpoint",
            "forged final MXH1 MAC never sends a fifth probe",
            "corrupted encrypted TV sixth record is rejected",
            "pipelined bytes after final sealed reply violate EOF",
            "truncated or incorrectly sized sixth frame fails closed",
            "default route loss after TV confirmation cancels encrypted exchange",
            "LAN permission denied before binder sends no offer",
            "binder closes socket before connect and no fallback is attempted",
            "owner onStop during blocked TV confirmation aborts without reconnect",
            "lease expiry before connection prevents any signed frame",
            "route loss while binding prevents even the first connection",
        )) assertContains(tests, required)
        val ci = root.resolve(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
            .readText()
        assertContains(ci, ":local-control:mxh1-lan:test")
        assertContains(ci, "Lc04MxH1LeasedSealedPhoneContractTest")
        assertContains(ci, "app:phone:compileDebugKotlin")
        assertContains(ci, "app:tv:compileDebugKotlin")
    }
}
