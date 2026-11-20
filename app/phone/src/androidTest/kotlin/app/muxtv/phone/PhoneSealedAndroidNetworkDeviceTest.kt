package app.muxtv.phone

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1SealedPrivateLanPhone
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1SealedPrivateLanTv
import app.muxtv.localcontrol.mxh1lan.Lc04SourceCommandTcpPhone
import app.muxtv.localcontrol.mxh1lan.Lc04SourceCommandTcpTv
import app.muxtv.localcontrol.onboarding.ProtectedSourceStaging
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
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
    private class Handle : SourcePreparationHandle()

    private class FakeOnboarding : SourceOnboarding {
        private val handle = Handle()
        val prepared = AtomicInteger(0)
        val activated = AtomicInteger(0)
        val canceled = AtomicInteger(0)
        @Volatile var locator: String? = null

        override suspend fun prepare(
            locator: String, insecureHttpApproved: Boolean,
        ): SourcePreparationResult =
            prepare(SourcePreparationRequest.M3u(locator, insecureHttpApproved))

        override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
            check(request is SourcePreparationRequest.M3u)
            check(!request.insecureHttpApproved)
            locator = request.locator
            prepared.incrementAndGet()
            return SourcePreparationResult.Prepared(handle, "https://provider.invalid")
        }

        override suspend fun activate(
            handle: SourcePreparationHandle, sourceName: String,
        ): SourceActivationResult {
            check(handle === this.handle)
            check(sourceName == "Каналы")
            activated.incrementAndGet()
            return SourceActivationResult.Activated
        }

        override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult {
            check(handle === this.handle)
            canceled.incrementAndGet()
            return SourceCancellationResult.Removed
        }

        override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? = null
    }
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
        var appTv: Lc04SourceCommandTcpTv? = null
        var appPhone: Lc04SourceCommandTcpPhone? = null

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

        fun openSourceServer(): Lc04MxH1SealedPrivateLanTv =
            Lc04MxH1SealedPrivateLanTv.startOnApprovedPrivateIpv4WithSourceKeyHandoff(
                address, tv, SystemClock::elapsedRealtime, ::routeStillSelected,
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
            appPhone?.close()
            appTv?.close()
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


    @Test fun selectedAndroidNetworkOriginalF6DelayedAppdataM3uTvConsentActivates() =
        fixture { f ->
            f.approveOnOriginalTv()
            val backend = FakeOnboarding()
            val handshakeTv = f.openSourceServer()
            val phone = f.openPhone(handshakeTv.port)

            // The exact original Android-bound F1-F6/EOF socket is spent now.
            // Delayed TV APPDATA port does not exist when phone authenticates F6.
            assertThat(phone.prepareSourceKeyHandoffForLaterAppdata()).isTrue()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!handshakeTv.hasAuthenticatedSyntheticExchange() &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            assertThat(handshakeTv.hasAuthenticatedSyntheticExchange()).isTrue()
            assertThat(f.bindings.get()).isEqualTo(1)

            val tv = Lc04SourceCommandTcpTv.startAfterCompletedF6OnApprovedPrivateIpv4(
                address = f.address,
                completedHandshake = handshakeTv,
                originalTvQrOwner = f.tv,
                staging = ProtectedSourceStaging(backend),
                now = SystemClock::elapsedRealtime,
                selectedRouteEligible = f::routeStillSelected,
                isTvVisible = { true },
            ).also { f.appTv = it }

            // Android Network.bindSocket before connect on a DIFFERENT socket,
            // but with exactly the original F6 owner (not a new handshake).
            val app = checkNotNull(
                Lc04SourceCommandTcpPhone.afterVerifiedTvEofOnSelectedNetwork(
                    address = f.address,
                    appdataPort = tv.port,
                    completedHandshake = phone,
                    selectedRouteEligible = f::routeStillSelected,
                    bindUnconnectedToSelectedNetwork = { unconnected ->
                        check(!unconnected.isConnected && !unconnected.isBound)
                        check(f.routeStillSelected())
                        f.network.bindSocket(unconnected)
                        check(f.routeStillSelected())
                        f.bindings.incrementAndGet()
                    },
                ),
            ).also { f.appPhone = it }
            val testM3u = "https://iptv.invalid/list.m3u?token=ANDROID_TEST_ONLY"
            assertThat(app.prepareM3u(testM3u)).isEqualTo(Lc04SourceReplyCode.PREPARED)
            assertThat(backend.locator).isEqualTo(testM3u)
            assertThat(app.requestActivate("Каналы"))
                .isEqualTo(Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED)
            assertThat(backend.activated.get()).isEqualTo(0)
            assertThat(tv.pendingActivationNameForTv()).isEqualTo("Каналы")
            assertThat(tv.approvePendingActivationOnTv()).isTrue()
            assertThat(tv.approvePendingActivationOnTv()).isFalse()
            assertThat(app.requestActivate("Каналы")).isEqualTo(Lc04SourceReplyCode.ACTIVATED)
            assertThat(backend.activated.get()).isEqualTo(1)
            assertThat(backend.prepared.get()).isEqualTo(1)
            assertThat(f.bindings.get()).isEqualTo(2)
        }


    @Test fun selectedAndroidNetworkOriginalF6AppdataM3uCancelNeverActivates() =
        fixture { f ->
            f.approveOnOriginalTv()
            val backend = FakeOnboarding()
            val handshakeTv = f.openSourceServer()
            val phone = f.openPhone(handshakeTv.port)
            assertThat(phone.prepareSourceKeyHandoffForLaterAppdata()).isTrue()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!handshakeTv.hasAuthenticatedSyntheticExchange() &&
                System.nanoTime() < deadline
            ) Thread.sleep(10)
            assertThat(handshakeTv.hasAuthenticatedSyntheticExchange()).isTrue()
            val tv = Lc04SourceCommandTcpTv.startAfterCompletedF6OnApprovedPrivateIpv4(
                address = f.address,
                completedHandshake = handshakeTv,
                originalTvQrOwner = f.tv,
                staging = ProtectedSourceStaging(backend),
                now = SystemClock::elapsedRealtime,
                selectedRouteEligible = f::routeStillSelected,
                isTvVisible = { true },
            ).also { f.appTv = it }
            val app = checkNotNull(
                Lc04SourceCommandTcpPhone.afterVerifiedTvEofOnSelectedNetwork(
                    address = f.address,
                    appdataPort = tv.port,
                    completedHandshake = phone,
                    selectedRouteEligible = f::routeStillSelected,
                    bindUnconnectedToSelectedNetwork = { socket ->
                        check(!socket.isBound && !socket.isConnected)
                        check(f.routeStillSelected())
                        f.network.bindSocket(socket)
                        check(f.routeStillSelected())
                        f.bindings.incrementAndGet()
                    },
                ),
            ).also { f.appPhone = it }
            assertThat(app.prepareM3u("https://iptv.invalid/test.m3u?token=REDACTED"))
                .isEqualTo(Lc04SourceReplyCode.PREPARED)
            assertThat(backend.prepared.get()).isEqualTo(1)
            assertThat(backend.activated.get()).isEqualTo(0)
            assertThat(tv.pendingActivationNameForTv()).isNull()
            assertThat(tv.approvePendingActivationOnTv()).isFalse()
            assertThat(app.cancel()).isEqualTo(Lc04SourceReplyCode.CANCELED)
            assertThat(backend.canceled.get()).isEqualTo(1)
            assertThat(backend.activated.get()).isEqualTo(0)
            assertThat(tv.pendingActivationNameForTv()).isNull()
            assertThat(f.bindings.get()).isEqualTo(2)
            // No second TCP connection or automatic ACTIVATE is ever made.
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
