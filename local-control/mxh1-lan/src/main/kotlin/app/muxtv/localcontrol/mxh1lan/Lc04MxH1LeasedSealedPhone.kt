package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LC04-S2J2C2A: RESEARCH-ONLY selected-Network six-frame PHONE transport.
 *
 * Not imported by the Android application. Unlike the installed four-frame
 * phone path, it sends the exact fifth encrypted synthetic MXR1 probe BEFORE
 * half-closing output and requires an authenticated sixth record plus TV EOF.
 *
 * A caller supplies the selected Android Network's eligibility and
 * Network.bindSocket(unconnected) callbacks. This class does not attest
 * that those callbacks are genuine Android Network operations, or that
 * ACCESS_LOCAL_NETWORK has been granted on API37; the future Android adapter
 * must provide and test that evidence. No generic payload or source API.
 */
internal class Lc04MxH1LeasedSealedPhone private constructor(
    private val lease: Lc04MxH1SelectedNetworkLease,
    private val phone: Lc04MxH1PhoneLab,
    private val elapsedMillis: () -> Long,
    private val newUnconnectedSocket: () -> Socket,
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)

    /**
     * Exactly one attempt, using ONLY the lease's vetted numeric RFC1918
     * endpoint. A route/permission change or Activity stop closes the socket
     * synchronously via lease.revokeSelectedNetwork().
     *
     * Returns a diagnostic Boolean for a fixed synthetic probe only, never
     * an authorization token, paired capability or retained encryption key.
     */
    fun completeSyntheticExchange(): Boolean {
        if (!consumed.compareAndSet(false, true)) {
            close()
            return false
        }
        val socket = try {
            newUnconnectedSocket()
        } catch (_: RuntimeException) {
            close()
            return false
        }
        var authenticated = false
        try {
            socket.use { connected ->
                if (!lease.bindOnce(connected)) return false
                connected.soTimeout = IO_TIMEOUT_MS
                val destination = lease.endpointIfBoundAndEligible() ?: return false
                connected.connect(destination, CONNECT_TIMEOUT_MS)
                if (!lease.checkpoint()) return false

                val input = DataInputStream(connected.getInputStream())
                val output = DataOutputStream(connected.getOutputStream())

                if (!lease.checkpoint()) return false
                val offer = phone.createSignedSeedOffer() ?: return false
                try {
                    if (!lease.checkpoint()) return false
                    Lc04MxH1FrameIo.write(
                        output, offer, Lc04HandoffKind.SIGNED_SEED_OFFER,
                    )
                } finally { offer.fill(0) }
                if (!lease.checkpoint()) return false

                val tvConfirm = Lc04MxH1FrameIo.read(
                    input, Lc04HandoffKind.TV_KEY_CONFIRM,
                )
                val phoneConfirm = try {
                    if (!lease.checkpoint()) return false
                    phone.acceptTvConfirmationAwaitingFinished(tvConfirm)
                } finally { tvConfirm.fill(0) }
                if (phoneConfirm == null) return false
                try {
                    if (!lease.checkpoint()) return false
                    Lc04MxH1FrameIo.write(
                        output, phoneConfirm, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                    )
                } finally { phoneConfirm.fill(0) }
                if (!lease.checkpoint()) return false

                val finished = Lc04MxH1FrameIo.read(
                    input, Lc04HandoffKind.TV_FINISHED,
                )
                val verified = try {
                    lease.checkpoint() && phone.acceptTvFinishedForProbeLab(
                        finished, elapsedMillis,
                    )
                } finally { finished.fill(0) }
                if (!verified || !lease.checkpoint()) return false

                val fifth = phone.sealMxH1SyntheticProbeForLab() ?: return false
                try {
                    if (!lease.checkpoint()) return false
                    Lc04MxH1LeasedSealedFrameIo.write(output, fifth)
                    if (!lease.checkpoint()) return false
                    connected.shutdownOutput()
                } finally { fifth.fill(0) }
                if (!lease.checkpoint()) return false

                val sixth = Lc04MxH1LeasedSealedFrameIo.read(input)
                try {
                    // The sixth reply is not accepted on ciphertext alone:
                    // exact TV TCP EOF, live selected route and AEAD tag must
                    // all succeed before reporting even a diagnostic result.
                    authenticated = input.read() == -1 &&
                        lease.checkpoint() &&
                        phone.acceptMxH1SyntheticProbeForLab(sixth) &&
                        lease.checkpoint() &&
                        phone.hasAuthenticatedTvSyntheticProof()
                    return authenticated
                } finally { sixth.fill(0) }
            }
        } catch (_: IOException) {
            return false
        } catch (_: RuntimeException) {
            return false
        } finally {
            // Terminal even after authenticated probe: never retain a pairing
            // session, raw traffic key, socket or original QR/phone identity.
            close()
        }
    }

    override fun close() {
        lease.close() // closes any in-flight socket and QR/key owner
        phone.close()
    }

    override fun toString(): String = "Lc04MxH1LeasedSealedPhone([REDACTED])"

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val IO_TIMEOUT_MS = 2_500

        /** Numeric private IPv4 ONLY. There is no hostname/unbound fallback. */
        fun forSelectedPrivateIpv4(
            privateIpv4: String,
            port: Int,
            originalPhone: Lc04MxH1PhoneLab,
            elapsedMillis: () -> Long,
            selectedRouteEligible: () -> Boolean,
            bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
            revokeOriginalQrAndKeys: () -> Unit,
        ): Lc04MxH1LeasedSealedPhone = create(
            privateIpv4, port, originalPhone, elapsedMillis,
            selectedRouteEligible, bindUnconnectedToSelectedNetwork,
            revokeOriginalQrAndKeys, ::Socket,
        )

        /**
         * Hostile JVM test seam. The test socket may forward to loopback,
         * while the production lease still validates the ORIGINAL requested
         * RFC1918 destination and original selected-route checkpoints.
         * Never called from any installed app or production connector.
         */
        internal fun withTestSocketForHostileJVM(
            privateIpv4: String,
            port: Int,
            originalPhone: Lc04MxH1PhoneLab,
            elapsedMillis: () -> Long,
            selectedRouteEligible: () -> Boolean,
            bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
            revokeOriginalQrAndKeys: () -> Unit,
            testSocketSupplier: () -> Socket,
        ): Lc04MxH1LeasedSealedPhone = create(
            privateIpv4, port, originalPhone, elapsedMillis,
            selectedRouteEligible, bindUnconnectedToSelectedNetwork,
            revokeOriginalQrAndKeys, testSocketSupplier,
        )

        private fun create(
            ip: String,
            port: Int,
            phone: Lc04MxH1PhoneLab,
            now: () -> Long,
            eligible: () -> Boolean,
            binder: (Socket) -> Unit,
            revoke: () -> Unit,
            socketFactory: () -> Socket,
        ): Lc04MxH1LeasedSealedPhone {
            val lease = Lc04MxH1SelectedNetworkLease.forSelectedPrivateIpv4(
                ip, port, now, eligible, binder,
            ) {
                try { phone.close() } finally { revoke() }
            }
            return Lc04MxH1LeasedSealedPhone(lease, phone, now, socketFactory)
        }
    }
}

/**
 * Not an application-data codec: exactly 104 bytes (fixed-domain 32B
 * synthetic probe + header/tag), four-byte BE prefix, no arbitrary plaintext.
 */
internal object Lc04MxH1LeasedSealedFrameIo {
    private const val RECORD_BYTES = 104

    fun read(input: DataInputStream): ByteArray {
        if (input.readInt() != RECORD_BYTES) throw IOException("Invalid MXR1 size.")
        return ByteArray(RECORD_BYTES).also { input.readFully(it) }
    }

    fun write(output: DataOutputStream, wire: ByteArray) {
        require(wire.size == RECORD_BYTES) { "Invalid MXR1 synthetic record." }
        output.writeInt(RECORD_BYTES)
        output.write(wire)
        output.flush()
    }
}
