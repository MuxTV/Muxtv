package app.muxtv.localcontrol

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.muxtv.localcontrol.PairingAuthority
import app.muxtv.localcontrol.PairingClock
import app.muxtv.localcontrol.PendingTvPairing
import app.muxtv.localcontrol.tvqr.TvHpkeQrPresentation
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.server.LanAddressPolicy
import app.muxtv.localcontrol.server.TvLanServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Activity-owned and explicitly user-invoked. No listener starts in Application.onCreate.
 * Selected active Wi-Fi/Ethernet only; a VPN/cellular default does not silently expose LAN.
 */
class TvPairingController(
    context: Context,
    private val invalidated: () -> Unit,
    private val isTvVisible: () -> Boolean,
) {
    private val networkManager =
        context.getSystemService(ConnectivityManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pairing = PairingAuthority(clock = PairingClock { SystemClock.elapsedRealtime() })
    private val server = TvLanServer(pairing)
    private val lifecycleMutex = Mutex()
    private val lifecycleEpoch = AtomicLong()
    private val publicQrEpoch = AtomicLong()
    @Volatile private var publicQrSession: TvHpkeQrSession? = null
    @Volatile private var observedNetwork: Network? = null
    @Volatile private var observedAddress: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    data class ScreenSession(val qrValue: String, val expiresAtElapsedMillis: Long) {
        override fun toString(): String = "ScreenSession(qrValue=[REDACTED])"
    }

    /** Must be invoked from background dispatcher, AFTER runtime LAN permission grant. */
    suspend fun start(): ScreenSession = lifecycleMutex.withLock {
        stopInternal()
        val invocationEpoch = lifecycleEpoch.incrementAndGet()
        val network = requireNotNull(networkManager.activeNetwork) { "No active local network." }
        val caps = requireNotNull(networkManager.getNetworkCapabilities(network)) {
            "No network capability information."
        }
        require(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            "Local control requires active Wi-Fi or Ethernet."
        }
        require(!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            "VPN/cellular network is not eligible for LAN binding."
        }
        val properties = requireNotNull(networkManager.getLinkProperties(network)) {
            "Cannot inspect active LAN addresses."
        }
        val host = selectPrivateAddress(properties)
            ?: throw IllegalStateException("No eligible private LAN address on this network.")
        val endpoint = server.start(host)
        try {
            observedNetwork = network
            observedAddress = host
            watchNetwork(network)
            val invitation = pairing.begin()
            if (invocationEpoch != lifecycleEpoch.get()) {
                throw CancellationException("TV pairing activation was revoked.")
            }
            ScreenSession(
                qrValue = "${endpoint.origin}/pair#${invitation.secret}",
                expiresAtElapsedMillis = SystemClock.elapsedRealtime() + invitation.lifetimeMillis,
            )
        } catch (failure: Exception) {
            withContext(NonCancellable) { stopInternal() }
            throw failure
        }
    }

    /**
     * Explicit TV-screen action only. This preview uses a separate, fresh HPKE receiver:
     * no existing LC03 HTTP bearer, phone enrollment, source API or network route is involved.
     * Key generation runs under the same lifecycle mutex as TV pairing shutdown.
     */
    suspend fun startPublicKeyQrPreview(): TvHpkeQrPresentation = lifecycleMutex.withLock {
        if (!isTvVisible()) throw CancellationException("TV screen is not visible.")
        val lifecycle = lifecycleEpoch.get()
        val previewGeneration = publicQrEpoch.incrementAndGet()
        publicQrSession?.close()
        publicQrSession = null
        val receiver = TvHpkeQrSession.create(SystemClock.elapsedRealtime())
        publishPublicKeyQr(receiver, lifecycle, previewGeneration)
    }

    @Synchronized
    private fun publishPublicKeyQr(
        receiver: TvHpkeQrSession,
        lifecycle: Long,
        previewGeneration: Long,
    ): TvHpkeQrPresentation {
        if (!isTvVisible() ||
            lifecycleEpoch.get() != lifecycle ||
            publicQrEpoch.get() != previewGeneration
        ) {
            receiver.close()
            throw CancellationException("TV key preview was revoked.")
        }
        val view = receiver.presentation(SystemClock.elapsedRealtime())
        if (view == null) {
            receiver.close()
            throw IllegalStateException("TV key preview expired before display.")
        }
        publicQrSession = receiver
        return view
    }

    /** Safe to call on the UI thread and from Activity.onStop. */
    @Synchronized
    fun revokePublicKeyQrPreview() {
        publicQrEpoch.incrementAndGet()
        publicQrSession?.close()
        publicQrSession = null
    }

    fun isPublicKeyQrPreviewActive(): Boolean =
        publicQrSession?.presentation(SystemClock.elapsedRealtime()) != null

    /**
     * Display-only TV-local read: never expose the pending fingerprint via legacy LAN routes.
     * A pending identity has no authority until the TV owner explicitly accepts it.
     */
    @Synchronized
    fun pendingPhonePublicKeyFingerprint(): String? {
        if (!isTvVisible()) return null
        val receiver = publicQrSession ?: return null
        if (receiver.presentation(SystemClock.elapsedRealtime()) == null) return null
        return receiver.phoneEnrollmentGate(SystemClock::elapsedRealtime)
            ?.pendingFingerprintForTv()
    }

    /**
     * This method is wired ONLY to the trusted D-pad UI.
     * It must not be imported or called by TvLanServer / plain /pair.
     */
    @Synchronized
    fun approvePhonePublicKeyOnTv(exactVisibleFingerprint: String): Boolean {
        if (!isTvVisible()) return false
        val receiver = publicQrSession ?: return false
        if (receiver.presentation(SystemClock.elapsedRealtime()) == null) return false
        val gate = receiver.phoneEnrollmentGate(SystemClock::elapsedRealtime) ?: return false
        if (gate.pendingFingerprintForTv() != exactVisibleFingerprint) return false
        return gate.approveOnTv(exactVisibleFingerprint)
    }

    /** Declining revokes the entire key QR session: stale challenges cannot be retried. */
    @Synchronized
    fun denyPhonePublicKeyOnTv(exactVisibleFingerprint: String): Boolean {
        if (!isTvVisible()) return false
        val receiver = publicQrSession ?: return false
        val gate = receiver.phoneEnrollmentGate(SystemClock::elapsedRealtime) ?: return false
        if (gate.pendingFingerprintForTv() != exactVisibleFingerprint) return false
        revokePublicKeyQrPreview()
        return true
    }

    fun pendingClaim(): PendingTvPairing? = pairing.pendingClaimForTv()
    fun approve(claimId: String): Boolean = pairing.approve(claimId)
    fun deny(claimId: String): Boolean = pairing.deny(claimId)
    fun activeSessionCount(): Int = pairing.activeSessionCount()
    fun isActive(): Boolean = observedNetwork != null && observedAddress != null

    suspend fun stop() {
        lifecycleEpoch.incrementAndGet()
        withContext(NonCancellable) {
            lifecycleMutex.withLock { stopInternal() }
        }
    }

    private suspend fun stopInternal() {
        revokePublicKeyQrPreview()
        networkCallback?.let { runCatching { networkManager.unregisterNetworkCallback(it) } }
        networkCallback = null
        observedNetwork = null
        observedAddress = null
        pairing.stop() // revoke synchronously, even if socket shutdown is delayed
        server.stop()
    }

    /** Immediate fail-closed invalidation on Activity.onStop or onDestroy. */
    fun revokeImmediately() {
        lifecycleEpoch.incrementAndGet()
        revokePublicKeyQrPreview()
        observedNetwork = null
        observedAddress = null
        pairing.stop()
    }

    private fun watchNetwork(network: Network) {
        val registrationEpoch = lifecycleEpoch.get()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(lost: Network) {
                if (network == lost) mainHandler.post {
                    if (observedNetwork == network && registrationEpoch == lifecycleEpoch.get()) invalidated()
                }
            }

            override fun onLinkPropertiesChanged(changed: Network, properties: LinkProperties) {
                if (changed == network && selectPrivateAddress(properties) != observedAddress) {
                    mainHandler.post {
                        if (observedNetwork == network && registrationEpoch == lifecycleEpoch.get() &&
                            selectPrivateAddress(properties) != observedAddress
                        ) invalidated()
                    }
                }
            }

            override fun onCapabilitiesChanged(changed: Network, capabilities: NetworkCapabilities) {
                if (changed != network) return
                if ((!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                        !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                ) mainHandler.post {
                    if (observedNetwork == network && registrationEpoch == lifecycleEpoch.get()) invalidated()
                }
            }
        }
        val request = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
        networkManager.registerNetworkCallback(request, callback)
        networkCallback = callback
    }

    private fun selectPrivateAddress(properties: LinkProperties): String? =
        properties.linkAddresses.asSequence()
            .mapNotNull { it.address.hostAddress }
            .filter(LanAddressPolicy::isPrivateLiteral)
            .sortedWith(compareBy<String> { ':' in it }.thenBy { it })
            .firstOrNull()
}
