package app.muxtv.buildlogic

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * S2J2C2C exact-source boundary: the installed apps may use only the
 * original TV QR's six-frame synthetic facade, never a source-data port.
 * Hostile TCP and Android device tests remain separately mandatory.
 */
class Lc04MxH1SealedAndroidIntegrationContractTest {
    private val root = File(System.getProperty("user.dir")).parentFile.parentFile

    private fun source(path: String): String = root.resolve(path).readText()

    @Test fun `TV keeps original consent, selected route and synchronous owner revocation`() {
        val controller = source(
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/TvPairingController.kt",
        )
        for (required in listOf(
            "suspend fun enableSealedMxH1ForApprovedVisibleQr()",
            "receiver.hasApprovedPhoneForSyntheticMxH1(",
            "requireNotNull(nativeProbeServer)",
            "networkManager.activeNetwork == selected",
            "NetworkCapabilities.TRANSPORT_WIFI",
            "NetworkCapabilities.TRANSPORT_ETHERNET",
            "NetworkCapabilities.TRANSPORT_VPN",
            "NetworkCapabilities.TRANSPORT_CELLULAR",
            "host == observedAddress && host == originalEndpoint.privateIpv4",
            "Lc04MxH1SealedPrivateLanTv.startOnApprovedPrivateIpv4(",
            "sealedMxH1Server = listener",
            "sealedListener?.close()",
            "require(mxh1Server == null)",
            "require(sealedMxH1Server == null)",
        )) assertContains(controller, required)
        val main = source("app/tv/src/main/kotlin/app/muxtv/MainActivity.kt")
        assertContains(main, "pairingController.enableSealedMxH1ForApprovedVisibleQr()")
        assertContains(main, "requestLocalNetworkPermission()")
        assertContains(main, "stopPhonePairing() // active LAN interface")
        val tvSurface = source(
            "app/tv/src/main/kotlin/app/muxtv/localcontrol/PhonePairingRoute.kt",
        )
        assertContains(tvSurface, "local-tv-mxh1-second-opt-in")
        assertContains(tvSurface, "MXH1/MXR1")
    }

    @Test fun `phone binds a single unconnected socket and revokes selected route`() {
        val phone = source("app/phone/src/main/kotlin/app/muxtv/phone/PhoneNativeProofSession.kt")
        for (required in listOf(
            "fun beginSealedMxH1(endpoint: PhoneNativeEndpoint)",
            "phase.compareAndSet(Phase.VERIFIED, Phase.MXH1_RUNNING)",
            "flow?.hasAuthenticatedReceipt() != true",
            "Lc04MxH1SealedPrivateLanPhone.forSelectedPrivateIpv4(",
            "selectedRouteEligible = {",
            "phase.get() == Phase.MXH1_RUNNING && eligible(selected, addresses)",
            "selected.bindSocket(unconnected)",
            "if (!eligible(selected, addresses)) {",
            "client.completeSyntheticExchange()",
            "sealedMxH1Client?.close()",
            "Build.VERSION.SDK_INT >= 37",
            "Manifest.permission.ACCESS_LOCAL_NETWORK",
            "signingPhone.close()",
        )) assertContains(phone, required)
        val phoneUi = source("app/phone/src/main/kotlin/app/muxtv/phone/PhoneQrActivity.kt")
        assertContains(phoneUi, "session.beginSealedMxH1(endpoint)")
        assertContains(phoneUi, "lc04-phone-mxh1-second-opt-in")
        assertContains(phoneUi, "override fun onStop()")
        assertContains(phoneUi, "nativeSession?.close()")
    }

    @Test fun `synthetic facade cannot become an HTTP, source or plaintext transport`() {
        val facade = source(
            "local-control/mxh1-lan/src/main/kotlin/" +
                "app/muxtv/localcontrol/mxh1lan/Lc04MxH1SealedPrivateLanPhone.kt",
        )
        for (required in listOf(
            "class Lc04MxH1SealedPrivateLanPhone",
            "Lc04MxH1LeasedSealedPhone.forSelectedPrivateIpv4(",
            "fun completeSyntheticExchange(): Boolean",
            "privateIpv4: String",
            "bindUnconnectedToSelectedNetwork: (Socket) -> Unit",
            "revokeOriginalQrAndKeys: () -> Unit",
        )) assertContains(facade, required)
        val tv = source(
            "local-control/mxh1-lan/src/main/kotlin/" +
                "app/muxtv/localcontrol/mxh1lan/Lc04MxH1SealedPrivateLanTv.kt",
        )
        for (required in listOf(
            "class Lc04MxH1SealedPrivateLanTv",
            "originalTvQrOwner.hasApprovedPhoneForSyntheticMxH1(now())",
            "Lc04PrivateIpv4.parse(address)",
            "tv.acceptMxH1SyntheticProbeForLab(fifth, now())",
            "input.read() == -1",
            "Lc04MxH1LeasedSealedFrameIo.write(output, sixth)",
            "socket.shutdownOutput()",
            "tv.close()",
        )) assertContains(tv, required)
        for (file in listOf(facade, tv)) for (forbidden in listOf(
            "SourceOnboarding", "SourcePreparationRequest", "SourcePreparationHandle",
            "HttpClient", "SharedPreferences", "getPassword(", "getUsername(",
            "InetAddress.getByName(", "bindProcessToNetwork",
        )) assertFalse(file.contains(forbidden), forbidden)
        val setup = source(".github/workflows/lc04-mxh1-android-private-lan-ci.yml")
        assertContains(setup, "Lc04MxH1SealedAndroidIntegrationContractTest")
        assertContains(setup, ":local-control:mxh1-lan:test")
        assertContains(setup, ":app:tv:compileDebugKotlin :app:phone:compileDebugKotlin")
        assertTrue(root.resolve(
            ".work/specifications/lc04-mxh1-android-sealed-network-20261110.md",
        ).isFile)
    }
}
