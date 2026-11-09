package app.muxtv.localcontrol.mxh1tcp

import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * S2J2B: one-use HOSTILE-TEST-ONLY TCP loopback exercising the *actual*
 * original TV/phone signed MXH1 state machine followed by one sealed MXR1
 * probe each way. This is NOT linked from either Android app, native LAN,
 * any source activation or any provider/credential-bearing API.
 *
 * Separate from legacy four-frame lab: phone MUST NOT half-close until after
 * sending the fifth authenticated record. TV requires input EOF after the
 * fifth record before its sixth encrypted response. Phone verifies response
 * plus TV EOF. Every failure closes the original TV QR/key owner.
 */
internal class Lc04MxH1SealedLoopbackTv private constructor(
    private val tv: TvHpkeQrSession,
    private val now: () -> Long,
    private val listener: ServerSocket,
) : AutoCloseable {
    @Volatile private var stopped = false
    @Volatile private var completed = false
    @Volatile private var active: Socket? = null
    private val worker = Thread({ serveOnce() }, "lc04-mxh1-mxr1-sealed-loopback").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = listener.localPort

    /** Diagnostic only: not a source/pairing authorization. */
    fun hasAuthenticatedSyntheticTcpExchange(): Boolean =
        !stopped && completed && tv.hasSyntheticMxH1Proof(now())

    private fun serveOnce() {
        try {
            while (!stopped && tv.presentation(now()) != null) {
                val accepted = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    return
                }
                if (!acquire(accepted)) {
                    runCatching { accepted.close() }
                    return
                }
                val result = try {
                    accepted.use { socket ->
                        socket.soTimeout = IO_TIMEOUT_MS
                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())

                        val offer = Lc04MxH1LengthFrame.read(
                            input, Lc04HandoffKind.SIGNED_SEED_OFFER,
                        )
                        val tvConfirm = try {
                            tv.acceptSyntheticMxH1SeedOffer(offer, now())
                        } finally { offer.fill(0) }
                        if (tvConfirm == null || stopped) return@use false
                        try {
                            Lc04MxH1LengthFrame.write(
                                output, tvConfirm, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                        } finally { tvConfirm.fill(0) }

                        val phoneConfirm = Lc04MxH1LengthFrame.read(
                            input, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                        )
                        val finished = try {
                            if (stopped) null else
                                tv.finishSyntheticMxH1ForProbeLab(phoneConfirm, now)
                        } finally { phoneConfirm.fill(0) }
                        if (finished == null || stopped) {
                            finished?.fill(0)
                            return@use false
                        }
                        try {
                            Lc04MxH1LengthFrame.write(
                                output, finished, Lc04HandoffKind.TV_FINISHED,
                            )
                        } finally { finished.fill(0) }

                        // Do not authorize a TV reply from TV_FINISHED alone.
                        val fifth = Lc04MxH1SealedFrameIo.read(input)
                        val ended = input.read() == -1
                        val approved = try {
                            ended && !stopped &&
                                tv.acceptMxH1SyntheticProbeForLab(fifth, now())
                        } finally { fifth.fill(0) }
                        if (!approved || stopped) return@use false
                        val sixth = tv.sealMxH1SyntheticProbeForLab(now())
                            ?: return@use false
                        try {
                            Lc04MxH1SealedFrameIo.write(output, sixth)
                            socket.shutdownOutput()
                        } finally { sixth.fill(0) }
                        !stopped
                    }
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                } finally { release(accepted) }
                if (result && !stopped) {
                    completed = true
                    runCatching { listener.close() }
                    return
                }
                close() // any hostile, partial, expired or cancelled peer revokes QR
                return
            }
        } finally {
            if (!completed) close()
        }
    }

    @Synchronized private fun acquire(socket: Socket): Boolean {
        if (stopped || active != null) return false
        active = socket
        return true
    }

    @Synchronized private fun release(socket: Socket) {
        if (active === socket) active = null
    }

    /** Synchronous cancellation from TV onStop / original QR owner loss. */
    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        completed = false
        runCatching { listener.close() }
        runCatching { active?.close() }
        active = null
        tv.close()
    }

    override fun toString() = "Lc04MxH1SealedLoopbackTv([REDACTED])"

    companion object {
        private const val IO_TIMEOUT_MS = 2_500
        fun startLoopbackForTests(
            originalApprovedTvOwner: TvHpkeQrSession, now: () -> Long,
        ): Lc04MxH1SealedLoopbackTv {
            require(originalApprovedTvOwner.hasApprovedPhoneForSyntheticMxH1(now())) {
                "Original QR and TV-local-approved Ed25519 phone are required."
            }
            val listener = ServerSocket(0, 1, sealedLoopback())
            listener.soTimeout = 300
            return Lc04MxH1SealedLoopbackTv(originalApprovedTvOwner, now, listener)
        }
    }
}

