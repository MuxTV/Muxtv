package app.muxtv.localcontrol.mxh1tcp

import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import app.muxtv.localcontrol.wirelab.Lc04HandoffWireLab
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * LC04-S2G synthetic, one-use, real TCP laboratory. The listener is bound to
 * loopback ONLY and is not referenced by either installed Android APK.
 *
 * The deployed MXW1 listener remains unchanged. This class owns precisely
 * one socket, three canonical MXH1 frames and a terminal zero-length
 * TRANSPORT acknowledgement. The acknowledgement is NOT key confirmation,
 * source permission, a TV identity proof, or an encrypted data channel.
 *
 * The caller provides the ORIGINAL TvHpkeQrSession after its TV-local
 * Ed25519 approval; this lab does not mint a second receiver or consent gate.
 */
internal class Lc04MxH1LoopbackTv private constructor(
    private val tv: TvHpkeQrSession,
    private val elapsedMillis: () -> Long,
    private val listener: ServerSocket,
) : AutoCloseable {
    @Volatile private var stopped = false
    @Volatile private var completed = false
    @Volatile private var serving: Socket? = null
    private val worker = Thread({ serveOneAttempt() }, "lc04-mxh1-one-use-loopback").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = listener.localPort

    /** This is a diagnostic only; it never yields a session/credential token. */
    fun hasSyntheticNetworkProof(): Boolean =
        !stopped && completed && tv.hasSyntheticMxH1Proof(elapsedMillis())

    private fun serveOneAttempt() {
        try {
            while (!stopped && tv.presentation(elapsedMillis()) != null) {
                val peer = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    return
                }
                if (!acquire(peer)) {
                    try { peer.close() } catch (_: IOException) {}
                    return
                }
                val success = try {
                    peer.use { socket ->
                        socket.soTimeout = FRAME_TIMEOUT_MS
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())

                        val offer = Lc04MxH1LengthFrame.read(
                            input, Lc04HandoffKind.SIGNED_SEED_OFFER,
                        )
                        val tvReply = try {
                            tv.acceptSyntheticMxH1SeedOffer(offer, elapsedMillis())
                        } finally { offer.fill(0) }
                        if (tvReply == null || stopped) return@use false
                        try {
                            Lc04MxH1LengthFrame.write(
                                output, tvReply, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                        } finally { tvReply.fill(0) }

                        val phoneReply = Lc04MxH1LengthFrame.read(
                            input, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                        )
                        // No bytes after the third protocol frame. A partial
                        // or pipelined fourth frame MUST fail closed, including
                        // a client that refuses to half-close by the deadline.
                        val ended = input.read() == -1
                        val verified = try {
                            if (!ended || stopped) false else
                                tv.acceptSyntheticMxH1PhoneConfirmation(
                                    phoneReply, elapsedMillis(),
                                )
                        } finally { phoneReply.fill(0) }
                        if (!verified || stopped) return@use false
                        output.writeInt(0)
                        output.flush()
                        socket.shutdownOutput()
                        true
                    }
                } catch (_: IOException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                } finally {
                    release(peer)
                }

                if (success && !stopped) {
                    completed = true
                    // One accepted attempt exhausts this listener. Keep the
                    // original QR owner alive until its Activity closes it.
                    try { listener.close() } catch (_: IOException) {}
                    return
                }
                close() // any failure revokes the original TV QR/key owner
                return
            }
        } finally {
            if (!completed) close()
        }
    }

    @Synchronized
    private fun acquire(peer: Socket): Boolean {
        if (stopped || serving != null) return false
        serving = peer
        return true
    }

    @Synchronized
    private fun release(peer: Socket) {
        if (serving === peer) serving = null
    }

    @Synchronized
    override fun close() {
        if (stopped) return
        stopped = true
        completed = false
        try { listener.close() } catch (_: IOException) {}
        try { serving?.close() } catch (_: IOException) {}
        serving = null
        tv.close() // Activity/route stop and failure share one revocation owner.
    }

    override fun toString(): String = "Lc04MxH1LoopbackTv([PRIVATE_SOCKET_REDACTED])"

    companion object {
        private const val FRAME_TIMEOUT_MS = 2_500

        /** No public/network factory. GitHub-hosted hostile loopback only. */
        fun startLoopbackForTests(
            originalOwner: TvHpkeQrSession,
            elapsedMillis: () -> Long,
        ): Lc04MxH1LoopbackTv {
            require(originalOwner.presentation(elapsedMillis()) != null)
            val listener = ServerSocket(0, 1, loopback())
            listener.soTimeout = 300
            return Lc04MxH1LoopbackTv(originalOwner, elapsedMillis, listener)
        }
    }
}

