package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * S2J3D2A: a REAL, independent APPDATA TCP connection after original F6/EOF.
 *
 * Only the first authenticated Cancel(1) is accepted, with a fixed EMPTY
 * reply. Cancel is a wire-level proof in this class: NOTHING is dispatched to
 * SourceOnboarding, and no M3U/Xtream/Activate command can reach a source.
 * The source record port must come from the *original* QR/HPKE owner after
 * completed six-frame TCP; the constructor cannot derive or mint keys.
 *
 * A failed or hostile first peer consumes the original QR. There is no HTTP,
 * generic decrypted-payload callback, reconnect, bearer, or relay.
 */
class Lc04SourceFirstRequestTv private constructor(
    private val owner: TvHpkeQrSession,
    private val records: Lc04SourceAeadPort,
    private val listener: ServerSocket,
    private val now: () -> Long,
    private val selectedRouteEligible: () -> Boolean,
    private val loopbackForHostTests: Boolean,
) : AutoCloseable {
    private val started = now()
    @Volatile private var stopped = false
    @Volatile private var completed = false
    @Volatile private var active: Socket? = null
    private val worker = Thread({ serveOnce() }, "muxtv-lc04-appdata-first-tv").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = listener.localPort

    /** Evidence-only, not an authorization grant. */
    fun hasAuthenticatedFirstRequest(): Boolean = completed && !stopped

    private fun eligible(): Boolean =
        !stopped && runCatching {
            val current = now()
            current >= started && current - started < PROOF_TTL_MS &&
                selectedRouteEligible() && owner.hasSyntheticMxH1Proof(current)
        }.getOrDefault(false)

    private fun serveOnce() {
        try {
            while (eligible()) {
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
                        if (!loopbackForHostTests &&
                            Lc04PrivateIpv4.parse(socket.inetAddress.hostAddress) == null
                        ) return@use false
                        if (!eligible()) return@use false

                        val input = DataInputStream(socket.getInputStream())
                        val output = DataOutputStream(socket.getOutputStream())
                        val wire = Lc04SourceFirstFrameIo.read(input)
                        val command = try {
                            if (!eligible()) null else records.openRequest(wire)
                        } finally { wire.fill(0) }
                        if (command !is Lc04SourceCommand.Cancel ||
                            command.sequence != 1L || !eligible()
                        ) return@use false

                        // No second command, early data, or pipeline is accepted:
                        // the phone must half-close the source proof socket.
                        if (input.read() != -1 || !eligible()) return@use false
                        val response = records.sealReply(
                            Lc04SourceReply(1, Lc04SourceReplyCode.EMPTY),
                        ) ?: return@use false
                        try {
                            if (!eligible()) return@use false
                            Lc04SourceFirstFrameIo.write(output, response)
                            socket.shutdownOutput()
                            eligible()
                        } finally { response.fill(0) }
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
                close() // terminal denial, including unauthenticated first peer
                return
            }
        } finally {
            // The first-request proof is deliberately terminal even on
            // success; a separately reviewed TV source principal will be
            // needed before the channel can support provider operations.
            records.close()
            if (completed) {
                // This one-shot proof has no further APPDATA operations.
                // Revoke the spent original TV QR and all remaining key
                // ownership as soon as the successful reply is committed.
                owner.close()
            } else close()
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

    /** Synchronous from TV Activity.onStop, route or permission loss. */
    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        completed = false
        runCatching { listener.close() }
        runCatching { active?.close() }
        active = null
        records.close()
        owner.close()
    }

    override fun toString(): String =
        "Lc04SourceFirstRequestTv([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        private const val IO_TIMEOUT_MS = 2_500
        private const val PROOF_TTL_MS = 30_000L

        /**
         * Production: the caller must have independently completed real TV F6
         * write + TCP half-close and taken the one-use original-owner port.
         * Re-check the same selected Wi-Fi/Ethernet and API37 permission.
         */
        fun startOnApprovedPrivateIpv4(
            address: String,
            originalTvQrOwner: TvHpkeQrSession,
            sourceRecordsAfterRealF6: Lc04SourceAeadPort,
            now: () -> Long,
            selectedRouteEligible: () -> Boolean,
        ): Lc04SourceFirstRequestTv {
            val ip = requireNotNull(Lc04PrivateIpv4.parse(address)) {
                "Only numeric RFC1918 IPv4 is accepted."
            }
            require(selectedRouteEligible() && originalTvQrOwner.hasSyntheticMxH1Proof(now())) {
                "Original TV QR, verified handshake and selected LAN required."
            }
            return open(ip, originalTvQrOwner, sourceRecordsAfterRealF6,
                now, selectedRouteEligible, false)
        }

        /** Host-only loopback for actual F6/EOF + APPDATA socket tests. */
        internal fun startLoopbackForHostTests(
            originalTvQrOwner: TvHpkeQrSession,
            sourceRecordsAfterRealF6: Lc04SourceAeadPort,
            now: () -> Long,
            selectedRouteEligible: () -> Boolean = { true },
        ): Lc04SourceFirstRequestTv {
            require(originalTvQrOwner.hasSyntheticMxH1Proof(now()))
            return open(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                originalTvQrOwner, sourceRecordsAfterRealF6,
                now, selectedRouteEligible, true)
        }

        private fun open(
            ip: InetAddress,
            owner: TvHpkeQrSession,
            records: Lc04SourceAeadPort,
            now: () -> Long,
            route: () -> Boolean,
            hostOnly: Boolean,
        ): Lc04SourceFirstRequestTv {
            val listener = ServerSocket(0, 1, ip)
            listener.soTimeout = 300
            return Lc04SourceFirstRequestTv(owner, records, listener,
                now, route, hostOnly)
        }
    }
}