/** Single-attempt, fixed-domain synthetic phone. No generic plaintext or credential API. */
internal class Lc04MxH1SealedLoopbackPhone private constructor(
    private val port: Int,
    private val now: () -> Long,
) : AutoCloseable {
    @Volatile private var stopped = false
    @Volatile private var active: Socket? = null

    fun complete(phone: Lc04MxH1PhoneLab): Boolean {
        if (stopped) return false
        val socket = Socket()
        if (!acquire(socket)) {
            runCatching { socket.close() }
            return false
        }
        var success = false
        return try {
            socket.use {
                if (stopped) return false
                socket.soTimeout = IO_TIMEOUT_MS
                socket.connect(InetSocketAddress(sealedLoopback(), port), CONNECT_TIMEOUT_MS)
                if (stopped) return false
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())

                val offer = phone.createSignedSeedOffer() ?: return false
                try {
                    Lc04MxH1LengthFrame.write(
                        output, offer, Lc04HandoffKind.SIGNED_SEED_OFFER,
                    )
                } finally { offer.fill(0) }
                val tvConfirm = Lc04MxH1LengthFrame.read(
                    input, Lc04HandoffKind.TV_KEY_CONFIRM,
                )
                val phoneConfirm = try {
                    phone.acceptTvConfirmationAwaitingFinished(tvConfirm)
                } finally { tvConfirm.fill(0) }
                if (phoneConfirm == null || stopped) return false
                try {
                    Lc04MxH1LengthFrame.write(
                        output, phoneConfirm, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                    )
                } finally { phoneConfirm.fill(0) }

                val finished = Lc04MxH1LengthFrame.read(
                    input, Lc04HandoffKind.TV_FINISHED,
                )
                val verified = try {
                    !stopped && phone.acceptTvFinishedForProbeLab(
                        finished, now,
                    )
                } finally { finished.fill(0) }
                if (!verified || stopped) return false

                val fifth = phone.sealMxH1SyntheticProbeForLab()
                    ?: return false
                try {
                    Lc04MxH1SealedFrameIo.write(output, fifth)
                    socket.shutdownOutput()
                } finally { fifth.fill(0) }
                val sixth = Lc04MxH1SealedFrameIo.read(input)
                try {
                    // Require the exact sealed reply followed by EOF before
                    // reporting any symmetric encrypted network proof.
                    success = input.read() == -1 && !stopped &&
                        phone.acceptMxH1SyntheticProbeForLab(sixth) &&
                        phone.hasAuthenticatedTvSyntheticProof()
                    success
                } finally { sixth.fill(0) }
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            release(socket)
            if (!success || stopped) phone.close()
        }
    }

    @Synchronized private fun acquire(socket: Socket): Boolean {
        if (stopped || active != null) return false
        active = socket
        return true
    }

    @Synchronized private fun release(socket: Socket) {
        if (active === socket) active = null
    }

    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        runCatching { active?.close() }
        active = null
    }

    override fun toString() = "Lc04MxH1SealedLoopbackPhone([REDACTED])"

    companion object {
        private const val IO_TIMEOUT_MS = 2_500
        private const val CONNECT_TIMEOUT_MS = 1_500
        fun loopbackForTests(
            port: Int, now: () -> Long,
        ): Lc04MxH1SealedLoopbackPhone {
            require(port in 1..65535)
            return Lc04MxH1SealedLoopbackPhone(port, now)
        }
    }
}

/** Exact 4-byte length prefix plus 104-byte MXR1; never accept arbitrary data. */
internal object Lc04MxH1SealedFrameIo {
    private const val SYNTHETIC_RECORD_BYTES = 104

    fun read(input: DataInputStream): ByteArray {
        val length = input.readInt()
        if (length != SYNTHETIC_RECORD_BYTES) throw IOException("Wrong sealed frame length.")
        return ByteArray(length).also { input.readFully(it) }
    }

    fun write(output: DataOutputStream, wire: ByteArray) {
        require(wire.size == SYNTHETIC_RECORD_BYTES)
        output.writeInt(wire.size)
        output.write(wire)
        output.flush()
    }
}

private fun sealedLoopback(): InetAddress =
    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
