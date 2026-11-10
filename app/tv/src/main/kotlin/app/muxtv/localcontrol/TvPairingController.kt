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
import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4
import app.muxtv.localcontrol.nativetransport.Lc04TvTcpServer
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1PrivateLanTv
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1SealedPrivateLanTv
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
    @Volatile private var nativeProbeServer: Lc04TvTcpServer? = null
    @Volatile private var nativeProbeEndpoint: NativeProbeEndpoint? = null
    @Volatile private var mxh1Server: Lc04MxH1PrivateLanTv? = null
    @Volatile private var sealedMxH1Server: Lc04MxH1SealedPrivateLanTv? = null
    @Volatile private var mxh1Endpoint: NativeProbeEndpoint? = null
    @Volatile private var observedNetwork: Network? = null
    @Volatile private var observedAddress: String? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var nativeDefaultNetworkCallback: ConnectivityManager.NetworkCallback? = null

    data class ScreenSession(val qrValue: String, val expiresAtElapsedMillis: Long) {
        override fun toString(): String = "ScreenSession(qrValue=[REDACTED])"
    }

    /** TV-visible transport locator only; not a trust assertion or a pairing token. */
    data class NativeProbeEndpoint(val privateIpv4: String, val port: Int) {
        override fun toString(): String = "NativeProbeEndpoint([PRIVATE_ADDRESS_REDACTED])"
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
        // The native-key mode never coexists with the legacy plaintext /pair
        // server. The transition closes HTTP and cancels its claims first.
        stopInternal()
        val lifecycle = lifecycleEpoch.incrementAndGet()
        val previewGeneration = publicQrEpoch.get()
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

    /**
     * Separately opted-in LC04 native listener for the already-visible, short-lived QR.
     * Uses an independently validated, permission-gated Wi-Fi/Ethernet interface.
     * The legacy HTTP listener was stopped on entry to public-key mode; it is
     * NEVER used to bootstrap the native listener or authorize the phone.
     * Only public enrollment and authenticated synthetic CHANNEL_PROBE are permitted.
     */
    suspend fun enableNativeProbeForVisibleQr(): NativeProbeEndpoint = lifecycleMutex.withLock {
        if (!isTvVisible()) throw CancellationException("TV screen is not visible.")
        val receiver = requireNotNull(publicQrSession) { "No TV public-key QR." }
        requireNotNull(receiver.presentation(SystemClock.elapsedRealtime())) {
            "TV public-key QR is expired or revoked."
        }
        nativeProbeEndpoint?.let { return@withLock it }
        val generation = publicQrEpoch.get()
        val lifecycle = lifecycleEpoch.get()
        val network = requireNotNull(networkManager.activeNetwork) {
            "No active Wi-Fi/Ethernet network."
        }
        val capabilities = requireNotNull(networkManager.getNetworkCapabilities(network))
        require((capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        ) { "Network is not eligible for private IPv4 listener." }
        val properties = requireNotNull(networkManager.getLinkProperties(network))
        val host = requireNotNull(selectPrivateAddress(properties))
        require(Lc04PrivateIpv4.parse(host) != null) {
            "Private numeric IPv4 interface is unavailable."
        }

        val listener = Lc04TvTcpServer.start(host, receiver, SystemClock::elapsedRealtime)
        try {
            synchronized(this@TvPairingController) {
                if (!isTvVisible() || generation != publicQrEpoch.get() ||
                    lifecycle != lifecycleEpoch.get() ||
                    publicQrSession !== receiver ||
                    receiver.presentation(SystemClock.elapsedRealtime()) == null ||
                    networkManager.activeNetwork != network
                ) throw CancellationException("Native listener activation was revoked.")
                val endpoint = NativeProbeEndpoint(host, listener.port)
                nativeProbeServer = listener
                nativeProbeEndpoint = endpoint
                observedNetwork = network
                observedAddress = host
                watchNetwork(network)
                watchDefaultNetworkForNative(network, lifecycle, generation)
                endpoint
            }
        } catch (failure: Throwable) {
            // Registration and publication can fail after socket creation.
            // Revoke the entire QR owner and unregister any partial callback.
            revokePublicKeyQrPreview()
            listener.close()
            throw failure
        }
    }

    /**
     * Explicit *second* TV D-pad opt-in after MXW1 enrollment. Keeps the SAME
     * QR/HPKE private receiver and TV-local-approved phone. Separate port,
     * never an extra MXW1 kind, never auto-started from a QR or /pair route.
     * The first native listener remains active for its original lifecycle.
     */
    suspend fun enableMxH1ForApprovedVisibleQr(): NativeProbeEndpoint = lifecycleMutex.withLock {
        if (!isTvVisible()) throw CancellationException("TV screen is not visible.")
        val receiver = requireNotNull(publicQrSession) { "No original TV QR." }
        require(receiver.hasApprovedPhoneForSyntheticMxH1(SystemClock.elapsedRealtime())) {
            "The original TV-local Ed25519 consent is not verified."
        }
        requireNotNull(nativeProbeServer) { "The original native enrollment is not active." }
        val nativeEndpoint = requireNotNull(nativeProbeEndpoint)
        require(sealedMxH1Server == null) { "Six-frame listener already owns this original QR." }
        mxh1Endpoint?.let { return@withLock it }
        val generation = publicQrEpoch.get()
        val lifecycle = lifecycleEpoch.get()
        val selected = requireNotNull(observedNetwork) { "No selected Wi-Fi/Ethernet Network." }
        val active = requireNotNull(networkManager.activeNetwork)
        require(active == selected) { "Default network changed." }
        val caps = requireNotNull(networkManager.getNetworkCapabilities(selected))
        require((caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))
        val host = requireNotNull(selectPrivateAddress(
            requireNotNull(networkManager.getLinkProperties(selected)),
        ))
        require(host == observedAddress && host == nativeEndpoint.privateIpv4) {
            "The original private IPv4 route changed."
        }
        val listener = Lc04MxH1PrivateLanTv.startOnApprovedPrivateIpv4(
            host, receiver, SystemClock::elapsedRealtime,
        )
        try {
            synchronized(this@TvPairingController) {
                if (!isTvVisible() || generation != publicQrEpoch.get() ||
                    lifecycle != lifecycleEpoch.get() ||
                    publicQrSession !== receiver ||
                    !receiver.hasApprovedPhoneForSyntheticMxH1(SystemClock.elapsedRealtime()) ||
                    networkManager.activeNetwork != selected ||
                    observedAddress != host || nativeProbeServer == null
                ) throw CancellationException("MXH1 TV owner was revoked before publication.")
                val endpoint = NativeProbeEndpoint(host, listener.port)
                mxh1Server = listener
                mxh1Endpoint = endpoint
                endpoint
            }
        } catch (failure: Throwable) {
            revokePublicKeyQrPreview()
            listener.close()
            throw failure
        }
    }

    /**
     * S2J2C2C: separately opted-in six-frame MXH1/MXR1 synthetic path.
     * Reuses the SAME QR/HPKE private receiver, approved Ed25519 phone,
     * permission-gated selected Wi-Fi/Ethernet interface and onStop fencing
     * as original four-frame MXH1. The two modes can never coexist.
     *
     * No source, credential or persistent pairing authority is created.
     */
    suspend fun enableSealedMxH1ForApprovedVisibleQr(): NativeProbeEndpoint =
        lifecycleMutex.withLock {
            if (!isTvVisible()) throw CancellationException("TV screen is not visible.")
            val receiver = requireNotNull(publicQrSession) { "No original TV QR." }
            require(receiver.hasApprovedPhoneForSyntheticMxH1(SystemClock.elapsedRealtime())) {
                "Original TV-local Ed25519 consent has not been verified."
            }
            requireNotNull(nativeProbeServer) { "Original authenticated enrollment not active." }
            val originalEndpoint = requireNotNull(nativeProbeEndpoint)
            require(mxh1Server == null) { "Four-frame MXH1 already owns this QR." }
            mxh1Endpoint?.let {
                require(sealedMxH1Server != null) { "MXH1 listener owner mismatch." }
                return@withLock it
            }
            val generation = publicQrEpoch.get()
            val lifecycle = lifecycleEpoch.get()
            val selected = requireNotNull(observedNetwork) { "No selected LAN Network." }
            require(networkManager.activeNetwork == selected) { "Default route changed." }
            val caps = requireNotNull(networkManager.getNetworkCapabilities(selected))
            require((caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                "Only selected Wi-Fi/Ethernet without VPN/cellular is eligible."
            }
            val host = requireNotNull(selectPrivateAddress(
                requireNotNull(networkManager.getLinkProperties(selected)),
            ))
            require(Lc04PrivateIpv4.parse(host) != null &&
                host == observedAddress && host == originalEndpoint.privateIpv4
            ) { "Original selected private IPv4 address changed." }

            val listener = Lc04MxH1SealedPrivateLanTv.startOnApprovedPrivateIpv4(
                host, receiver, SystemClock::elapsedRealtime,
            )
            try {
                synchronized(this@TvPairingController) {
                    if (!isTvVisible() || generation != publicQrEpoch.get() ||
                        lifecycle != lifecycleEpoch.get() ||
                        publicQrSession !== receiver ||
                        !receiver.hasApprovedPhoneForSyntheticMxH1(
                            SystemClock.elapsedRealtime(),
                        ) ||
                        networkManager.activeNetwork != selected ||
                        observedAddress != host ||
                        nativeProbeServer == null || mxh1Server != null
                    ) throw CancellationException("Six-frame listener owner revoked.")
                    val endpoint = NativeProbeEndpoint(host, listener.port)
                    sealedMxH1Server = listener
                    mxh1Endpoint = endpoint
                    endpoint
                }
            } catch (failure: Throwable) {
                revokePublicKeyQrPreview()
                listener.close()
                throw failure
            }
        }

    /** Safe to call on the UI thread and from Activity.onStop. */
    @Synchronized
    fun revokePublicKeyQrPreview() {
        publicQrEpoch.incrementAndGet()
        nativeProbeEndpoint = null
        mxh1Endpoint = null
        val mxh1Listener = mxh1Server
        mxh1Server = null
        mxh1Listener?.close() // same original QR owner and cancellation policy
        val sealedListener = sealedMxH1Server
        sealedMxH1Server = null
        sealedListener?.close() // six-frame socket and original keys die on onStop
        val nativeListener = nativeProbeServer
        nativeProbeServer = null
        if (nativeListener != null) {
            nativeDefaultNetworkCallback?.let {
                runCatching { networkManager.unregisterNetworkCallback(it) }
            }
            nativeDefaultNetworkCallback = null
            networkCallback?.let { runCatching { networkManager.unregisterNetworkCallback(it) } }
            networkCallback = null
            observedNetwork = null
            observedAddress = null
            nativeListener.close() // also revokes approved phone and QR receiver
        }
        publicQrSession?.close()
        publicQrSession = null
    }

    fun isPublicKeyQrPreviewActive(): Boolean =
        publicQrSession?.presentation(SystemClock.elapsedRealtime()) != null

    /**
     * Read-only user-interface qualification after REAL native Ed25519 proof.
     * A D-pad approval flag alone is not proof: the enrollment gate must have
     * verified and redeemed the exact phone identity for the ORIGINAL TV QR.
     */
    fun isApprovedMxH1PhoneReady(): Boolean =
        isTvVisible() && nativeProbeServer != null &&
            publicQrSession?.hasApprovedPhoneForSyntheticMxH1(
                SystemClock.elapsedRealtime(),
            ) == true

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
    fun isActive(): Boolean =
        nativeProbeServer == null && observedNetwork != null && observedAddress != null

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

    /**
     * A VPN can become the default without dropping Wi-Fi/Ethernet. The regular
     * interface callback alone cannot revoke that transition. Native mode
     * additionally observes default-network replacement and fails closed.
     */
    private fun watchDefaultNetworkForNative(
        selected: Network,
        lifecycle: Long,
        qrGeneration: Long,
    ) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != selected) invalidateSelectedNativeNetwork()
            }

            override fun onLost(network: Network) {
                if (network == selected) invalidateSelectedNativeNetwork()
            }

            private fun invalidateSelectedNativeNetwork() {
                mainHandler.post {
                    if (nativeProbeServer != null &&
                        observedNetwork == selected &&
                        lifecycleEpoch.get() == lifecycle &&
                        publicQrEpoch.get() == qrGeneration &&
                        networkManager.activeNetwork != selected
                    ) invalidated()
                }
            }
        }
        networkManager.registerDefaultNetworkCallback(callback)
        nativeDefaultNetworkCallback = callback
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
