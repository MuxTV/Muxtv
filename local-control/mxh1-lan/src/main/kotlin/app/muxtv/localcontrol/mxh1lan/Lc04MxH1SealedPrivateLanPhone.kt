package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Explicit, one-use Android-facing adapter for the fixed six-frame synthetic
 * MXH1/MXR1 exchange. This facade exposes neither the lease, traffic keys,
 * arbitrary application payloads, source commands nor a pairing grant.
 *
 * Callers MUST supply the same validated selected Network throughout the
 * attempt and bind the UNCONNECTED socket using Network.bindSocket(socket).
 * Merely supplying these callbacks is not an Android/device attestation.
 */
class Lc04MxH1SealedPrivateLanPhone private constructor(
    private val transport: Lc04MxH1LeasedSealedPhone,
) : AutoCloseable {
    private val handoffStarted = AtomicBoolean(false)
    private val handoffLock = Any()
    @Volatile private var closed = false
    private var preparedSourcePort: Lc04SourceAeadPort? = null

    /**
     * A TV APPDATA port only exists AFTER real F6 and kernel EOF. Hold the
     * original sealed key owner privately until the user enters that port.
     * Never restart the handshake or reset directional AES-GCM counters.
     * Blocking handshake I/O is NOT protected by the lifecycle lock.
     */
    fun prepareSourceKeyHandoffForLaterAppdata(): Boolean {
        if (closed || !handoffStarted.compareAndSet(false, true)) return false
        val port = transport.completeSourceKeyHandoffAfterTvEof() ?: return false
        return synchronized(handoffLock) {
            if (closed) {
                port.close()
                false
            } else {
                preparedSourcePort = port
                true
            }
        }
    }

    /** Diagnostic only. Returns false on any failed/revoked network checkpoint. */
    fun completeSyntheticExchange(): Boolean = transport.completeSyntheticExchange()

    /**
     * Explicit, one-use source APPDATA key-owner handoff after actual F6/EOF.
     * This does NOT establish an app-data TCP connection, source principal,
     * D-pad activation approval, or provider credential transport.
     */
    fun completeSourceKeyHandoffAfterTvEof(): Lc04SourceAeadPort? {
        if (closed) return null
        if (handoffStarted.compareAndSet(false, true)) {
            // Preserve the existing immediate-consumer API and its test fixtures.
            return transport.completeSourceKeyHandoffAfterTvEof()
        }
        return synchronized(handoffLock) {
            if (closed) null else preparedSourcePort.also { preparedSourcePort = null }
        }
    }

    override fun close() {
        synchronized(handoffLock) {
            closed = true
            preparedSourcePort?.close()
            preparedSourcePort = null
        }
        transport.close() // interrupt pending socket reads on Activity.onStop
    }

    override fun toString(): String =
        "Lc04MxH1SealedPrivateLanPhone([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        /**
         * Numeric RFC1918 endpoint only, bound exactly once to a selected
         * Android Network. No HTTP, fallback route, DNS or credential transfer.
         */
        fun forSelectedPrivateIpv4(
            privateIpv4: String,
            port: Int,
            originalPhone: Lc04MxH1PhoneLab,
            elapsedMillis: () -> Long,
            selectedRouteEligible: () -> Boolean,
            bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
            revokeOriginalQrAndKeys: () -> Unit,
        ): Lc04MxH1SealedPrivateLanPhone =
            Lc04MxH1SealedPrivateLanPhone(
                Lc04MxH1LeasedSealedPhone.forSelectedPrivateIpv4(
                    privateIpv4 = privateIpv4,
                    port = port,
                    originalPhone = originalPhone,
                    elapsedMillis = elapsedMillis,
                    selectedRouteEligible = selectedRouteEligible,
                    bindUnconnectedToSelectedNetwork = bindUnconnectedToSelectedNetwork,
                    revokeOriginalQrAndKeys = revokeOriginalQrAndKeys,
                ),
            )
    }
}
