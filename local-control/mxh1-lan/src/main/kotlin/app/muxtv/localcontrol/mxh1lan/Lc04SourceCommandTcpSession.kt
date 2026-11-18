package app.muxtv.localcontrol.mxh1lan

import app.muxtv.localcontrol.nativetransport.Lc04PrivateIpv4
import app.muxtv.localcontrol.onboarding.Lc04TvOriginalAeadCommandOwner
import app.muxtv.localcontrol.onboarding.ProtectedSourceStaging
import app.muxtv.localcontrol.onboarding.SourceStagingResult
import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking

/**
 * S2J3D2B2: real one-peer APPDATA TCP SOURCE COMMAND session, NOT the terminal
 * Cancel(1) proof endpoint. No HTTP, bearer, arbitrary plaintext handler or
 * independent TV-owned staging pipeline. All provider fields are sent ONLY
 * inside original signed-QR/F6-bound MXA1 records and authenticated BEFORE
 * the TV-owned source dispatcher can touch a catalog port.
 *
 * A selected Wi-Fi/Ethernet binding, Android 17 LAN permission, visibility
 * and network-change revocation remain obligations of the Android lifecycle
 * owner, never inferred from a private IPv4 address alone.
 */
class Lc04SourceCommandTcpTv private constructor(
    private val bridge: Lc04TvOriginalAeadCommandOwner,
    private val listener: ServerSocket,
    private val now: () -> Long,
    private val selectedRouteEligible: () -> Boolean,
    private val isTvVisible: () -> Boolean,
    private val hostOnlyLoopback: Boolean,
) : AutoCloseable {
    private val started = now()
    private val stopped = AtomicBoolean(false)
    @Volatile private var activePeer: Socket? = null
    @Volatile private var cleanupResult: SourceStagingResult? = null
    private val worker = Thread({ serveOnce() }, "muxtv-lc04-source-appdata-tv").apply {
        isDaemon = true
        start()
    }

    val port: Int get() = listener.localPort

    /** TV UI only: never expose this action on LAN or to phone commands. */
    fun pendingActivationNameForTv(): String? =
        if (active()) bridge.pendingActivationNameForTv() else null

    /** TV UI must invoke only from its own visible D-pad confirmation. */
    fun approvePendingActivationOnTv(): Boolean =
        active() && bridge.approvePendingActivationOnTv()

    /** Diagnostic with no source names or secrets. */
    fun cleanupStatusAfterStop(): SourceStagingResult? = cleanupResult

    private fun active(): Boolean = !stopped.get() && runCatching {
        val t = now()
        t >= started && t - started < SESSION_TTL_MS &&
            selectedRouteEligible() && isTvVisible()
    }.getOrDefault(false)

    private fun serveOnce() {
        try {
            while (active()) {
                val socket = try {
                    listener.accept()
                } catch (_: SocketTimeoutException) {
                    if (now() - started >= ACCEPT_TTL_MS) break
                    continue
                } catch (_: IOException) {
                    break
                }
                // Only one connection, including a hostile first peer.
                runCatching { listener.close() }
                if (!acquire(socket)) {
                    runCatching { socket.close() }
                    break
                }
                try {
                    socket.use { peer ->
                        peer.soTimeout = IO_TIMEOUT_MS
                        if (!hostOnlyLoopback &&
                            Lc04PrivateIpv4.parse(peer.inetAddress.hostAddress) == null
                        ) return@use
                        if (!active()) return@use

                        val input = DataInputStream(peer.getInputStream())
                        val output = DataOutputStream(peer.getOutputStream())
                        var count = 0
                        while (active() && count < MAX_COMMANDS) {
                            val request = try {
                                Lc04SourceFirstFrameIo.read(input)
                            } catch (_: EOFException) {
                                // Normal phone output half-close also arrives as
                                // EOF; incomplete frames remain terminal and
                                // are never dispatched to TV-owned staging.
                                break
                            }
                            val response = try {
                                if (active()) runBlocking {
                                    bridge.acceptAuthenticatedRecord(request)
                                } else null
                            } finally { request.fill(0) }
                            if (response == null) break
                            try {
                                if (!active()) break
                                Lc04SourceFirstFrameIo.write(output, response)
                            } finally { response.fill(0) }
                            count++
                        }
                        if (active()) {
                            // No follow-up requests or re-connections once the
                            // session ends; the phone gets exact TCP EOF.
                            peer.shutdownOutput()
                        }
                    }
                } catch (_: IOException) {
                    // Network failure: fail closed. Never auto-retry Activate.
                } catch (_: RuntimeException) {
                    // Includes failure to dispatch an already consumed record.
                } finally {
                    release(socket)
                }
                break
            }
        } finally {
            close()
            // Required lifecycle cleanup after synchronous QR/AEAD revocation.
            // Runs outside all transport locks, never on Android UI thread.
            cleanupResult = try {
                runBlocking { bridge.discardPreparedAfterTvStop() }
            } catch (_: Exception) {
                SourceStagingResult.CLEANUP_PENDING
            }
        }
    }

    @Synchronized private fun acquire(peer: Socket): Boolean {
        if (stopped.get() || activePeer != null) return false
        activePeer = peer
        return true
    }

    @Synchronized private fun release(peer: Socket) {
        if (activePeer === peer) activePeer = null
    }

    /** Synchronous from TV Activity.onStop, route loss or permission revoke. */
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { listener.close() }
        runCatching { activePeer?.close() }
        bridge.close()
    }

    /** TV lifecycle coroutine may await cleanup after invoking close(). */
    suspend fun discardPreparedAfterTvStop(): SourceStagingResult {
        close()
        return bridge.discardPreparedAfterTvStop()
    }

    override fun toString(): String = "Lc04SourceCommandTcpTv([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        private const val ACCEPT_TTL_MS = 30_000L
        private const val SESSION_TTL_MS = 120_000L
        private const val IO_TIMEOUT_MS = 30_000
        private const val MAX_COMMANDS = 16

        /**
         * Production constructor is *tied to a real completed MXH1 F6 write
         * and TCP output shutdown*. The port cannot come from a fixture.
         * startAfter... must run on an eligible selected, visible TV network.
         */
        fun startAfterCompletedF6OnApprovedPrivateIpv4(
            address: String,
            completedHandshake: Lc04MxH1SealedPrivateLanTv,
            originalTvQrOwner: TvHpkeQrSession,
            staging: ProtectedSourceStaging,
            now: () -> Long,
            selectedRouteEligible: () -> Boolean,
            isTvVisible: () -> Boolean,
        ): Lc04SourceCommandTcpTv {
            val ip = requireNotNull(Lc04PrivateIpv4.parse(address)) {
                "APPDATA requires numeric RFC1918 TV interface."
            }
            require(selectedRouteEligible() && isTvVisible())
            val sourcePort = requireNotNull(
                completedHandshake.takeSourceKeyPortAfterRealF6OutputClose(),
            ) { "Real authenticated F6 and socket output close required." }
            return open(ip, originalTvQrOwner, sourcePort, staging, now,
                selectedRouteEligible, isTvVisible, false)
        }

        /** Host only: same owner and real handshake, but actual loopback TCP. */
        internal fun startLoopbackAfterCompletedF6ForHostTests(
            completedHandshake: Lc04MxH1SealedPrivateLanTv,
            originalTvQrOwner: TvHpkeQrSession,
            staging: ProtectedSourceStaging,
            now: () -> Long,
            selectedRouteEligible: () -> Boolean,
            isTvVisible: () -> Boolean,
        ): Lc04SourceCommandTcpTv {
            val port = requireNotNull(
                completedHandshake.takeSourceKeyPortAfterRealF6OutputClose(),
            ) { "No completed real F6 TCP output close." }
            return open(
                InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
                originalTvQrOwner, port, staging, now,
                selectedRouteEligible, isTvVisible, true,
            )
        }

        private fun open(
            ip: InetAddress,
            qr: TvHpkeQrSession,
            port: Lc04SourceAeadPort,
            staging: ProtectedSourceStaging,
            now: () -> Long,
            eligible: () -> Boolean,
            visible: () -> Boolean,
            hostOnly: Boolean,
        ): Lc04SourceCommandTcpTv {
            try {
                val bridge = Lc04TvOriginalAeadCommandOwner
                    .fromOriginalTvRecordsAfterRealF6(
                        qr, port, staging, now, eligible, visible,
                    )
                try {
                    val listener = ServerSocket(0, 1, ip)
                    listener.soTimeout = 250
                    return Lc04SourceCommandTcpTv(
                        bridge, listener, now, eligible, visible, hostOnly,
                    )
                } catch (failure: Exception) {
                    bridge.close()
                    throw failure
                }
            } catch (failure: Exception) {
                port.close()
                qr.close()
                throw failure
            }
        }
    }
}

