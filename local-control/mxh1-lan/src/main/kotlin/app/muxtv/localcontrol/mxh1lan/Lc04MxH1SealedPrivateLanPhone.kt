package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import java.net.Socket

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
    /** Diagnostic only. Returns false on any failed/revoked network checkpoint. */
    fun completeSyntheticExchange(): Boolean = transport.completeSyntheticExchange()

    override fun close() = transport.close()

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
