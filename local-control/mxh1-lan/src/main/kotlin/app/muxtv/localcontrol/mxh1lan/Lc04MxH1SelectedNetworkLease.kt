package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * LC04-S2J2C1: the *pre-network* eligibility and ownership guard for a future
 * six-frame MXH1+MXR1 selected Wi-Fi/Ethernet transport.
 *
 * INTERNAL, intentionally NOT attached to either Android app. This class
 * cannot authenticate a QR, grant pairing, send payloads or enable source
 * operations. The Android caller must separately establish and continuously
 * verify the SAME selected Network, default route, private IPv4 interface,
 * Wi-Fi/Ethernet capability, VPN exclusion and (targetSdk >= 37) LAN permission.
 *
 * A binder callback must call selectedNetwork.bindSocket(unconnected) rather
 * than binding the process/default network. The callback itself is not a
 * security proof; a product adapter requires independently tested Android
 * Network invariants before adoption.
 */
internal class Lc04MxH1SelectedNetworkLease private constructor(
    private val endpoint: InetSocketAddress,
    private val elapsedMillis: () -> Long,
    private val selectedRouteEligible: () -> Boolean,
    private val bindToSelectedNetwork: (Socket) -> Unit,
    private val revokeOriginalQrAndKeys: () -> Unit,
) : AutoCloseable {
    private enum class Phase { NEW, BINDING, BOUND, CLOSED }
    private val mutex = Any()
    private val started = elapsedMillis()
    private var lastObserved = started
    @Volatile private var phase = Phase.NEW
    @Volatile private var activeSocket: Socket? = null

    init {
        require(started >= 0L && started <= Long.MAX_VALUE - TTL_MILLIS)
    }

    /**
     * May be attempted ONCE. A pre-bound or already-connected raw socket
     * cannot be rebound to a selected Android Network and is terminal.
     * No external callback executes while the lifecycle mutex is held.
     */
    fun bindOnce(unconnected: Socket): Boolean {
        val acquired = synchronized(mutex) {
            if (phase != Phase.NEW || unconnected.isBound ||
                unconnected.isConnected || unconnected.isClosed
            ) false else {
                phase = Phase.BINDING
                activeSocket = unconnected
                true
            }
        }
        if (!acquired) {
            // A rejected caller socket is not the tracked active socket.
            // Close it too: a pre-bound or second untracked socket must
            // never survive rejection as an alternate network path.
            runCatching { unconnected.close() }
            close()
            return false
        }
        return try {
            if (!check(Phase.BINDING)) return false
            bindToSelectedNetwork(unconnected)
            if (!check(Phase.BINDING)) return false
            val claimed = synchronized(mutex) {
                if (phase == Phase.BINDING && activeSocket === unconnected &&
                    !unconnected.isClosed && !unconnected.isConnected
                ) {
                    phase = Phase.BOUND
                    true
                } else false
            }
            if (!claimed) {
                close()
                return false
            }
            check(Phase.BOUND)
        } catch (_: IOException) {
            close()
            false
        } catch (_: RuntimeException) {
            close()
            false
        }
    }

    /**
     * Must be called before connect, every protocol frame, and after an
     * unblocking network operation. Never continue on a failed checkpoint.
     * The socket is synchronously closed on route/permission loss.
     */
    fun checkpoint(): Boolean = check(Phase.BOUND)

    /** A caller may connect ONLY to this already-vetted numeric endpoint. */
    fun endpointIfBoundAndEligible(): InetSocketAddress? =
        if (checkpoint()) endpoint else null

    /** Invoke synchronously from onStop, onLost, default route/IP/permission changes. */
    fun revokeSelectedNetwork() = close()

    private fun check(required: Phase): Boolean {
        val timeOk = try {
            synchronized(mutex) {
                val now = elapsedMillis()
                if (phase != required || now < lastObserved ||
                    now < started || now - started >= TTL_MILLIS
                ) false else {
                    lastObserved = now
                    true
                }
            }
        } catch (_: RuntimeException) { false }
        val networkOk = timeOk && try {
            selectedRouteEligible()
        } catch (_: RuntimeException) { false }
        if (!networkOk || phase != required || activeSocket?.isClosed != false) {
            close()
            return false
        }
        return true
    }

    /** Idempotent; closes a blocked socket and consumes original QR/key owner. */
    override fun close() {
        val toClose: Socket?
        synchronized(mutex) {
            if (phase == Phase.CLOSED) return
            phase = Phase.CLOSED
            toClose = activeSocket
            activeSocket = null
        }
        runCatching { toClose?.close() }
        runCatching { revokeOriginalQrAndKeys() }
    }

    override fun toString(): String = "Lc04MxH1SelectedNetworkLease([REDACTED])"

    companion object {
        private const val TTL_MILLIS = 120_000L

        /** No DNS, hostname, loopback, wildcard, multicast or public/VPN IPs. */
        fun forSelectedPrivateIpv4(
            numericIp: String,
            port: Int,
            elapsedMillis: () -> Long,
            selectedRouteEligible: () -> Boolean,
            bindToSelectedNetwork: (Socket) -> Unit,
            revokeOriginalQrAndKeys: () -> Unit,
        ): Lc04MxH1SelectedNetworkLease {
            require(port in 1..65535)
            val ip = requireNotNull(Lc04PrivateIpv4.parse(numericIp)) {
                "Numeric RFC1918 private IPv4 required."
            }
            return Lc04MxH1SelectedNetworkLease(
                InetSocketAddress(ip, port), elapsedMillis, selectedRouteEligible,
                bindToSelectedNetwork, revokeOriginalQrAndKeys,
            )
        }
    }
}
