package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Structural evidence is additive to actual hostile TCP + Tink/AES JVM tests. */
class Lc04MxH1SealedLoopbackTcpContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `six-frame sealed TCP remains loopback-only and source-free`() {
        val src = root.resolve(
            "local-control/mxh1-tcp-lab/src/main/kotlin/app/muxtv/localcontrol/" +
                "mxh1tcp/Lc04MxH1SealedLoopbackTcpLab.kt",
        ).readText()
        for (required in listOf(
            "internal class Lc04MxH1SealedLoopbackTv",
            "internal class Lc04MxH1SealedLoopbackPhone",
            "internal object Lc04MxH1SealedFrameIo",
            "ServerSocket(0, 1, sealedLoopback())",
            "InetSocketAddress(sealedLoopback(), port)",
            "finishSyntheticMxH1ForProbeLab(phoneConfirm, now)",
            "phone.acceptTvFinishedForProbeLab(",
            "tv.acceptMxH1SyntheticProbeForLab(fifth, now())",
            "tv.sealMxH1SyntheticProbeForLab(now())",
            "phone.sealMxH1SyntheticProbeForLab()",
            "phone.acceptMxH1SyntheticProbeForLab(sixth)",
            "val ended = input.read() == -1",
            "success = input.read() == -1",
            "socket.shutdownOutput()",
            "private const val SYNTHETIC_RECORD_BYTES = 104",
            "if (length != SYNTHETIC_RECORD_BYTES)",
            "tv.close()",
            "phone.close()",
        )) assertContains(src, required)
        for (forbidden in listOf(
            "SourceOnboarding", "SourcePreparationHandle", "SourcePreparationRequest",
            "Xtream", "HttpClient", "SharedPreferences", "android.content.",
            "SocketFactory", "InetAddress.getByName", "0.0.0.0", "192.168.",
            "fun encrypt(", "fun sendPlaintext(", "fun acceptSource(",
        )) assertFalse(src.contains(forbidden), forbidden)
    }

    @Test fun `deployed Android socket and source contracts cannot depend on sealed lab`() {
        val module = root.resolve("local-control/mxh1-tcp-lab/build.gradle.kts").readText()
        for (path in listOf(
            "app/tv/build.gradle.kts",
            "app/phone/build.gradle.kts",
            "local-control/mxh1-lan/build.gradle.kts",
            "local-control/native-transport/build.gradle.kts",
        )) {
            assertFalse(root.resolve(path).readText().contains(
                "project(\":local-control:mxh1-tcp-lab\")",
            ), path)
        }
        assertFalse(module.contains("project(\":local-control:onboarding\")"))
        assertFalse(module.contains("project(\":core:credentials\")"))
        val native = root.resolve("local-control/mxh1-lan/src/main/kotlin/" +
            "app/muxtv/localcontrol/mxh1lan/Lc04MxH1PrivateLan.kt").readText()
        assertFalse(native.contains("Lc04MxH1SealedLoopback"))
        assertFalse(native.contains("Lc04MxH1SealedFrameIo"))

        val tests = root.resolve("local-control/mxh1-tcp-lab/src/test/kotlin/" +
            "app/muxtv/localcontrol/mxh1tcp/Lc04MxH1SealedLoopbackTcpTest.kt").readText()
        for (required in listOf(
            "sixRealTcpFramesCompleteOnlyAfterPhoneAndTvEof",
            "tamperedFifthAeadTagDeniesSixthAndRevokesOriginalQr",
            "extraPipelinedByteAfterValidFifthIsTerminal",
            "duplicateFifthFrameBeforeHalfCloseIsRejected",
            "invalidRecordSizeAndTruncationRevokeKeys",
            "tvOnStopDuringPartialFifthFrameCancelsAndRevokes",
            "delayedFifthAfterQrExpiryCannotProduceSixth",
            "activeMitmFifthTagCorruptionIsNotAccepted",
        )) assertContains(tests, required)
        val ci = root.resolve(".github/workflows/lc04-mxh1-loopback-tcp-ci.yml")
            .readText()
        assertContains(ci, "Lc04MxH1SealedLoopbackTcpContractTest")
        assertContains(ci, ":local-control:mxh1-tcp-lab:test")
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-sealed-loopback-tcp-20261109.md",
        ).isFile)
    }
}
