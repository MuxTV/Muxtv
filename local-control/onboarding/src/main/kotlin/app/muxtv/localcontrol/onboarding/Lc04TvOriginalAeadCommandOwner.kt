package app.muxtv.localcontrol.onboarding

import app.muxtv.localcontrol.sourceaead.Lc04SourceAeadPort
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommand
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceCommandCodec
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReply
import app.muxtv.localcontrol.sourcecommandwire.Lc04SourceReplyCode
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * S2J3D2B1: TV-owned bridge from an ORIGINAL QR/F6-derived authenticated
 * AEAD port into the EXISTING SourceOnboarding staging and command dispatcher.
 *
 * This class does not own a socket and must NEVER be callable by old /pair:
 * the installed TV composition root must supply the exact one-use port taken
 * from the real six-frame TV kernel AFTER writing F6 + shutdownOutput().
 * Every incoming MXA1 record is authenticated before the bounded command
 * reaches SourceOnboarding. No legacy HTTP pairing bearer is minted.
 *
 * A local TV D-pad gesture is independently required for ACTIVATE and must
 * apply to the exact pending name/next sequence; no phone-supplied "approve"
 * flag is accepted. Neither raw keys nor provider details cross the reply.
 */
class Lc04TvOriginalAeadCommandOwner private constructor(
    private val originalTv: TvHpkeQrSession,
    private val records: Lc04SourceAeadPort,
    private val staging: ProtectedSourceStaging,
    private val now: () -> Long,
    private val routeEligible: () -> Boolean,
    private val isTvVisible: () -> Boolean,
) : AutoCloseable {
    private data class PendingActivation(
        val sequence: Long,
        val name: String,
        val issuedAt: Long,
        var approvedOnTv: Boolean = false,
    )

    private val bornAt = now()
    private val dispatchMutex = Mutex()
    private val consentLock = Any()
    private val tvSessionId: String = ByteArray(16).let { bytes ->
        SecureRandom().nextBytes(bytes)
        try {
            bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } finally { bytes.fill(0) }
    }
    @Volatile private var stopped = false
    private var session: Lc04TvSourceCommandSession? = null
    private var activationUnderDispatch: Lc04SourceCommand.Activate? = null
    private var pending: PendingActivation? = null

    init {
        require(bornAt >= 0 && bornAt <= Long.MAX_VALUE - TTL_MS)
    }

    private fun live(): Boolean = !stopped && try {
        val current = now()
        current >= bornAt && current - bornAt < TTL_MS &&
            routeEligible() && originalTv.ownsOriginalSourceRecords(records, current)
    } catch (_: RuntimeException) { false }

    /** Title only, for trusted TV-visible confirmation UI (never a phone endpoint). */
    fun pendingActivationNameForTv(): String? =
        synchronized(consentLock) {
            pending?.takeIf { !stopped && live() && now() >= it.issuedAt &&
                now() - it.issuedAt < APPROVAL_TTL_MS }?.name
        }

    /**
     * Only a trusted visible TV D-pad handler may invoke this method.
     * Approval is single-use and bound to the pending name and EXACT next
     * command sequence, never a reusable "allow sources" session flag.
     */
    fun approvePendingActivationOnTv(): Boolean =
        synchronized(consentLock) {
            val proposed = pending ?: return@synchronized false
            if (proposed.approvedOnTv || !live() ||
                !runCatching { isTvVisible() }.getOrDefault(false)
            ) return@synchronized false
            val time = now()
            if (time < proposed.issuedAt ||
                time - proposed.issuedAt >= APPROVAL_TTL_MS
            ) return@synchronized false
            proposed.approvedOnTv = true
            true
        }

    private fun consumeTvApproval(): Boolean = synchronized(consentLock) {
        val command = activationUnderDispatch ?: return@synchronized false
        val proposed = pending ?: return@synchronized false
        val time = now()
        val permitted = proposed.approvedOnTv &&
            time >= proposed.issuedAt &&
            time - proposed.issuedAt < APPROVAL_TTL_MS &&
            command.sequence == proposed.sequence + 1L &&
            command.sourceName == proposed.name &&
            runCatching { isTvVisible() }.getOrDefault(false)
        // Any attempt consumes the pending TV window; a different command
        // cannot inherit an old D-pad click.
        pending = null
        permitted
    }

    /**
     * Must receive ONLY an entire length-checked MXA1 record from the
     * selected-Network socket, never HTTP /pair bytes or plaintext.
     * The TV creates its in-memory SourcePrincipal lazily *after*
     * authenticating the first source frame under original F6-bound keys.
     */
    suspend fun acceptAuthenticatedRecord(wire: ByteArray): ByteArray? =
        dispatchMutex.withLock {
            if (!live()) {
                close()
                return@withLock null
            }
            val command = records.openRequest(wire) ?: run {
                close()
                return@withLock null
            }
            if (!live()) {
                close()
                return@withLock null
            }

            val handler = session ?: run {
                val principal = ProtectedSourcePrincipal.fromOriginalTvAeadOwner(
                    tvSessionId, ::live,
                )
                Lc04TvSourceCommandSession(
                    principal = principal,
                    staging = staging,
                    clock = now,
                    consumeTvActivationApproval = ::consumeTvApproval,
                ).also { session = it }
            }

            val plain = Lc04SourceCommandCodec.encodeRequest(command) ?: run {
                close()
                return@withLock null
            }
            val response: ByteArray?
            try {
                synchronized(consentLock) {
                    activationUnderDispatch = command as? Lc04SourceCommand.Activate
                    // Any non-ACTIVATE command invalidates pending consent.
                    if (command !is Lc04SourceCommand.Activate) pending = null
                }
                response = handler.acceptAuthenticatedPlaintext(plain)
            } catch (cancel: CancellationException) {
                close()
                throw cancel
            } catch (_: Exception) {
                close()
                return@withLock null
            } finally {
                synchronized(consentLock) { activationUnderDispatch = null }
                plain.fill(0)
            }
            val reply = response?.let(Lc04SourceCommandCodec::decodeReply)
                ?: run { close(); return@withLock null }
            response?.fill(0)
            if (command is Lc04SourceCommand.Activate) {
                synchronized(consentLock) {
                    if (reply.code == Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED) {
                        pending = PendingActivation(command.sequence, command.sourceName, now())
                    } else pending = null
                }
            }
            if (!live()) {
                // An ACTIVATE may have committed while onStop raced; the
                // caller must treat missing reply as outcome UNKNOWN.
                close()
                return@withLock null
            }
            val encrypted = records.sealReply(
                Lc04SourceReply(command.sequence, reply.code),
            ) ?: run { close(); return@withLock null }
            if (!live()) {
                encrypted.fill(0)
                close()
                return@withLock null
            }
            encrypted
        }

    /** Immediate synchronous key/QR revocation, safe from TV onStop. */
    override fun close() {
        if (stopped) return
        stopped = true
        synchronized(consentLock) {
            pending = null
            activationUnderDispatch = null
        }
        session?.close()
        records.close()
        originalTv.close()
    }

    /**
     * TV lifecycle coroutine: call after close() onStop/revoke. Prepared
     * handles are cleaned only after TV authority is gone; activation commit
     * ambiguity remains terminal and must never be silently retried.
     */
    suspend fun discardPreparedAfterTvStop(): SourceStagingResult {
        close()
        return session?.discardAfterOwnerRevocation() ?: SourceStagingResult.EMPTY
    }

    override fun toString(): String = "Lc04TvOriginalAeadCommandOwner([REDACTED])"

    companion object {
        private const val TTL_MS = 120_000L
        private const val APPROVAL_TTL_MS = 15_000L

        /**
         * TV-only construction. The ORIGINAL TV QR must prove that THIS
         * identical key port belongs to its signed/approved F6 handoff.
         * A fixture-derived port or a bare MXH1 diagnostic Boolean fails.
         */
        fun fromOriginalTvRecordsAfterRealF6(
            originalTv: TvHpkeQrSession,
            sourceRecords: Lc04SourceAeadPort,
            staging: ProtectedSourceStaging,
            clock: () -> Long,
            selectedRouteEligible: () -> Boolean,
            isTvVisible: () -> Boolean,
        ): Lc04TvOriginalAeadCommandOwner {
            require(selectedRouteEligible() &&
                originalTv.ownsOriginalSourceRecords(sourceRecords, clock())) {
                "An original verified TV QR-owned F6 APPDATA port is required."
            }
            return Lc04TvOriginalAeadCommandOwner(
                originalTv, sourceRecords, staging, clock,
                selectedRouteEligible, isTvVisible,
            )
        }
    }
}
