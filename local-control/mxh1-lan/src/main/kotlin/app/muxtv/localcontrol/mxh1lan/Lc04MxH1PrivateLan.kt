package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.mxh1.Lc04MxH1PhoneLab
import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4
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
 * LC04-S2I: One-use, QR-owner-bound, four-frame authenticated MXH1 transport.
 * The caller must already own the SAME original TV QR, TV-local-approved phone
 * and visible Activity. There is no HTTP route, source/pairing capability,
 * arbitrary payload operation or unattended network startup here.
 */
class Lc04MxH1PrivateLanTv private constructor(
    private val tv: TvHpkeQrSession,
    private val now: () -> Long,
    private val listener: ServerSocket,
    private val loopbackOnly: Boolean,
) : AutoCloseable {
    @Volatile private var stopped = false
    @Volatile private var completed = false
    @Volatile private var active: Socket? = null
    private val worker = Thread({ runOnce() }, "muxtv-lc04-mxh1-private-lan").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = listener.localPort
    fun hasSyntheticNetworkProof(): Boolean =
        !stopped && completed && tv.hasSyntheticMxH1Proof(now())

    private fun runOnce() {
        try {
            while (!stopped && tv.presentation(now()) != null) {
                val peer = try { listener.accept() }
                    catch (_: SocketTimeoutException) { continue }
                    catch (_: IOException) { return }
                if (!acquire(peer)) {
                    runCatching { peer.close() }
                    return
                }
                val valid = try {
                    peer.use { socket ->
                        socket.soTimeout = IO_TIMEOUT_MS
                        // A private-IP listener is NOT permission to accept
                        // public/VPN IP peers, even if the OS route changes.
                        if (!loopbackOnly &&
                            Lc04PrivateIpv4.parse(socket.inetAddress.hostAddress) == null
                        ) return@use false

                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        val offer = Lc04MxH1FrameIo.read(
                            input, Lc04HandoffKind.SIGNED_SEED_OFFER,
                        )
                        val tvConfirm = try {
                            tv.acceptSyntheticMxH1SeedOffer(offer, now())
                        } finally { offer.fill(0) }
                        if (tvConfirm == null || stopped) return@use false
                        try {
                            Lc04MxH1FrameIo.write(
                                output, tvConfirm, Lc04HandoffKind.TV_KEY_CONFIRM,
                            )
                        } finally { tvConfirm.fill(0) }
                        val phoneConfirm = Lc04MxH1FrameIo.read(
                            input, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                        )
                        val finished = try {
                            if (input.read() != -1 || stopped) null
                            else tv.finishSyntheticMxH1PhoneConfirmation(
                                phoneConfirm, now(),
                            )
                        } finally { phoneConfirm.fill(0) }
                        if (finished == null || stopped) {
                            finished?.fill(0)
                            return@use false
                        }
                        try {
                            Lc04MxH1FrameIo.write(
                                output, finished, Lc04HandoffKind.TV_FINISHED,
                            )
                            socket.shutdownOutput()
                        } finally { finished.fill(0) }
                        !stopped
                    }
                } catch (_: IOException) {
                    false
                } catch (_: RuntimeException) {
                    false
                } finally { release(peer) }
                if (valid && !stopped) {
                    completed = true
                    runCatching { listener.close() }
                    return
                }
                close() // first hostile/aborted peer revokes original QR owner
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

    /** Idempotent and synchronous from Activity.onStop / default-route loss. */
    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        completed = false
        runCatching { listener.close() }
        runCatching { active?.close() }
        active = null
        tv.close()
    }

    override fun toString() = "Lc04MxH1PrivateLanTv([ADDRESS_REDACTED])"

    companion object {
        private const val IO_TIMEOUT_MS = 2_500
        /** Never bind a wildcard, public interface, hostname or VPN route. */
        fun startOnApprovedPrivateIpv4(
            ipLiteral: String, originalTvOwner: TvHpkeQrSession, now: () -> Long,
        ): Lc04MxH1PrivateLanTv {
            val ip = requireNotNull(Lc04PrivateIpv4.parse(ipLiteral)) {
                "Only numeric RFC1918 private IPv4 is permitted."
            }
            require(originalTvOwner.hasApprovedPhoneForSyntheticMxH1(now())) {
                "TV-local-approved identity and original QR are required."
            }
            return open(ip, originalTvOwner, now, false)
        }

        internal fun startLoopbackForTests(
            originalTvOwner: TvHpkeQrSession, now: () -> Long,
        ): Lc04MxH1PrivateLanTv = open(
            InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
            originalTvOwner, now, true,
        )

        private fun open(
            ip: InetAddress, owner: TvHpkeQrSession, now: () -> Long,
            testOnly: Boolean,
        ): Lc04MxH1PrivateLanTv {
            require(owner.hasApprovedPhoneForSyntheticMxH1(now()))
            val listener = ServerSocket(0, 1, ip)
            listener.soTimeout = 300
            return Lc04MxH1PrivateLanTv(owner, now, listener, testOnly)
        }
    }
}

/**
 * A caller MUST supply a network-binding action that binds the *unconnected*
 * raw socket to the selected Android Wi-Fi/Ethernet Network. No unbound
 * production connector exists. The callback must re-check network eligibility.
 */
class Lc04MxH1PrivateLanPhone private constructor(
    private val ip: InetAddress,
    private val port: Int,
    private val bindBeforeConnect: (Socket) -> Unit,
) : AutoCloseable {
    @Volatile private var stopped = false
    private var active: Socket? = null

    @Synchronized private fun acquire(peer: Socket): Boolean {
        if (stopped || active != null) return false
        active = peer
        return true
    }
    @Synchronized private fun release(peer: Socket) {
        if (active === peer) active = null
    }

    /**
     * Only a synthetic proof boolean, no session keys. The phone is VERIFIED
     * only after a genuine HMAC-authenticated TV_FINISHED and exact EOF.
     */
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
                bindBeforeConnect(socket)
                if (stopped) return false
                socket.soTimeout = IO_TIMEOUT_MS
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                if (stopped) return false
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val offer = phone.createSignedSeedOffer() ?: return false
                try {
                    Lc04MxH1FrameIo.write(
                        output, offer, Lc04HandoffKind.SIGNED_SEED_OFFER,
                    )
                } finally { offer.fill(0) }
                val tvConfirm = Lc04MxH1FrameIo.read(
                    input, Lc04HandoffKind.TV_KEY_CONFIRM,
                )
                val phoneConfirm = try {
                    phone.acceptTvConfirmationAwaitingFinished(tvConfirm)
                } finally { tvConfirm.fill(0) }
                if (phoneConfirm == null || stopped) return false
                try {
                    Lc04MxH1FrameIo.write(
                        output, phoneConfirm, Lc04HandoffKind.PHONE_KEY_CONFIRM,
                    )
                } finally { phoneConfirm.fill(0) }
                socket.shutdownOutput()
                val finished = Lc04MxH1FrameIo.read(
                    input, Lc04HandoffKind.TV_FINISHED,
                )
                try {
                    success = input.read() == -1 && !stopped &&
                        phone.acceptTvFinished(finished) &&
                        phone.hasAuthenticatedTvSyntheticProof()
                    success
                } finally { finished.fill(0) }
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

    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        runCatching { active?.close() }
        active = null
    }
    override fun toString() = "Lc04MxH1PrivateLanPhone([ADDRESS_REDACTED])"

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val IO_TIMEOUT_MS = 2_500

        /** Hostname-free, VPN-safe selected Network binder is compulsory. */
        fun forPrivateIpv4OnNetwork(
            ipLiteral: String, port: Int, bindUnconnectedSocket: (Socket) -> Unit,
        ): Lc04MxH1PrivateLanPhone {
            require(port in 1..65535)
            val address = requireNotNull(Lc04PrivateIpv4.parse(ipLiteral)) {
                "MXH1 only accepts a numeric RFC1918 IPv4 locator."
            }
            return Lc04MxH1PrivateLanPhone(address, port, bindUnconnectedSocket)
        }

        internal fun loopbackForTests(
            port: Int, bind: (Socket) -> Unit = {},
        ): Lc04MxH1PrivateLanPhone {
            require(port in 1..65535)
            return Lc04MxH1PrivateLanPhone(
                InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port, bind,
            )
        }
    }
}

/** No other kinds or arbitrary encrypted source commands can use this codec. */
internal object Lc04MxH1FrameIo {
    fun read(input: DataInputStream, kind: Lc04HandoffKind): ByteArray {
        val length = input.readInt()
        if (length !in Lc04HandoffWireLab.HEADER_BYTES..
                Lc04HandoffWireLab.MAX_FRAME_BYTES
        ) throw IOException("Invalid MXH1 length.")
        val result = ByteArray(length)
        input.readFully(result)
        if (Lc04HandoffWireLab.decode(result)?.kind != kind) {
            result.fill(0)
            throw IOException("Invalid MXH1 kind.")
        }
        return result
    }

    fun write(output: DataOutputStream, bytes: ByteArray, kind: Lc04HandoffKind) {
        require(bytes.size in Lc04HandoffWireLab.HEADER_BYTES..
            Lc04HandoffWireLab.MAX_FRAME_BYTES &&
            Lc04HandoffWireLab.decode(bytes)?.kind == kind)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
