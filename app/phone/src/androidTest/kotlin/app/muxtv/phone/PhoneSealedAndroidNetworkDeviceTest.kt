package app.muxtv.phone

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1SealedPrivateLanPhone
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1SealedPrivateLanTv
import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S2J2C2D Android-only evidence, hosted API26/API36. This uses a REAL Android
 * ConnectivityManager-selected Network and Network.bindSocket(unconnected),
 * not the JVM test-only redirect. Both peers execute in one emulator/app
 * process, however: passing is NOT two-device interoperability evidence,
 * API37 permission evidence or authority to transmit IPTV credentials.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSealedAndroidNetworkDeviceTest {
    private class Fixture : AutoCloseable {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val network: Network = requireNotNull(manager.activeNetwork) {
            "Hosted Android phone AVD has no active selected Network."
        }
        val caps = requireNotNull(manager.getNetworkCapabilities(network)) {
            "Selected Android Network has no capabilities."
        }
        val originalAddresses: Set<String> = requireNotNull(manager.getLinkProperties(network))
            .linkAddresses.mapNotNull { it.address.hostAddress }.toSet()
        val address: String = originalAddresses.asSequence()
            .filter { Lc04PrivateIpv4.parse(it) != null }
            .sorted().firstOrNull() ?: error("No numeric RFC1918 IPv4 on Android selected Network.")
        val tv = TvHpkeQrSession.create(SystemClock.elapsedRealtime())
        val qr = TvQrPublicKeyParser.parse(
            checkNotNull(tv.presentation(SystemClock.elapsedRealtime())).qrValue,
        )
        val signer = PhoneSigningIdentity.generate()
        val phone = Lc04MxH1PhoneLab(qr, signer, SystemClock::elapsedRealtime)
        val gate = checkNotNull(tv.phoneEnrollmentGate(SystemClock::elapsedRealtime))
        val bindings = AtomicInteger(0)
        val revoked = AtomicInteger(0)
        var listener: Lc04MxH1SealedPrivateLanTv? = null
        var client: Lc04MxH1SealedPrivateLanPhone? = null

        init {
            check(Build.VERSION.SDK_INT in 26..36) { "API37 permission needs separate device gate." }
            check(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) { "Phone AVD's selected Network is not Wi-Fi/Ethernet." }
            check(!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            ) { "Selected phone network is VPN/cellular." }
        }

        fun approveOnOriginalTv() {
            val challenge = checkNotNull(gate.begin(signer.publicKeysetBytes()))
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = gate.verify(signer.prove(qr, challenge))
                as PhoneEnrollmentResult.Verified
            assertThat(tv.activateVerifiedPhone(
                verified.identity, SystemClock.elapsedRealtime(),
            )).isTrue()
        }

        fun routeStillSelected(): Boolean {
            val current = manager.getNetworkCapabilities(network) ?: return false
            return manager.activeNetwork == network &&
                (current.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    current.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !current.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !current.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                manager.getLinkProperties(network)
                    ?.linkAddresses?.mapNotNull { it.address.hostAddress }?.toSet() ==
                    originalAddresses
        }

        fun openServer(): Lc04MxH1SealedPrivateLanTv =
            Lc04MxH1SealedPrivateLanTv.startOnApprovedPrivateIpv4(
                address, tv, SystemClock::elapsedRealtime,
            ).also { listener = it }

        fun openPhone(
            port: Int,
            routeEligible: () -> Boolean = ::routeStillSelected,
        ): Lc04MxH1SealedPrivateLanPhone =
            Lc04MxH1SealedPrivateLanPhone.forSelectedPrivateIpv4(
                privateIpv4 = address,
                port = port,
                originalPhone = phone,
                elapsedMillis = SystemClock::elapsedRealtime,
                selectedRouteEligible = routeEligible,
                bindUnconnectedToSelectedNetwork = { unconnected: Socket ->
                    check(!unconnected.isConnected && !unconnected.isBound)
                    check(routeStillSelected())
                    network.bindSocket(unconnected) // the ACTUAL Android API
                    check(routeStillSelected())
                    bindings.incrementAndGet()
                },
                revokeOriginalQrAndKeys = {
                    signer.close()
                    revoked.incrementAndGet()
                },
            ).also { client = it }

        override fun close() {
            client?.close()
            listener?.close()
            phone.close()
            signer.close()
            tv.close()
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val f = Fixture()
        try { block(f) } finally { f.close() }
    }

    @Test fun noOriginalTvLocalApprovalCannotExposeSixFramePrivateListener() =
        fixture { f ->
            assertThat(f.tv.hasApprovedPhoneForSyntheticMxH1(
                SystemClock.elapsedRealtime(),
            )).isFalse()
            assertThat(runCatching { f.openServer() }.isFailure).isTrue()
            assertThat(f.bindings.get()).isEqualTo(0)
        }

    @Test fun selectedAndroidNetworkBoundSocketCompletesAllSixAuthenticatedFrames() =
        fixture { f ->
            f.approveOnOriginalTv()
            val originalQr = checkNotNull(
                f.tv.presentation(SystemClock.elapsedRealtime()),
            ).qrValue
            val server = f.openServer() // binds the REAL Android-selected RFC1918 IP
            val client = f.openPhone(server.port)
            assertThat(client.completeSyntheticExchange()).isTrue()
            assertThat(f.bindings.get()).isEqualTo(1)
            assertThat(f.revoked.get()).isEqualTo(1)
            // The client sees TV EOF before the TV worker publishes completion.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (!server.hasAuthenticatedSyntheticExchange() &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            assertThat(server.hasAuthenticatedSyntheticExchange()).isTrue()
            assertThat(checkNotNull(
                f.tv.presentation(SystemClock.elapsedRealtime()),
            ).qrValue).isEqualTo(originalQr)
            assertThat(client.completeSyntheticExchange()).isFalse() // one-use
        }

    @Test fun deniedSelectedRouteNeverBindsSocketOrAuthenticates() =
        fixture { f ->
            f.approveOnOriginalTv()
            val server = f.openServer()
            val client = f.openPhone(server.port, routeEligible = { false })
            assertThat(client.completeSyntheticExchange()).isFalse()
            assertThat(f.bindings.get()).isEqualTo(0)
            assertThat(f.revoked.get()).isEqualTo(1)
            assertThat(server.hasAuthenticatedSyntheticExchange()).isFalse()
            assertThat(client.completeSyntheticExchange()).isFalse()
        }
}
