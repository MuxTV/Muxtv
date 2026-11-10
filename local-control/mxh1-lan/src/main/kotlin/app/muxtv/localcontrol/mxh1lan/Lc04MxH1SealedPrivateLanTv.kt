package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * LC04-S2J2C2B: original-TV-QR-owned, one-peer six-frame MXH1/MXR1
 * listener. This is an internal synthetic-only kernel, not an Android
 * integration, bearer, or a generic application-data transport.
 *
 * Only the existing TV-local-approved Ed25519 phone can exchange the
 * fixed-domain encrypted probes. The listener is bound to exactly one
 * numeric private IPv4 address; callers must independently guard the
 * selected Android Network and TV visibility before constructing it.
 */
class Lc04MxH1SealedPrivateLanTv private constructor(
    private val tv: TvHpkeQrSession,
    private val now: () -> Long,
    private val listener: ServerSocket,
    private val loopbackForHostTests: Boolean,
) : AutoCloseable {
    @Volatile private var stopped = false
    @Volatile private var completed = false
    @Volatile private var active: Socket? = null
    private val worker = Thread({ serveOnce() }, "muxtv-mxh1-mxr1-private-tv").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = listener.localPort

    /** Diagnostic only; not a source or pairing capability. */
    fun hasAuthenticatedSyntheticExchange(): Boolean =
        !stopped && completed && tv.hasSyntheticMxH1Proof(now())

    private fun serveOnce() {
        try {
            while (!stopped && tv.presentation(now()) != null) {
                val peer = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    return
                }
                if (!acquire(peer)) {
                    runCatching { peer.close() }
                    return
                }
                val accepted = try {
                    peer.use { socket ->
                        socket.soTimeout = IO_TIMEOUT_MS
                        // A private interface bind is not an identity proof.
                        // Reject public, VPN-tunneled or loopback peer IPs in
                        // the actual private-LAN factory.
                        if (!loopbackForHostTests &&
                            Lc04PrivateIpv4.parse(socket.inetAddress.hostAddress) == null
                        ) return@use false

                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())

                        val offer = Lc04MxH1FrameIo.read(
                            input, Lc04HandoffKind.SIGNED_SEED_OFFER,
                        )
                        val tvConfirmation = try {
                            if (stopped) null else
                                tv.acceptSyntheticMxH1SeedOffer(offer, now())
                        } finally { offer.fill(0) }
                        if (tvConfirmation == null || stopped) return@use false
                        try {
                            Lc04MxH1FrameIo.write(
                                output, tvConfirmation, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                        } finally { tvConfirmation.fill(0) }

                        val phoneConfirmation = Lc04MxH1FrameIo.read(
                            input, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                        )
                        val finished = try {
                            if (stopped) null else
                                tv.finishSyntheticMxH1ForProbeLab(phoneConfirmation, now)
                        } finally { phoneConfirmation.fill(0) }
                        if (finished == null || stopped) {
                            finished?.fill(0)
                            return@use false
                        }
                        try {
                            Lc04MxH1FrameIo.write(
                                output, finished, Lc04HandoffKind.TV_FINISHED,
                            )
                        } finally { finished.fill(0) }

                        // F4 alone is never sufficient to authorize F6.
                        // Require the sealed F5 AND phone TCP half-close.
                        val fifth = Lc04MxH1LeasedSealedFrameIo.read(input)
                        val proof = try {
                            input.read() == -1 && !stopped &&
                                tv.acceptMxH1SyntheticProbeForLab(fifth, now())
                        } finally { fifth.fill(0) }
                        if (!proof || stopped) return@use false

                        val sixth = tv.sealMxH1SyntheticProbeForLab(now())
                            ?: return@use false
                        try {
                            Lc04MxH1LeasedSealedFrameIo.write(output, sixth)
                            socket.shutdownOutput()
                        } finally { sixth.fill(0) }
                        !stopped
                    }
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                } finally { release(peer) }

                if (accepted && !stopped) {
                    completed = true
                    runCatching { listener.close() }
                    return
                }
                close() // one hostile/aborted peer consumes the original QR
                return
            }
        } finally {
            if (!completed) close()
        }
    }

    @Synchronized private fun acquire(peer: Socket): Boolean {
        if (stopped || active != null) return false
        active = peer
        return true
    }

    @Synchronized private fun release(peer: Socket) {
        if (active === peer) active = null
    }

    /** Synchronous cancellation during TV onStop or route/permission loss. */
    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        completed = false
        runCatching { listener.close() }
        runCatching { active?.close() }
        active = null
        tv.close()
    }

    override fun toString(): String =
        "Lc04MxH1SealedPrivateLanTv([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        private const val IO_TIMEOUT_MS = 2_500

        /** Never bind a wildcard, loopback, hostname or public interface. */
        fun startOnApprovedPrivateIpv4(
            address: String, originalTvQrOwner: TvHpkeQrSession, now: () -> Long,
        ): Lc04MxH1SealedPrivateLanTv {
            val ip = requireNotNull(Lc04PrivateIpv4.parse(address)) {
                "Numeric RFC1918 private IPv4 required."
            }
            require(originalTvQrOwner.hasApprovedPhoneForSyntheticMxH1(now())) {
                "Original QR and TV-local-approved phone required."
            }
            return open(ip, originalTvQrOwner, now, false)
        }

        /** Host-only fixture; cannot be used to bind a deployed TV endpoint. */
        internal fun startLoopbackForHostTests(
            originalTvQrOwner: TvHpkeQrSession, now: () -> Long,
        ): Lc04MxH1SealedPrivateLanTv = open(
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            originalTvQrOwner, now, true,
        )

        private fun open(
            ip: InetAddress, original: TvHpkeQrSession,
            now: () -> Long, hostOnly: Boolean,
        ): Lc04MxH1SealedPrivateLanTv {
            require(original.hasApprovedPhoneForSyntheticMxH1(now()))
            val socket = ServerSocket(0, 1, ip)
            socket.soTimeout = 300
            return Lc04MxH1SealedPrivateLanTv(original, now, socket, hostOnly)
        }
    }
}
