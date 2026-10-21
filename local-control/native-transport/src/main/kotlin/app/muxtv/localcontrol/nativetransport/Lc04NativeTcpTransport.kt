package app.muxtv.localcontrol.nativetransport

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * LC04-S3-E3: independent, bounded, one-frame-per-connection native TCP endpoint.
 *
 * NO legacy /pair, HTTP, source provisioning, strings, URLs or credentials.
 * The peer address is only a transport locator, NEVER an identity. The TV's
 * original QR key, TV-local approval and cryptographic proofs are trust roots.
 *
 * This module is NOT wired to Android Activity/UI or the phone manifest yet.
 */
object Lc04PrivateIpv4 {
    fun parse(address: String): InetAddress? {
        if (address.isEmpty() || address != address.trim()) return null
        val octets = address.split('.')
        if (octets.size != 4) return null
        val bytes = ByteArray(4)
        for ((index, part) in octets.withIndex()) {
            if (part.isEmpty() || part.length > 3 ||
                (part.length > 1 && part[0] == '0') ||
                part.any { it !in '0'..'9' }
            ) return null
            val value = part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            bytes[index] = value.toByte()
        }
        val a = bytes[0].toInt() and 255
        val b = bytes[1].toInt() and 255
        if (a != 10 && !(a == 172 && b in 16..31) &&
            !(a == 192 && b == 168)
        ) return null
        return InetAddress.getByAddress(bytes) // numeric only; never DNS
    }
}

/** Four-byte unsigned-big-endian size, followed by exactly one MXW1 frame. */
internal object Lc04LengthPrefix {
    private const val MAX = Lc04WireLab.MAX_FRAME_BYTES

    fun read(input: DataInputStream, allowEmpty: Boolean): ByteArray? {
        val n = input.readInt() // EOF, timeout, or partial header MUST reject
        if (n == 0 && allowEmpty) return null
        if (n <= 0 || n > MAX) throw IOException("Invalid bounded frame length.")
        val bytes = ByteArray(n)
        input.readFully(bytes)
        if (Lc04WireLab.decode(bytes) == null) throw IOException("Invalid frame.")
        return bytes
    }

    fun write(output: DataOutputStream, bytes: ByteArray?) {
        if (bytes == null) {
            output.writeInt(0) // no application acknowledgement; not a success token
        } else {
            require(bytes.size in 1..MAX && Lc04WireLab.decode(bytes) != null)
            output.writeInt(bytes.size)
            output.write(bytes)
        }
        output.flush()
    }
}

/**
 * Phone-side bounded exchange: only PUBLIC_KEY, PHONE_PROOF or SIGNED_PROBE
 * requests may be sent, only TV_CHALLENGE or SEALED_TV_RECEIPT returned.
 * The caller MUST separately authenticate the returned data with existing
 * QR-pinned enrollment and one-shot AES-GCM verification components.
 */
class Lc04TcpPhoneExchange private constructor(private val ip: InetAddress, private val port: Int) {
    init { require(port in 1..65535) }

