package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structural STOP gates only. Actual HPKE/Ed25519/AES-GCM proofs use JVM tests. */
class Lc04MxH1VerifiedOwnerTransitionContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `actual TV and phone owner carry only opaque synthetic probes`() {
        val tv = root.resolve("local-control/tv-qr/src/main/kotlin/" +
            "app/muxtv/localcontrol/tvqr/TvHpkeQrSession.kt").readText()
        val phone = root.resolve("local-control/mxh1-session-lab/src/main/kotlin/" +
            "app/muxtv/localcontrol/mxh1/Lc04MxH1PhoneLab.kt").readText()
        val port = root.resolve("local-control/protected-channel/src/main/kotlin/" +
            "app/muxtv/localcontrol/protectedchannel/Lc04MxH1SyntheticProbePort.kt").readText()

        for (required in listOf(
            "finishSyntheticMxH1ForProbeLab(",
            "mxh1Keys?.handOffToSyntheticProbeLab(",
            "phoneSide = false",
            "mxh1PhoneProvedFinalFrame",
            "acceptMxH1SyntheticProbeForLab(",
            "sealMxH1SyntheticProbeForLab(",
            "mxh1ProbePort?.close()",
            "mxh1Keys?.close()", // original default closing behavior retained
        )) assertContains(tv, required)
        for (required in listOf(
            "acceptTvFinishedForProbeLab(",
            "keys?.verifySyntheticTvFinished(digest, tag)",
            "keys?.handOffToSyntheticProbeLab(",
            "phoneSide = true",
            "mxh1ProbePort?.close()",
            "keys?.close()", // original default closing behavior retained
        )) assertContains(phone, required)
        for (required in listOf(
            "interface Lc04MxH1SyntheticProbePort : AutoCloseable",
            "fun sealSyntheticProbe(): ByteArray?",
            "fun acceptSyntheticProbe(wire: ByteArray): Boolean",
            "override fun close()",
        )) assertContains(port, required)
        for (blocked in listOf(
            "SourceOnboarding", "SourcePreparationHandle", "SourcePreparationRequest",
            "Xtream", "M3U", "HttpClient", "Socket", "Password",
            "fun encrypt(", "fun send(", "fun receivePlaintext",
        )) assertFalse(port.contains(blocked), blocked)
    }

    @Test fun `installed Android MXH1 path cannot call experimental key transfer`() {
        for (path in listOf(
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt",
            "app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt",
            "local-control/mxh1-lan/src/main/kotlin/app/muxtv/localcontrol/mxh1lan/Lc04MxH1PrivateLan.kt",
            "local-control/mxh1-tcp-lab/src/main/kotlin/app/muxtv/localcontrol/mxh1tcp/Lc04MxH1LoopbackTcpLab.kt",
        )) {
            val src = root.resolve(path).readText()
            for (forbidden in listOf(
                "finishSyntheticMxH1ForProbeLab(",
                "acceptTvFinishedForProbeLab(",
                "sealMxH1SyntheticProbeForLab(",
                "acceptMxH1SyntheticProbeForLab(",
                "handOffToSyntheticProbeLab(",
            )) assertFalse(src.contains(forbidden), "$path: $forbidden")
        }

        val test = root.resolve("local-control/mxh1-tcp-lab/src/test/kotlin/" +
            "app/muxtv/localcontrol/mxh1tcp/Lc04MxH1VerifiedOwnerTransitionTest.kt")
            .readText()
        for (required in listOf(
            "tvCannotSendRecordsBeforePhoneProvesReceptionOfFinished",
            "forgedTvFinishedCannotGrantPhoneARecordOwner",
            "defaultInstalledHandshakeStillClosesAllKeysAndOffersNoRecord",
            "forgedFirstPostFinishedRecordRevokesOriginalTvQr",
            "replayOfCorrectPostFinishedRecordRevokesOwner",
            "tvStopBetweenFinishedAndPhoneProofRevokesAndCannotResume",
            "ownerExpiryAfterAuthenticatedFinishedDeniesAllRecords",
        )) assertContains(test, required)
        val workflow = root.resolve(".github/workflows/lc04-mxh1-loopback-tcp-ci.yml")
            .readText()
        assertContains(workflow, ":local-control:mxh1-tcp-lab:test")
        assertContains(workflow, "Lc04MxH1VerifiedOwnerTransitionContractTest")
        assertTrue(root.resolve(".work/specifications/" +
            "lc04-mxh1-verified-record-owner-transition-20261109.md").isFile)
    }
}
