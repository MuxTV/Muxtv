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
        MXH1_VERIFYING, MXH1_VERIFIED, FAILED, REVOKED,
    }

    private enum class Phase {
        NEW, OFFERING, WAITING, VERIFYING, VERIFIED,
        MXH1_RUNNING, MXH1_VERIFIED, CLOSED,
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
        selectedNetwork = null
        selectedAddresses = null
        flow?.close()
        flow = null
        signingPhone.close()
        worker.shutdownNow()
    }
}
