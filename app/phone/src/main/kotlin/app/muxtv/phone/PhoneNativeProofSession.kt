package app.muxtv.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.nativetransport.Lc04TcpPhoneExchange
import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1PrivateLanPhone
import app.muxtv.localcontrol.mxh1lan.Lc04MxH1SealedPrivateLanPhone
import app.muxtv.localcontrol.mxh1lan.Lc04SourceCommandTcpPhone
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import app.muxtv.localcontrol.phoneflow.Lc04PhoneFrameExchange
import app.muxtv.localcontrol.phoneflow.Lc04PhoneProofFlow
import app.muxtv.localcontrol.qr.TvQrPublicKey
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * One Android-visible, opt-in attempt. The QR-pinned TV key is the trust root;
 * numeric RFC1918 address/port and the Wi-Fi Network are ONLY untrusted locators.
 *
 * No HTTP, source commands, persisted pairing grant, credentials or UI state
 * survive this object. Socket cancellation is synchronous on close/onStop.
 */
internal class PhoneNativeProofSession(
    context: Context,
    private val scannedTv: TvQrPublicKey,
    private val signingPhone: PhoneSigningIdentity,
    private val postToUi: (() -> Unit) -> Unit,
    private val onState: (State) -> Unit,
) : AutoCloseable {
    enum class State {
        OFFERING, WAITING_FOR_TV, VERIFYING, VERIFIED,
        MXH1_VERIFYING, MXH1_VERIFIED, SOURCE_HANDSHAKE_RUNNING,
        SOURCE_READY, SOURCE_PREPARING, SOURCE_PREPARED,
        SOURCE_REQUESTING_APPROVAL, SOURCE_WAIT_TV_APPROVAL,
        SOURCE_CANCELING, SOURCE_CANCELED, SOURCE_CANCEL_CLEANUP_PENDING,
        SOURCE_CANCEL_RESULT_UNKNOWN,
        SOURCE_ACTIVATED, SOURCE_OUTCOME_UNKNOWN, FAILED, REVOKED,
    }

    private enum class Phase {
        NEW, OFFERING, WAITING, VERIFYING, VERIFIED,
        MXH1_RUNNING, MXH1_VERIFIED, SOURCE_HANDSHAKE, SOURCE_READY,
        SOURCE_PREPARING, SOURCE_PREPARED, SOURCE_REQUESTING_APPROVAL,
        SOURCE_WAIT_TV, SOURCE_CONFIRMING, SOURCE_CANCELING,
        SOURCE_DONE, CLOSED,
    }

    private val manager: ConnectivityManager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val appContext = context.applicationContext
    private val phase = AtomicReference(Phase.NEW)
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var flow: Lc04PhoneProofFlow? = null
    @Volatile private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var selectedNetwork: Network? = null
    @Volatile private var selectedAddresses: Set<String>? = null
    @Volatile private var mxh1Client: Lc04MxH1PrivateLanPhone? = null
    @Volatile private var sealedMxH1Client: Lc04MxH1SealedPrivateLanPhone? = null
    @Volatile private var mxh1Phone: Lc04MxH1PhoneLab? = null
    @Volatile private var sourceAppdata: Lc04SourceCommandTcpPhone? = null
    @Volatile private var sourceTvIpv4: String? = null
    @Volatile private var sourceNameForActivation: String? = null

    /**
     * May only be called once after a real user gesture AND the Android 17
     * local-network runtime permission gate. Never request permission here.
     */
    fun begin(endpoint: PhoneNativeEndpoint): Boolean {
        if (!phase.compareAndSet(Phase.NEW, Phase.OFFERING)) return false
        val selected = manager.activeNetwork ?: return failedToStart()
        val initial = manager.getLinkProperties(selected) ?: return failedToStart()
        val addresses = privateAddresses(initial)
        if (addresses.isEmpty() || !eligible(selected, addresses)) return failedToStart()
        if (Build.VERSION.SDK_INT >= 37 &&
            appContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
        ) return failedToStart()

        val tcp = try {
            Lc04TcpPhoneExchange.forPrivateIpv4OnNetwork(
                endpoint.privateIpv4, endpoint.port,
            ) { socket ->
                // Bind the UNCONNECTED socket to the exact initial Wi-Fi/Ethernet
                // Network. Never let VPN/default route transparently reroute it.
                if (!eligible(selected, addresses)) throw IOException("LAN network revoked")
                selected.bindSocket(socket)
            }
        } catch (_: IllegalArgumentException) {
            return failedToStart()
        }

        val transport = object : Lc04PhoneFrameExchange {
            override fun exchange(frame: ByteArray): ByteArray? {
                if (phase.get() == Phase.CLOSED || !eligible(selected, addresses)) return null
                return tcp.exchange(frame)
            }
            override fun close() = tcp.close()
        }
        val created = try {
            Lc04PhoneProofFlow(scannedTv, signingPhone, transport, SystemClock::elapsedRealtime)
        } catch (_: Exception) {
            tcp.close()
            return failedToStart()
        }
        flow = created
        selectedNetwork = selected
        selectedAddresses = addresses
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (network != selected) revokeNetwork()
            }
            override fun onLost(network: Network) {
                if (network == selected) revokeNetwork()
            }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network == selected && !eligible(selected, addresses)) revokeNetwork()
            }
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                if (network == selected && privateAddresses(properties) != addresses) revokeNetwork()
            }
        }
        networkCallback = callback
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            return failedToStart()
        }
        if (!eligible(selected, addresses) || phase.get() != Phase.OFFERING) {
            return failedToStart()
        }
        publish(State.OFFERING)
        try {
            worker.execute {
                val review = created.offer()
                if (review != null && phase.compareAndSet(Phase.OFFERING, Phase.WAITING)) {
                    // TV_CHALLENGE is UNAUTHENTICATED. Only a final authenticated
                    // receipt can qualify the endpoint as the scanned TV.
                    publish(State.WAITING_FOR_TV)
                } else if (phase.get() != Phase.CLOSED) {
                    fail()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return failedToStart()
        }
        return true
    }

    fun finishAfterTvButton(): Boolean {
        if (!phase.compareAndSet(Phase.WAITING, Phase.VERIFYING)) return false
        val attempt = flow ?: return fail()
        publish(State.VERIFYING)
        try {
            worker.execute {
                if (attempt.finishAfterTvAction() &&
                    phase.compareAndSet(Phase.VERIFYING, Phase.VERIFIED)
                ) publish(State.VERIFIED)
                else if (phase.get() != Phase.CLOSED) fail()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return fail()
        }
        return true
    }

    /**
     * A SECOND explicit phone-button action after the ordinary native proof
     * is VERIFIED. Reuses the same QR and Ephemeral Ed25519 signing identity;
     * the selected original Android Network binds an UNCONNECTED raw Socket.
     *
     * This reports only synthetic mutual key confirmation, never pairing
     * credentials, a reusable channel, or authority to prepare IPTV sources.
     */
    fun beginMxH1(endpoint: PhoneNativeEndpoint): Boolean {
        if (!phase.compareAndSet(Phase.VERIFIED, Phase.MXH1_RUNNING)) return false
        if (flow?.hasAuthenticatedReceipt() != true) return failedToStart()
        val selected = selectedNetwork ?: return failedToStart()
        val addresses = selectedAddresses ?: return failedToStart()
        if (!eligible(selected, addresses)) return failedToStart()
        val socketClient = try {
            Lc04MxH1PrivateLanPhone.forPrivateIpv4OnNetwork(
                endpoint.privateIpv4, endpoint.port,
            ) { unconnected ->
                if (phase.get() != Phase.MXH1_RUNNING ||
                    !eligible(selected, addresses)
                ) throw IOException("Selected Android LAN route was revoked.")
                selected.bindSocket(unconnected)
            }
        } catch (_: IllegalArgumentException) {
            return failedToStart()
        }
        val phone = try {
            Lc04MxH1PhoneLab(scannedTv, signingPhone, SystemClock::elapsedRealtime)
        } catch (_: RuntimeException) {
            socketClient.close()
            return failedToStart()
        }
        mxh1Phone = phone
        mxh1Client = socketClient
        publish(State.MXH1_VERIFYING)
        try {
            worker.execute {
                if (socketClient.complete(phone) &&
                    phase.compareAndSet(Phase.MXH1_RUNNING, Phase.MXH1_VERIFIED)
                ) publish(State.MXH1_VERIFIED)
                else if (phase.get() != Phase.CLOSED) fail()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return failedToStart()
        }
        return true
    }

    /**
     * S2J2C2C: the phone's SECOND user opt-in uses the already authenticated
     * original MXW1 QR/Ed25519 identity, but sends six actual MXH1/MXR1
     * frames to the TV listener. This is a synthetic transport proof ONLY.
     *
     * Real Android Network.bindSocket is invoked on the unconnected raw
     * socket and the chosen route is checked before/after binding and
     * every frame. No network fallback or source/credential transfer.
     */
    fun beginSealedMxH1(endpoint: PhoneNativeEndpoint): Boolean {
        if (!phase.compareAndSet(Phase.VERIFIED, Phase.MXH1_RUNNING)) return false
        if (flow?.hasAuthenticatedReceipt() != true) return failedToStart()
        val selected = selectedNetwork ?: return failedToStart()
        val addresses = selectedAddresses ?: return failedToStart()
        if (!eligible(selected, addresses)) return failedToStart()
        val phone = try {
            Lc04MxH1PhoneLab(scannedTv, signingPhone, SystemClock::elapsedRealtime)
        } catch (_: RuntimeException) {
            return failedToStart()
        }
        val client = try {
            Lc04MxH1SealedPrivateLanPhone.forSelectedPrivateIpv4(
                privateIpv4 = endpoint.privateIpv4,
                port = endpoint.port,
                originalPhone = phone,
                elapsedMillis = SystemClock::elapsedRealtime,
                selectedRouteEligible = {
                    phase.get() == Phase.MXH1_RUNNING && eligible(selected, addresses)
                },
                bindUnconnectedToSelectedNetwork = { unconnected ->
                    if (phase.get() != Phase.MXH1_RUNNING ||
                        !eligible(selected, addresses)
                    ) throw IOException("Selected Wi-Fi/Ethernet LAN Network revoked.")
                    selected.bindSocket(unconnected)
                    if (!eligible(selected, addresses)) {
                        throw IOException("Selected route changed after binding.")
                    }
                },
                revokeOriginalQrAndKeys = {
                    // The original QR-signing identity is terminal after
                    // the six-frame attempt, whether authenticated or not.
                    signingPhone.close()
                },
            )
        } catch (_: IllegalArgumentException) {
            phone.close()
            return failedToStart()
        } catch (_: RuntimeException) {
            phone.close()
            return failedToStart()
        }
        mxh1Phone = phone
        sealedMxH1Client = client
        publish(State.MXH1_VERIFYING)
        try {
            worker.execute {
                if (client.completeSyntheticExchange() &&
                    phase.compareAndSet(Phase.MXH1_RUNNING, Phase.MXH1_VERIFIED)
                ) publish(State.MXH1_VERIFIED)
                else if (phase.get() != Phase.CLOSED) fail()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return failedToStart()
        }
        return true
    }

    /**
     * Separate PRODUCT mode after verified original MXW1/TV Ed25519 consent.
     * Authenticates real F6 + kernel EOF on original selected-Network TCP,
     * then retains one-use source records without learning the APPDATA port.
     * No credentials or source mutation in this phase.
     */
    fun beginSourceMxH1(endpoint: PhoneNativeEndpoint): Boolean {
        if (!phase.compareAndSet(Phase.VERIFIED, Phase.SOURCE_HANDSHAKE)) return false
        if (flow?.hasAuthenticatedReceipt() != true) return failedToStart()
        val selected = selectedNetwork ?: return failedToStart()
        val addresses = selectedAddresses ?: return failedToStart()
        if (!eligible(selected, addresses)) return failedToStart()
        val phone = try {
            Lc04MxH1PhoneLab(scannedTv, signingPhone, SystemClock::elapsedRealtime)
        } catch (_: RuntimeException) { return failedToStart() }
        val client = try {
            Lc04MxH1SealedPrivateLanPhone.forSelectedPrivateIpv4(
                privateIpv4 = endpoint.privateIpv4,
                port = endpoint.port,
                originalPhone = phone,
                elapsedMillis = SystemClock::elapsedRealtime,
                selectedRouteEligible = {
                    sourcePhaseActive() && eligible(selected, addresses)
                },
                bindUnconnectedToSelectedNetwork = { unconnected ->
                    if (!sourcePhaseActive() || !eligible(selected, addresses)) {
                        throw IOException("Source LAN route revoked")
                    }
                    selected.bindSocket(unconnected)
                    if (!eligible(selected, addresses)) {
                        throw IOException("Source LAN route changed after bind")
                    }
                },
                revokeOriginalQrAndKeys = { signingPhone.close() },
            )
        } catch (_: IllegalArgumentException) {
            phone.close()
            return failedToStart()
        } catch (_: RuntimeException) {
            phone.close()
            return failedToStart()
        }
        // The network callback can revoke onStop WHILE construction runs.
        // Publish the client under the same monitor as @Synchronized close():
        // never create an unowned socket after the Activity has stopped.
        val owned = synchronized(this) {
            if (phase.get() != Phase.SOURCE_HANDSHAKE) false else {
                mxh1Phone = phone
                sealedMxH1Client = client
                sourceTvIpv4 = endpoint.privateIpv4
                true
            }
        }
        if (!owned) {
            client.close()
            phone.close()
            return false
        }
        publish(State.SOURCE_HANDSHAKE_RUNNING)
        try {
            worker.execute {
                if (client.prepareSourceKeyHandoffForLaterAppdata() &&
                    phase.compareAndSet(Phase.SOURCE_HANDSHAKE, Phase.SOURCE_READY) &&
                    eligible(selected, addresses)
                ) publish(State.SOURCE_READY)
                else if (phase.get() != Phase.CLOSED) fail()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return failedToStart()
        }
        return true
    }

    private fun sourcePhaseActive(): Boolean = when (phase.get()) {
        Phase.SOURCE_HANDSHAKE, Phase.SOURCE_READY, Phase.SOURCE_PREPARING,
        Phase.SOURCE_PREPARED, Phase.SOURCE_REQUESTING_APPROVAL,
        Phase.SOURCE_WAIT_TV, Phase.SOURCE_CONFIRMING,
        Phase.SOURCE_CANCELING -> true
        else -> false
    }

    /**
     * User enters TV's second port AFTER F6/EOF. Only the original exact TV
     * private IPv4 is eligible; no hostname/VPN fallback. HTTPS only until
     * a separately reviewed TV-local insecure HTTP approval exists.
     */
    fun prepareSourceM3u(
        endpoint: PhoneNativeEndpoint, locator: String, sourceName: String,
    ): Boolean {
        if (endpoint.privateIpv4 != sourceTvIpv4 ||
            !locator.startsWith("https://", ignoreCase = true) ||
            locator.toByteArray(Charsets.UTF_8).size !in 9..4096 ||
            sourceName.isBlank() || sourceName != sourceName.trim() ||
            sourceName.any { it.isISOControl() } ||
            sourceName.toByteArray(Charsets.UTF_8).size !in 1..120
        ) return false
        if (!phase.compareAndSet(Phase.SOURCE_READY, Phase.SOURCE_PREPARING)) return false
        val selected = selectedNetwork ?: return failedToStart()
        val addresses = selectedAddresses ?: return failedToStart()
        val handshake = sealedMxH1Client ?: return failedToStart()
        sourceNameForActivation = sourceName
        publish(State.SOURCE_PREPARING)
        try {
            worker.execute {
                if (!sourcePhaseActive() || !eligible(selected, addresses)) {
                    fail()
                    return@execute
                }
                val app = try {
                    Lc04SourceCommandTcpPhone.afterVerifiedTvEofOnSelectedNetwork(
                        address = endpoint.privateIpv4,
                        appdataPort = endpoint.port,
                        completedHandshake = handshake,
                        selectedRouteEligible = {
                            sourcePhaseActive() && eligible(selected, addresses)
                        },
                        bindUnconnectedToSelectedNetwork = { unconnected ->
                            if (!sourcePhaseActive() || !eligible(selected, addresses)) {
                                throw IOException("Selected APPDATA Network revoked")
                            }
                            selected.bindSocket(unconnected)
                            if (!eligible(selected, addresses)) {
                                throw IOException("Selected APPDATA Network changed")
                            }
                        },
                    )
                } catch (_: IllegalArgumentException) { null }
                  catch (_: RuntimeException) { null }
                if (app == null) {
                    fail()
                    return@execute
                }
                // close() is synchronized on the same owner, so a completed
                // onStop can never be followed by a newly retained socket.
                val owned = synchronized(this) {
                    if (phase.get() != Phase.SOURCE_PREPARING) false
                    else {
                        sourceAppdata = app
                        true
                    }
                }
                if (!owned) {
                    app.close()
                    return@execute
                }
                if (app.prepareM3u(locator) == Lc04SourceReplyCode.PREPARED &&
                    phase.compareAndSet(Phase.SOURCE_PREPARING, Phase.SOURCE_PREPARED)
                ) publish(State.SOURCE_PREPARED)
                else if (phase.get() != Phase.CLOSED) fail()
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return failedToStart()
        }
        return true
    }

    /** One initial ACTIVATE request; null or unknown reply is TERMINAL. */
    fun requestSourceActivation(): Boolean {
        if (!phase.compareAndSet(Phase.SOURCE_PREPARED, Phase.SOURCE_REQUESTING_APPROVAL)) {
            return false
        }
        val sourceName = sourceNameForActivation ?: return activationOutcomeUnknown()
        val app = sourceAppdata ?: return activationOutcomeUnknown()
        publish(State.SOURCE_REQUESTING_APPROVAL)
        try {
            worker.execute {
                when (app.requestActivate(sourceName)) {
                    Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED -> {
                        if (phase.compareAndSet(Phase.SOURCE_REQUESTING_APPROVAL, Phase.SOURCE_WAIT_TV)) {
                            publish(State.SOURCE_WAIT_TV_APPROVAL)
                        }
                    }
                    // A first-step ACTIVATED answer is not an allowed outcome:
                    // phone requires a TV-confirmation-required challenge, then
                    // a separate second gesture after physical TV D-pad consent.
                    else -> activationOutcomeUnknown()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return activationOutcomeUnknown()
        }
        return true
    }

    /** TV D-pad first; second PHONE button sends the exact next sequence. */
    fun completeSourceActivationAfterTvGesture(): Boolean {
        if (!phase.compareAndSet(Phase.SOURCE_WAIT_TV, Phase.SOURCE_CONFIRMING)) return false
        val sourceName = sourceNameForActivation ?: return activationOutcomeUnknown()
        val app = sourceAppdata ?: return activationOutcomeUnknown()
        publish(State.SOURCE_REQUESTING_APPROVAL)
        try {
            worker.execute {
                if (app.requestActivate(sourceName) == Lc04SourceReplyCode.ACTIVATED) {
                    completeSourceActivation()
                } else {
                    activationOutcomeUnknown()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return activationOutcomeUnknown()
        }
        return true
    }


    /**
     * Explicit phone-side abort is permitted only BEFORE a durable ACTIVATE.
     * The TV owns the opaque prepared credential and performs actual cleanup;
     * a lost/ambiguous CANCEL reply is terminal and must never be replayed.
     * In the TV-confirmation-required state this also invalidates the pending
     * D-pad consent for the exact sequence, without sending another ACTIVATE.
     */
    fun cancelPreparedSource(): Boolean {
        val previous = phase.get()
        if (previous != Phase.SOURCE_PREPARED && previous != Phase.SOURCE_WAIT_TV) {
            return false
        }
        if (!phase.compareAndSet(previous, Phase.SOURCE_CANCELING)) return false
        val app = sourceAppdata ?: return cancelSourceResultUnknown()
        publish(State.SOURCE_CANCELING)
        try {
            worker.execute {
                when (app.cancel()) {
                    Lc04SourceReplyCode.CANCELED -> {
                        if (!phase.compareAndSet(Phase.SOURCE_CANCELING, Phase.SOURCE_DONE)) {
                            return@execute // Activity.onStop revoked the owner during I/O.
                        }
                        sourceAppdata?.close()
                        sourceAppdata = null
                        sealedMxH1Client?.close()
                        sealedMxH1Client = null
                        sourceNameForActivation = null
                        publish(State.SOURCE_CANCELED)
                    }
                    Lc04SourceReplyCode.CLEANUP_PENDING -> {
                        cancelSourceCleanupPending()
                    }
                    else -> cancelSourceResultUnknown()
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return cancelSourceResultUnknown()
        }
        return true
    }

    private fun cancelSourceCleanupPending(): Boolean {
        if (phase.get() != Phase.SOURCE_CANCELING) return false
        close()
        postToUi { onState(State.SOURCE_CANCEL_CLEANUP_PENDING) }
        return false
    }

    private fun cancelSourceResultUnknown(): Boolean {
        if (phase.get() != Phase.SOURCE_CANCELING) return false
        close()
        postToUi { onState(State.SOURCE_CANCEL_RESULT_UNKNOWN) }
        return false
    }

    private fun completeSourceActivation() {
        if (!phase.compareAndSet(Phase.SOURCE_REQUESTING_APPROVAL, Phase.SOURCE_DONE) &&
            !phase.compareAndSet(Phase.SOURCE_CONFIRMING, Phase.SOURCE_DONE)
        ) return // Activity stopped or route revoked: never claim success.
        sourceAppdata?.close()
        sourceAppdata = null
        sealedMxH1Client?.close()
        sealedMxH1Client = null
        sourceNameForActivation = null
        publish(State.SOURCE_ACTIVATED)
    }

    private fun activationOutcomeUnknown(): Boolean {
        close()
        postToUi { onState(State.SOURCE_OUTCOME_UNKNOWN) }
        return false
    }

    private fun eligible(selected: Network, addresses: Set<String>): Boolean {
        if (phase.get() == Phase.CLOSED || manager.activeNetwork != selected) return false
        val caps = manager.getNetworkCapabilities(selected) ?: return false
        if ((!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        ) return false
        if (Build.VERSION.SDK_INT >= 37 &&
            appContext.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) !=
                PackageManager.PERMISSION_GRANTED
        ) return false
        return manager.getLinkProperties(selected)?.let(::privateAddresses) == addresses
    }

    private fun privateAddresses(properties: LinkProperties): Set<String> =
        properties.linkAddresses.mapNotNull { it.address.hostAddress }
            .filter { app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4.parse(it) != null }
            .toSet()

    private fun publish(state: State) = postToUi {
        if (phase.get() != Phase.CLOSED) onState(state)
    }

    private fun revokeNetwork() {
        if (phase.get() == Phase.CLOSED) return
        close()
        postToUi { onState(State.REVOKED) }
    }

    private fun fail(): Boolean {
        close()
        postToUi { onState(State.FAILED) }
        return false
    }

    private fun failedToStart(): Boolean = fail()

    @Synchronized
    override fun close() {
        if (phase.getAndSet(Phase.CLOSED) == Phase.CLOSED) return
        networkCallback?.let { callback ->
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        mxh1Client?.close()
        mxh1Client = null
        sealedMxH1Client?.close()
        sealedMxH1Client = null
        mxh1Phone?.close()
        mxh1Phone = null
        sourceAppdata?.close()
        sourceAppdata = null
        sourceTvIpv4 = null
        sourceNameForActivation = null
        selectedNetwork = null
        selectedAddresses = null
        flow?.close()
        flow = null
        signingPhone.close()
        worker.shutdownNow()
    }
}
