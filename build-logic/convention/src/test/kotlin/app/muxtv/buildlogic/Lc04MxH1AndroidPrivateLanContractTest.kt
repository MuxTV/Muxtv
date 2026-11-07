package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2I opt-in Android MXH1 uses the original TV QR and selected Wi-Fi/Ethernet.
 * These structural checks complement (not replace) the hostile TCP/Android tests.
 */
class Lc04MxH1AndroidPrivateLanContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    @Test fun `private IP module has no source or HTTP routes and cannot bind wildcard`() {
        val gradle = root.resolve("local-control/mxh1-lan/build.gradle.kts").readText()
        for (required in listOf(":local-control:tv-qr", ":local-control:mxh1-session-lab",
            ":local-control:wire-lab", ":local-control:native-transport")) {
            assertContains(gradle, required)
        }
        for (blocked in listOf(":catalog:", ":core:credentials", ":local-control:onboarding",
            ":local-control:server", "libs.okhttp", "libs.ktor", "com.android.")) {
            assertFalse(gradle.contains(blocked), blocked)
        }
        val src = root.resolve("local-control/mxh1-lan/src/main/kotlin/" +
            "app/muxtv/localcontrol/mxh1lan/Lc04MxH1PrivateLan.kt").readText()
        for (required in listOf(
            "class Lc04MxH1PrivateLanTv", "class Lc04MxH1PrivateLanPhone",
            "startOnApprovedPrivateIpv4(", "forPrivateIpv4OnNetwork(",
            "originalTvOwner.hasApprovedPhoneForSyntheticMxH1(now())",
            "Lc04PrivateIpv4.parse(ipLiteral)", "bindBeforeConnect(socket)",
            "ServerSocket(0, 1, ip)", "Lc04HandoffKind.SIGNED_SEED_OFFER",
            "Lc04HandoffKind.TV_KEY_CONFIRM", "Lc04HandoffKind.PHONE_KEY_CONFIRM",
            "Lc04HandoffKind.TV_FINISHED", "phone.acceptTvFinished(finished)",
            "input.read() == -1", "socket.shutdownOutput()", "tv.close()",
        )) assertContains(src, required)
        for (blocked in listOf("SourceOnboarding", "SourcePreparationHandle",
            "SourcePreparationRequest", "HttpClient", "SharedPreferences",
            "android.content.", "InetAddress.getByName(", "0.0.0.0",
            "getPassword(", "getUsername(", "output.writeInt(0)")) {
            assertFalse(src.contains(blocked), blocked)
        }
        assertContains(root.resolve("settings.gradle.kts").readText(),
            "":local-control:mxh1-lan"")
    }

    @Test fun `Android MXH1 requires two explicit gestures and same network owner`() {
        val controller = root.resolve("app/tv/src/main/kotlin/" +
            "app/muxtv/localcontrol/TvPairingController.kt").readText()
        for (required in listOf(
            "suspend fun enableNativeProbeForVisibleQr()",
            "suspend fun enableMxH1ForApprovedVisibleQr()",
            "requireNotNull(nativeProbeServer)",
            "receiver.hasApprovedPhoneForSyntheticMxH1(",
            "nativeEndpoint.privateIpv4", "networkManager.activeNetwork",
            "Lc04MxH1PrivateLanTv.startOnApprovedPrivateIpv4(",
            "mxh1Listener?.close()", "publicQrSession?.close()",
            "watchDefaultNetworkForNative(", "watchNetwork(network)",
        )) assertContains(controller, required)
        assertFalse(controller.contains("Lc04MxH1TvOwnerLab"))
        val tv = root.resolve("app/tv/src/main/kotlin/" +
            "app/muxtv/localcontrol/PhonePairingRoute.kt").readText()
        assertContains(tv, "local-tv-native-opt-in")
        assertContains(tv, "local-tv-mxh1-second-opt-in")
        assertContains(tv, "onEnableMxH1()")
        assertContains(tv, "phoneKeyApproved")
        val phone = root.resolve("app/phone/src/main/kotlin/" +
            "app/muxtv/phone/PhoneNativeProofSession.kt").readText()
        for (required in listOf(
            "fun beginMxH1(endpoint: PhoneNativeEndpoint)",
            "phase.compareAndSet(Phase.VERIFIED, Phase.MXH1_RUNNING)",
            "flow?.hasAuthenticatedReceipt() != true",
            "selectedNetwork", "selectedAddresses",
            "Lc04MxH1PrivateLanPhone.forPrivateIpv4OnNetwork(",
            "selected.bindSocket(unconnected)", "!eligible(selected, addresses)",
            "mxh1Client?.close()", "mxh1Phone?.close()",
            "Build.VERSION.SDK_INT >= 37",
            "Manifest.permission.ACCESS_LOCAL_NETWORK",
        )) assertContains(phone, required)
        val activity = root.resolve("app/phone/src/main/kotlin/" +
            "app/muxtv/phone/PhoneQrActivity.kt").readText()
        for (required in listOf(
            "lc04-phone-native-opt-in", "lc04-phone-mxh1-second-opt-in",
            "PhoneNativeProofSession.State.VERIFIED",
            "startMxH1FromGesture()", "nativeSession?.close()",
            "override fun onStop()", "scanEpoch.incrementAndGet()",
        )) assertContains(activity, required)

        for (app in listOf("app/tv/build.gradle.kts", "app/phone/build.gradle.kts")) {
            assertContains(root.resolve(app).readText(),
                """implementation(project(":local-control:mxh1-lan"))""")
        }
        assertFalse(root.resolve("app/tv/build.gradle.kts").readText().contains(
            """implementation(project(":local-control:mxh1-session-lab"))""",
        ))
        val old = root.resolve("local-control/native-transport/src/main/kotlin/" +
            "app/muxtv/localcontrol/nativetransport/Lc04NativeTcpTransport.kt").readText()
        assertFalse(old.contains("Lc04MxH1PrivateLan"))
        assertFalse(old.contains("TV_FINISHED"))
        assertFalse(root.resolve("app/phone/src/main/AndroidManifest.xml")
            .readText().contains("usesCleartextTraffic=\"true\""))
    }

    @Test fun `hosted CI tests socket kernel and Android API26 API36 provider regression`() {
        val tests = root.resolve("local-control/mxh1-lan/src/test/kotlin/" +
            "app/muxtv/localcontrol/mxh1lan/Lc04MxH1PrivateLanTest.kt").readText()
        for (required in listOf(
            "noTcpListenerBeforeExactTvLocalEd25519Approval",
            "realLoopbackFourFrameExchangeAuthenticatesFinishedAndKeepsSameQr",
            "privateLanFactoryRejectsLoopbackWildcardDnsAndPublicIpv4",
            "bindingFailureBeforeConnectRevokesSeedAndNeverAuthenticatesTv",
            "hostileFirstFrameInvalidLengthClosesOriginalTvOwner",
            "ownerCloseInterruptsPartialPeerAndCancelsProof",
            "phoneCloseInterruptsSilentPeerAndRevokesAttempt",
            "expiredOriginalQrDeniesNativeMxH1WithoutNewKeyOwner",
        )) assertContains(tests, required)
        val wf = root.resolve(
            ".github/workflows/lc04-mxh1-android-private-lan-ci.yml",
        ).readText()
        assertContains(wf, "runs-on: ubuntu-latest")
        assertContains(wf, "test \"\$(git rev-parse HEAD)\" = \"\$EXPECTED_SHA\"")
        assertContains(wf, ":local-control:mxh1-lan:test")
        assertContains(wf, "Lc04MxH1AndroidPrivateLanContractTest")
        assertContains(wf, ":app:tv:compileDebugKotlin")
        assertContains(wf, ":app:phone:compileDebugKotlin")
        assertFalse(wf.contains("self-hosted"))
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-android-private-lan-20261107.md",
        ).isFile)
    }
}