/**
 * One live selected-Network phone TCP socket, bound BEFORE connect.
 * Phone can only emit the four bounded source commands; the encrypted TV
 * reply must authenticate under the identical original F6-derived owner.
 * All null/failed/unknown responses are terminal; never auto-retry Activate.
 */
class Lc04SourceCommandTcpPhone private constructor(
    private val endpoint: InetSocketAddress,
    private val records: Lc04SourceAeadPort,
    private val selectedRouteEligible: () -> Boolean,
    private val bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
    private val revokeOriginalPhoneOwner: () -> Unit,
    private val newUnconnectedSocket: () -> Socket,
) : AutoCloseable {
    private val stopped = AtomicBoolean(false)
    private val sendLock = Any()
    @Volatile private var socket: Socket? = null
    private var nextSequence = 1L

    fun prepareM3u(locator: String): Lc04SourceReplyCode? =
        send { Lc04SourceCommand.PrepareM3u(it, locator) }

    fun prepareXtream(
        endpoint: String, username: String, password: String,
    ): Lc04SourceReplyCode? =
        send { Lc04SourceCommand.PrepareXtream(it, endpoint, username, password) }

    fun requestActivate(sourceName: String): Lc04SourceReplyCode? =
        send { Lc04SourceCommand.Activate(it, sourceName) }

    fun cancel(): Lc04SourceReplyCode? =
        send { Lc04SourceCommand.Cancel(it) }

    private fun eligible(): Boolean = !stopped.get() &&
        runCatching { selectedRouteEligible() }.getOrDefault(false)

    private fun send(build: (Long) -> Lc04SourceCommand): Lc04SourceReplyCode? =
        synchronized(sendLock) {
            if (!eligible() || nextSequence > MAX_COMMANDS) {
                close()
                return@synchronized null
            }
            try {
                val peer = socket ?: connectSelected() ?: run {
                    close()
                    return@synchronized null
                }
                if (!eligible()) {
                    close()
                    return@synchronized null
                }
                val seq = nextSequence
                val wire = records.sealRequest(build(seq)) ?: run {
                    close()
                    return@synchronized null
                }
                nextSequence++
                try {
                    if (!eligible()) {
                        close()
                        return@synchronized null
                    }
                    Lc04SourceFirstFrameIo.write(
                        DataOutputStream(peer.getOutputStream()), wire,
                    )
                } finally { wire.fill(0) }

                val response = Lc04SourceFirstFrameIo.read(
                    DataInputStream(peer.getInputStream()),
                )
                val code = try {
                    if (!eligible()) null else
                        records.openReply(response)
                            ?.takeIf { it.sequence == seq }?.code
                } finally { response.fill(0) }
                if (code == null || !eligible()) {
                    close()
                    null
                } else {
                    if (nextSequence > MAX_COMMANDS ||
                        code == Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN ||
                        code == Lc04SourceReplyCode.ACCESS_DENIED
                    ) close()
                    code
                }
            } catch (_: IOException) {
                close()
                null
            } catch (_: RuntimeException) {
                close()
                null
            }
        }

    private fun connectSelected(): Socket? {
        val peer = newUnconnectedSocket()
        socket = peer // onStop can now close even a stalled connect/read
        if (!eligible() || peer.isConnected || peer.isBound) return null
        bindUnconnectedToSelectedNetwork(peer)
        if (!eligible() || peer.isClosed || peer.isConnected) return null
        peer.soTimeout = IO_TIMEOUT_MS
        peer.connect(endpoint, CONNECT_TIMEOUT_MS)
        return peer.takeIf { eligible() }
    }

    /**
     * Synchronous revoke, independent of sendLock (a blocking network read
     * MUST NOT block Activity.onStop waiting on the request mutex).
     */
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        records.close()
        runCatching { revokeOriginalPhoneOwner() }
    }

    override fun toString(): String =
        "Lc04SourceCommandTcpPhone([PRIVATE_ADDRESS_REDACTED])"

    companion object {
        private const val IO_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val MAX_COMMANDS = 16L

        /**
         * Production: handshake completeSourceKeyHandoffAfterTvEof is the
         * ONLY source of phone app records; this enforces authentic F6/EOF
         * on the ORIGINAL selected-Network socket.
         */
        fun afterVerifiedTvEofOnSelectedNetwork(
            address: String,
            appdataPort: Int,
            completedHandshake: Lc04MxH1LeasedSealedPhone,
            selectedRouteEligible: () -> Boolean,
            bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
        ): Lc04SourceCommandTcpPhone? {
            val ip = requireNotNull(Lc04PrivateIpv4.parse(address)) {
                "Only numeric RFC1918 APPDATA TV endpoint permitted."
            }
            require(appdataPort in 1..65535)
            val records = completedHandshake.completeSourceKeyHandoffAfterTvEof()
                ?: return null
            return Lc04SourceCommandTcpPhone(
                InetSocketAddress(ip, appdataPort),
                records, selectedRouteEligible, bindUnconnectedToSelectedNetwork,
                completedHandshake::close, ::Socket,
            )
        }

        /** Host-only: separate original-QR F6/EOF fixture socket and key port. */
        internal fun withTestSocketForHostOnly(
            address: String,
            appdataPort: Int,
            sourceRecordsAfterRealTvEof: Lc04SourceAeadPort,
            selectedRouteEligible: () -> Boolean,
            bindUnconnectedToSelectedNetwork: (Socket) -> Unit,
            revokeOriginalPhoneOwner: () -> Unit,
            testSocketFactory: () -> Socket,
        ): Lc04SourceCommandTcpPhone {
            val ip = requireNotNull(Lc04PrivateIpv4.parse(address))
            require(appdataPort in 1..65535)
            return Lc04SourceCommandTcpPhone(
                InetSocketAddress(ip, appdataPort),
                sourceRecordsAfterRealTvEof,
                selectedRouteEligible, bindUnconnectedToSelectedNetwork,
                revokeOriginalPhoneOwner, testSocketFactory,
            )
        }
    }
}