    fun exchange(request: ByteArray): ByteArray? {
        val frame = Lc04WireLab.decode(request) ?: return null
        if (frame.kind != Lc04WireKind.PHONE_PUBLIC_KEY &&
            frame.kind != Lc04WireKind.PHONE_PROOF &&
            frame.kind != Lc04WireKind.SIGNED_CHANNEL_PROBE
        ) return null
        return try {
            Socket().use { socket ->
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
                DataOutputStream(socket.getOutputStream()).use { output ->
                    Lc04LengthPrefix.write(output, request)
                    val reply = Lc04LengthPrefix.read(
                        DataInputStream(socket.getInputStream()), allowEmpty = true,
                    ) ?: return null
                    val kind = Lc04WireLab.decode(reply)?.kind ?: return null
                    if ((frame.kind == Lc04WireKind.PHONE_PUBLIC_KEY &&
                            kind == Lc04WireKind.TV_CHALLENGE) ||
                        (frame.kind == Lc04WireKind.SIGNED_CHANNEL_PROBE &&
                            kind == Lc04WireKind.SEALED_TV_RECEIPT)
                    ) reply else null
                }
            }
        } catch (_: IOException) {
            null // no fallback to plaintext /pair, no raw reply and no peer trust
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val READ_TIMEOUT_MS = 2_500

        fun forPrivateIpv4(address: String, port: Int): Lc04TcpPhoneExchange {
            val parsed = requireNotNull(Lc04PrivateIpv4.parse(address)) {
                "Only explicit RFC1918 numeric IPv4 endpoints are supported."
            }
            return Lc04TcpPhoneExchange(parsed, port)
        }

        /** Test-only loopback connector, not exposed as a public factory. */
        internal fun loopbackForTests(port: Int): Lc04TcpPhoneExchange =
            Lc04TcpPhoneExchange(loopbackAddress(), port)
    }
}

/**
 * Untrusted ingress cannot set approval, mint a pairing grant or obtain
 * protected IPTV data. TV calls approveOnTv() only from a trusted local UI.
 */
class Lc04TvFrameDispatcher(
    private val tv: TvHpkeQrSession,
    private val elapsedMillis: () -> Long,
) {
    fun handle(bytes: ByteArray): ByteArray? {
        val frame = Lc04WireLab.decode(bytes) ?: return null
        val now = elapsedMillis()
        if (tv.presentation(now) == null) return null
        return when (frame.kind) {
            Lc04WireKind.PHONE_PUBLIC_KEY -> {
                val gate = tv.phoneEnrollmentGate(elapsedMillis) ?: return null
                gate.beginPublicOffer(bytes)?.let(Lc04PublicEnrollmentWire::tvChallenge)
            }
            Lc04WireKind.PHONE_PROOF -> {
                val gate = tv.phoneEnrollmentGate(elapsedMillis) ?: return null
                val verified = gate.verifyPublicProof(bytes) as? PhoneEnrollmentResult.Verified
                if (verified != null) tv.activateVerifiedPhone(verified.identity, elapsedMillis())
                // No unauthenticated "paired" status: only protected probe gives
                // the phone a cryptographically verifiable TV receipt.
                null
            }
            Lc04WireKind.SIGNED_CHANNEL_PROBE ->
                tv.acceptProtectedProbe(bytes, now)
            else -> null
        }
    }
}

/** One logical request per connection; blocking and synchronized by one worker. */
class Lc04TvTcpServer private constructor(
    private val tv: TvHpkeQrSession,
    private val elapsedMillis: () -> Long,
    private val listener: ServerSocket,
    private val loopbackTestOnly: Boolean,
) : AutoCloseable {
    @Volatile private var live = true
    @Volatile private var serving: Socket? = null
    private val rate = BoundedPeerBudget(elapsedMillis)
    private val dispatcher = Lc04TvFrameDispatcher(tv, elapsedMillis)
    private val thread = Thread({ serve() }, "muxtv-lc04-public-only-tcp").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = listener.localPort

    private fun serve() {
        try {
            while (live && tv.presentation(elapsedMillis()) != null) {
                val peerSocket = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    break
                }
                serving = peerSocket
                try {
                    peerSocket.use { socket ->
                        socket.soTimeout = SOCKET_TIMEOUT_MS
                        val ip = socket.inetAddress
                        if (!loopbackTestOnly && Lc04PrivateIpv4.parse(ip.hostAddress) == null) {
                            return@use
                        }
                        if (!rate.permit(ip.hostAddress)) return@use
                        val incoming = Lc04LengthPrefix.read(
                            DataInputStream(socket.getInputStream()), allowEmpty = false,
                        ) ?: return@use
                        // Same response for all denied kinds/proofs: an empty frame.
                        val response = try {
                            dispatcher.handle(incoming)
                        } catch (_: RuntimeException) {
                            null // never send stacktraces or input bytes to the peer
                        } finally {
                            incoming.fill(0)
                        }
                        Lc04LengthPrefix.write(
                            DataOutputStream(socket.getOutputStream()), response,
                        )
                    }
                } catch (_: IOException) {
                    // Timeout, early EOF, bad length or disconnect: close the
                    // individual connection, preserve TV-owned QR lifecycle.
                } finally {
                    serving = null
                }
            }
        } finally {
            close()
        }
    }

    @Synchronized
    override fun close() {
        if (!live) return
        live = false
        try { listener.close() } catch (_: IOException) {}
        try { serving?.close() } catch (_: IOException) {}
        rate.clear()
        tv.close() // network stop, QR expiry or manual stop revokes the entire pairing
    }

    override fun toString(): String = "Lc04TvTcpServer([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        private const val SOCKET_TIMEOUT_MS = 2_500

        /** Explicit private-interface TV bind only, no wildcard/DNS/localhost. */
        fun start(
            privateIpv4Address: String,
            tv: TvHpkeQrSession,
            elapsedMillis: () -> Long,
        ): Lc04TvTcpServer {
            require(tv.presentation(elapsedMillis()) != null)
            val ip = requireNotNull(Lc04PrivateIpv4.parse(privateIpv4Address))
            return open(ip, tv, elapsedMillis, testOnly = false)
        }

        internal fun startLoopbackForTests(
            tv: TvHpkeQrSession,
            elapsedMillis: () -> Long,
        ): Lc04TvTcpServer = open(loopbackAddress(), tv, elapsedMillis, testOnly = true)

        private fun open(
            ip: InetAddress, tv: TvHpkeQrSession, elapsedMillis: () -> Long,
            testOnly: Boolean,
        ): Lc04TvTcpServer {
            require(tv.presentation(elapsedMillis()) != null)
            val listener = ServerSocket(0, 8, ip)
            listener.soTimeout = 500
            return Lc04TvTcpServer(tv, elapsedMillis, listener, testOnly)
        }
    }
}

/** Global and per-peer fixed-budget admission: NO unbounded attacker IP map. */
internal class BoundedPeerBudget(private val elapsedMillis: () -> Long) {
    private val counts = HashMap<String, Int>()
    private var start: Long? = null
    private var lastObserved: Long? = null
    private var total = 0
    private var revoked = false

    @Synchronized fun permit(ip: String): Boolean {
        if (revoked) return false
        val now = elapsedMillis()
        val previous = lastObserved
        if (now < 0 || previous != null && now < previous) {
            clear()
            revoked = true
            return false
        }
        lastObserved = now
        val began = start
        if (began == null || now - began >= 60_000) {
            start = now
            counts.clear()
            total = 0
        }
        val current = counts[ip] ?: 0
        if (current == 0 && counts.size >= 16) return false
        if (total >= 48 || current >= 12) return false
        counts[ip] = current + 1
        total++
        return true
    }

    @Synchronized fun clear() {
        counts.clear()
        total = 0
    }
}

private fun loopbackAddress(): InetAddress =
    InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
