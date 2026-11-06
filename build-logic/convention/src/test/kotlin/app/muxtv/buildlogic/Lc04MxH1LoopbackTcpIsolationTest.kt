package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** No Android integration or source capability may be smuggled via TCP lab. */
class Lc04MxH1LoopbackTcpIsolationTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `a separate research module owns exactly one loopback MXH1 exchange`() {
        val gradle = root.resolve("local-control/mxh1-tcp-lab/build.gradle.kts").readText()
        for (required in listOf(":local-control:tv-qr", ":local-control:mxh1-session-lab",
            ":local-control:wire-lab")) assertContains(gradle, required)
        for (blocked in listOf(":catalog:", ":core:credentials", ":local-control:onboarding",
            ":local-control:server", "libs.okhttp", "libs.ktor", "com.android.",
            ":local-control:native-transport")) {
            assertFalse(gradle.contains(blocked), blocked)
        }
        val source = root.resolve("local-control/mxh1-tcp-lab/src/main/kotlin/" +
            "app/muxtv/localcontrol/mxh1tcp/Lc04MxH1LoopbackTcpLab.kt").readText()
        for (required in listOf("class Lc04MxH1LoopbackTv",
            "class Lc04MxH1LoopbackPhone",
            "object Lc04MxH1LengthFrame", "fun startLoopbackForTests(",
            "fun loopbackForTests(port:", "ServerSocket(0, 1, loopback())",
            "Lc04HandoffKind.SIGNED_SEED_OFFER", "Lc04HandoffKind.TV_KEY_CONFIRM",
            "Lc04HandoffKind.PHONE_KEY_CONFIRM", "Lc04HandoffKind.TV_FINISHED",
            "tv.finishSyntheticMxH1PhoneConfirmation(",
            "phone.acceptTvConfirmationAwaitingFinished(",
            "phone.acceptTvFinished(finished)",
            "socket.shutdownOutput()",
            "input.read() == -1", "socket.soTimeout = FRAME_TIMEOUT_MS",
            "fun hasSyntheticNetworkProof()", "tv.close()")) {
            assertContains(source, required)
        }
        for (blocked in listOf("SourceOnboarding", "SourcePreparationHandle",
            "SourcePreparationRequest", "PairingCapability.SOURCE_CREATE",
            "android.content.", "HttpClient", "SharedPreferences",
            "M3u(", "Xtream(", "getPassword(", "getUsername(",
            "Socket(\"0.0.0.0\"", "InetAddress.getByName(")) {
            assertFalse(source.contains(blocked), blocked)
        }
        assertFalse(source.contains("output.writeInt(0)"),
            "Unauthenticated terminal marker must not return")
        assertContains(root.resolve("settings.gradle.kts").readText(),
            """:local-control:mxh1-tcp-lab""")
        assertContains(root.resolve(".work/meta/modules.yaml").readText(),
            "local-control-mxh1-tcp-lab:")
    }

    @Test fun `installed TV and phone continue to dispatch MXW1 only`() {
        for (module in listOf("app/phone/build.gradle.kts", "app/tv/build.gradle.kts",
            "local-control/native-transport/build.gradle.kts",
            "local-control/server/build.gradle.kts")) {
            assertFalse(root.resolve(module).readText().contains(
                ":local-control:mxh1-tcp-lab",
            ))
        }
        val old = root.resolve("local-control/native-transport/src/main/kotlin/" +
            "app/muxtv/localcontrol/nativetransport/Lc04NativeTcpTransport.kt")
            .readText()
        assertFalse(old.contains("Lc04MxH1Loopback"))
        assertFalse(old.contains("SIGNED_SEED_OFFER"))
        assertContains(old, "Lc04LengthPrefix.read(")
        val tvController = root.resolve("app/tv/src/main/kotlin/" +
            "app/muxtv/localcontrol/TvPairingController.kt").readText()
        assertFalse(tvController.contains("Lc04MxH1Loopback"))
        assertContains(tvController, "revokePublicKeyQrPreview()")
        assertContains(tvController, "publicQrSession?.close()")
    }

    @Test fun `real TCP hostile matrix is mandatory on the exact PR commit`() {
        val tests = root.resolve("local-control/mxh1-tcp-lab/src/test/kotlin/" +
            "app/muxtv/localcontrol/mxh1tcp/Lc04MxH1LoopbackTcpLabTest.kt").readText()
        for (case in listOf(
            "oneRealSocketCompletesThreeFramesAndOriginalQrOwnership",
            "offerWithoutTvLocalApprovalRevokesOriginalOwner",
            "legacyMxw1FramesAreRejectedWithoutAutomaticDowngrade",
            "zeroNegativeOversizedAndIncompleteLengthPrefixesAllFailClosed",
            "forgedTvConfirmationCannotProducePhoneProofEvenWithMatchingDigest",
            "forgedPhoneMacAfterRealTvConfirmCannotReceiveTerminalAck",
            "extraPipelinedBytesAfterThirdFrameRevokeEntireSession",
            "tvLifecycleCloseInterruptsPeerWaitingOnPartialFirstFrame",
            "phoneLifecycleCloseInterruptsPeerThatNeverAnswers",
            "expiryClosesListenerAndNeverAcceptsLateOffer",
            "forgedFinalTvHmacAfterValidRealPhoneProofNeverCompletes",
            "zeroLengthLegacyTransportMarkerNoLongerCountsAsFinalProof",
            "authenticatedFinishedWithTrailingSmuggledByteIsNotAccepted",
            "authenticatedFinishedTruncatedBeforeLastMacByteIsNotAccepted",
            "finalFrameWithWrongKindCounterOrOfferBindingFailsClosed",
        )) assertContains(tests, case)
        val workflow = root.resolve(
            ".github/workflows/lc04-mxh1-loopback-tcp-ci.yml",
        ).readText()
        assertContains(workflow, "runs-on: ubuntu-latest")
        assertContains(workflow, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(workflow, ":local-control:mxh1-tcp-lab:test")
        assertContains(workflow, "Lc04MxH1LoopbackTcpIsolationTest")
        assertContains(workflow, "local-control/mxh1-tcp-lab/build/test-results/test/**")
        assertFalse(workflow.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-one-use-tcp-loopback-20261105.md",
        ).isFile)
    }
}