/**
 * Phone source-first-request TCP proof. The caller supplies exactly the
 * selected Android Network.bindSocket(unconnected) action and its ACTIVE
 * Wi-Fi/Ethernet/default-route/API37 permission predicate, from the original
 * phone handshake. These callbacks are not themselves Android attestations.
 *
 * The first proof is terminal: a successful EMPTY reply demonstrates
 * F6-derived APPDATA key agreement over real TCP, but never grants catalog
 * privileges and cannot be reused to send credentials.
 */
class Lc04SourceFirstRequestPhone private constructor(
    private val endpoint: InetSocketAddress,
    private val records: Lc04SourceAeadPort,
    private val eligible: () -> Boolean,
    private val bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
    private val revokeOriginalPhoneOwner: () -> Unit,
    private val newUnconnectedSocket: () -> Socket,
) : AutoCloseable {
    private val consumed = AtomicBoolean(false)
    @Volatile private var stopped = false
    @Volatile private var active: Socket? = null

    fun proveAuthenticatedFirstRequest(): Boolean {
        if (!consumed.compareAndSet(false, true)) {
            close()
            return false
        }
        val socket = try { newUnconnectedSocket() }
            catch (_: RuntimeException) { close(); return false }
        if (!acquire(socket)) {
            runCatching { socket.close() }
            return false
        }
        return try {
            socket.use { peer ->
                if (!allowed() || peer.isBound || peer.isConnected) return false
                bindUnconnectedToSelectedNetwork(peer)
                if (!allowed() || peer.isConnected || peer.isClosed) return false
                peer.soTimeout = IO_TIMEOUT_MS
                peer.connect(endpoint, CONNECT_TIMEOUT_MS)
                if (!allowed()) return false

                // Cancel(1) is fixed by the protocol; no arbitrary provider
                // credentials or request data can flow from this class.
                val wire = records.sealRequest(Lc04SourceCommand.Cancel(1))
                    ?: return false
                try {
                    if (!allowed()) return false
                    Lc04SourceFirstFrameIo.write(
                        DataOutputStream(peer.getOutputStream()), wire,
                    )
                    peer.shutdownOutput()
                } finally { wire.fill(0) }
                if (!allowed()) return false

                val input = DataInputStream(peer.getInputStream())
                val response = Lc04SourceFirstFrameIo.read(input)
                try {
                    // Exact TCP EOF is required AFTER the authenticated
                    // TV reply; untrusted extra bytes never qualify as proof.
                    if (input.read() != -1 || !allowed()) return false
                    val reply = records.openReply(response)
                    reply?.sequence == 1L &&
                        reply.code == Lc04SourceReplyCode.EMPTY && allowed()
                } finally { response.fill(0) }
            }
        } catch (_: IOException) {
            false
        } catch (_: RuntimeException) {
            false
        } finally {
            close() // one-use source proof, revokes key owner on all paths
        }
    }

    private fun allowed(): Boolean =
        !stopped && runCatching { eligible() }.getOrDefault(false)

    @Synchronized private fun acquire(socket: Socket): Boolean {
        if (stopped || active != null) return false
        active = socket
        return true
    }

    @Synchronized override fun close() {
        if (stopped) return
        stopped = true
        runCatching { active?.close() }
        active = null
        records.close()
        runCatching { revokeOriginalPhoneOwner() }
    }

    override fun toString(): String =
        "Lc04SourceFirstRequestPhone([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        private const val IO_TIMEOUT_MS = 2_500
        private const val CONNECT_TIMEOUT_MS = 1_500

        fun forSelectedPrivateIpv4(
            ipLiteral: String,
            port: Int,
            sourceRecordsAfterRealTvEof: Lc04SourceAeadPort,
            selectedRouteEligible: () -> Boolean,
            bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
            revokeOriginalPhoneOwner: () -> Unit,
        ): Lc04SourceFirstRequestPhone = create(
            ipLiteral, port, sourceRecordsAfterRealTvEof,
            selectedRouteEligible, bindUnconnectedToSelectedNetwork,
            revokeOriginalPhoneOwner, ::Socket,
        )

        /** Host test redirects only the source APPDATA socket, never keys. */
        internal fun withTestSocketForHostOnly(
            ipLiteral: String,
            port: Int,
            sourceRecordsAfterRealTvEof: Lc04SourceAeadPort,
            selectedRouteEligible: () -> Boolean,
            bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
            revokeOriginalPhoneOwner: () -> Unit,
            testSocketFactory: () -> Socket,
        ): Lc04SourceFirstRequestPhone = create(
            ipLiteral, port, sourceRecordsAfterRealTvEof,
            selectedRouteEligible, bindUnconnectedToSelectedNetwork,
            revokeOriginalPhoneOwner, testSocketFactory,
        )

        private fun create(
            ip: String,
            port: Int,
            records: Lc04SourceAeadPort,
            eligible: () -> Boolean,
            bind: (Socket) -> Unit,
            revoke: () -> Unit,
            socketFactory: () -> Socket,
        ): Lc04SourceFirstRequestPhone {
            require(port in 1..65535)
            val privateIp = requireNotNull(Lc04PrivateIpv4.parse(ip)) {
                "Only numeric RFC1918 IPv4 is accepted."
            }
            return Lc04SourceFirstRequestPhone(
                InetSocketAddress(privateIp, port), records, eligible,
                bind, revoke, socketFactory,
            )
        }
    }
}

/** Fixed length-delimited AES-GCM records, with full header authenticated by MXA1. */
internal object Lc04SourceFirstFrameIo {
    private const val MIN_WIRE_BYTES = 94 // MXA1 header + Cancel(1)/reply + tag
    private const val MAX_WIRE_BYTES = 6_734 // MXA1 header + bounded request + tag

    fun read(input: DataInputStream): ByteArray {
        val size = input.readInt()
        if (size !in MIN_WIRE_BYTES..MAX_WIRE_BYTES)
            throw IOException("Invalid APPDATA record length.")
        return ByteArray(size).also { input.readFully(it) }
    }

    fun write(output: DataOutputStream, wire: ByteArray) {
        require(wire.size in MIN_WIRE_BYTES..MAX_WIRE_BYTES)
        output.writeInt(wire.size)
        output.write(wire)
        output.flush()
    }
}