/**
 * Phone-side one-shot connected MXH1 lab transport. No arbitrary bytes or
 * generic endpoint APIs; the phone lab creates a fresh, QR-pinned HPKE offer
 * and must authenticate TV_CONFIRM HMAC before PHONE_CONFIRM is transmitted.
 * Closing concurrently aborts connect, read, write or EOF wait.
 */
internal class Lc04MxH1LoopbackPhone private constructor(
    private val port: Int,
) : AutoCloseable {
    @Volatile private var revoked = false
    private var activeSocket: Socket? = null

    @Synchronized
    private fun acquire(socket: Socket): Boolean {
        if (revoked || activeSocket != null) return false
        activeSocket = socket
        return true
    }

    @Synchronized
    private fun release(socket: Socket) {
        if (activeSocket === socket) activeSocket = null
    }

    /**
     * True means only that an already HMAC-authenticated TV sent the
     * expected terminal transport marker and EOF. No data-plane key is
     * exported, persistent pairing authorized or source prepared.
     */
    fun completeSyntheticExchange(phone: Lc04MxH1PhoneLab): Boolean {
        if (revoked) return false
        val socket = Socket()
        if (!acquire(socket)) {
            try { socket.close() } catch (_: IOException) {}
            return false
        }
        return try {
            socket.use {
                if (revoked) return false
                socket.soTimeout = FRAME_TIMEOUT_MS
                socket.connect(InetSocketAddress(loopback(), port), CONNECT_TIMEOUT_MS)
                if (revoked) return false
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val offer = phone.createSignedSeedOffer() ?: return false
                try {
                    Lc04MxH1LengthFrame.write(
                        output, offer, Lc04HandoffKind.SIGNED_SEED_OFFER,
                    )
                } finally { offer.fill(0) }
                val tvConfirmation = Lc04MxH1LengthFrame.read(
                    input, Lc04HandoffKind.TV_KEY_CONFIRM,
                )
                val phoneConfirmation = try {
                    phone.acceptTvConfirmation(tvConfirmation)
                } finally { tvConfirmation.fill(0) }
                if (phoneConfirmation == null || revoked) return false
                try {
                    Lc04MxH1LengthFrame.write(
                        output, phoneConfirmation, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                    )
                } finally { phoneConfirmation.fill(0) }

                socket.shutdownOutput()
                val marker = input.readInt()
                marker == 0 && input.read() == -1 &&
                    !revoked && phone.hasAuthenticatedTvSyntheticProof()
            }
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            release(socket)
            if (revoked) phone.close()
        }
    }

    @Synchronized
    override fun close() {
        if (revoked) return
        revoked = true
        try { activeSocket?.close() } catch (_: IOException) {}
        activeSocket = null
    }

    override fun toString(): String = "Lc04MxH1LoopbackPhone([PRIVATE_SOCKET_REDACTED])"

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val FRAME_TIMEOUT_MS = 2_500

        /** Never DNS or a public LAN route; research-only loopback. */
        fun loopbackForTests(port: Int): Lc04MxH1LoopbackPhone {
            require(port in 1..65535)
            return Lc04MxH1LoopbackPhone(port)
        }
    }
}

/** Exactly one length-prefixed canonical MXH1 frame, no MXW1 fallback. */
internal object Lc04MxH1LengthFrame {
    fun read(input: DataInputStream, expected: Lc04HandoffKind): ByteArray {
        val size = input.readInt()
        if (size !in Lc04HandoffWireLab.HEADER_BYTES..Lc04HandoffWireLab.MAX_FRAME_BYTES) {
            throw IOException("Invalid bounded MXH1 frame")
        }
        val bytes = ByteArray(size)
        input.readFully(bytes)
        val frame = Lc04HandoffWireLab.decode(bytes)
        if (frame == null || frame.kind != expected) {
            bytes.fill(0)
            throw IOException("Invalid canonical MXH1 kind")
        }
        return bytes
    }

    fun write(output: DataOutputStream, frame: ByteArray, expected: Lc04HandoffKind) {
        val parsed = Lc04HandoffWireLab.decode(frame)
        require(parsed != null && parsed.kind == expected)
        require(frame.size <= Lc04HandoffWireLab.MAX_FRAME_BYTES)
        output.writeInt(frame.size)
        output.write(frame)
        output.flush()
    }
}

private fun loopback(): InetAddress =
    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
