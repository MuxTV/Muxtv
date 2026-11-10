package app.muxtv.localcontrol.onboarding

import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.localcontrol.protectedchannel.Lc04SourceCommand
import app.muxtv.localcontrol.protectedchannel.Lc04SourceCommandCodec
import app.muxtv.localcontrol.protectedchannel.Lc04SourceReply
import app.muxtv.localcontrol.protectedchannel.Lc04SourceReplyCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TV-side SOURCE COMMAND boundary, NOT an authorization provider or a raw
 * network endpoint. Intentionally internal and NOT wired to /pair or to the
 * six-frame synthetic MXR1 probe. No one can construct an authorized source
 * session merely by completing MXH1's synthetic Boolean proof.
 *
 * A separately security-reviewed AEAD transport must authenticate the
 * original TV-approved principal, exclusively own fresh application traffic
 * keys, verify record direction/counter/binding before passing the decrypted
 * bytes here and encrypt the returned secret-free status. Never pass raw TCP
 * bytes to this class. Only a trusted TV D-pad consent callback can authorize
 * ACTIVATE; the default is deny.
 */
internal class Lc04TvSourceCommandSession(
    private val principal: ProtectedSourcePrincipal,
    private val staging: ProtectedSourceStaging,
    private val clock: () -> Long,
    private val consumeTvActivationApproval: () -> Boolean = { false },
) : AutoCloseable {
    private val mutex = Mutex()
    private val bornAt: Long = clock()
    private var lastSeen = bornAt
    private var nextSequence = 1L
    @Volatile private var stopped = false

    init { require(bornAt >= 0 && bornAt <= Long.MAX_VALUE - TTL_MS) }

    /**
     * Strict one-use sequence, max 16 commands and a monotonic 120-second
     * lifetime. The sequence is ALSO required to be authenticated inside
     * the enclosing AEAD record; this plaintext parser does NOT provide it.
     * Malformed/out-of-order/replayed plaintext is terminal.
     */
    suspend fun acceptAuthenticatedPlaintext(record: ByteArray): ByteArray? =
        mutex.withLock {
            if (!alive() || !principal.canWrite()) {
                close()
                return@withLock null
            }
            val command = Lc04SourceCommandCodec.decodeRequest(record)
            if (command == null || command.sequence != nextSequence ||
                nextSequence > MAX_COMMANDS
            ) {
                close()
                return@withLock null
            }
            // Consume before potentially durable staging operations: a retry
            // of any frame can never invoke SourceOnboarding twice.
            nextSequence++
            val status = try {
                when (command) {
                    is Lc04SourceCommand.PrepareM3u -> staging.prepare(
                        principal,
                        SourcePreparationRequest.M3u(
                            locator = command.locator,
                            insecureHttpApproved = false,
                        ),
                    ).toWireCode()
                    is Lc04SourceCommand.PrepareXtream -> staging.prepare(
                        principal,
                        SourcePreparationRequest.Xtream(
                            endpoint = command.endpoint,
                            username = command.username,
                            password = command.password,
                            insecureHttpApproved = false,
                        ),
                    ).toWireCode()
                    is Lc04SourceCommand.Activate -> {
                        // The phone can request activation but cannot approve
                        // itself. An actual TV-local gesture must be consumed.
                        if (!consumeTvActivationApproval())
                            Lc04SourceReplyCode.TV_CONFIRMATION_REQUIRED
                        else staging.activate(principal, command.sourceName).toWireCode()
                    }
                    is Lc04SourceCommand.Cancel -> staging.cancel(principal).toWireCode()
                }
            } catch (cancelled: CancellationException) {
                close()
                throw cancelled
            } catch (_: Exception) {
                // Never tell a phone to retry an activation whose durable
                // outcome may be unknown; source state is TV-owned.
                if (command is Lc04SourceCommand.Activate)
                    Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN
                else Lc04SourceReplyCode.FAILED
            }

            // Owner's onStop/revoke can race with the suspended backend. A
            // late successful activation must not be reported as safe.
            val safeStatus = if (stopped || !principal.canWrite()) {
                close()
                if (command is Lc04SourceCommand.Activate)
                    Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN
                else Lc04SourceReplyCode.ACCESS_DENIED
            } else status
            if (safeStatus == Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN ||
                safeStatus == Lc04SourceReplyCode.ACCESS_DENIED
            ) close()
            // No URL, password, source handle or provider detail in response.
            Lc04SourceCommandCodec.encodeReply(
                Lc04SourceReply(command.sequence, safeStatus),
            )
        }

    private fun alive(): Boolean {
        if (stopped || nextSequence > MAX_COMMANDS) return false
        val now = clock()
        if (now < bornAt || now < lastSeen || now - bornAt >= TTL_MS) return false
        lastSeen = now
        return true
    }

    /** Stop can be called concurrently with a suspended SourceOnboarding. */
    override fun close() { stopped = true }

    /**
     * TV lifecycle cleanup only: authority MUST have been revoked before
     * calling this; otherwise a still-active session may be used elsewhere.
     * No phone-facing API exposes this method or the opaque handle.
     */
    suspend fun discardAfterOwnerRevocation(): SourceStagingResult {
        close()
        require(!principal.canWrite()) { "Revoke the TV-owned session before cleanup." }
        return staging.discardRevoked(principal.sessionId)
    }

    override fun toString(): String = "Lc04TvSourceCommandSession([REDACTED])"

    companion object {
        private const val MAX_COMMANDS = 16L
        private const val TTL_MS = 120_000L
    }
}

private fun SourceStagingResult.toWireCode(): Lc04SourceReplyCode = when (this) {
    SourceStagingResult.PREPARED -> Lc04SourceReplyCode.PREPARED
    SourceStagingResult.ACTIVATED -> Lc04SourceReplyCode.ACTIVATED
    SourceStagingResult.CANCELED -> Lc04SourceReplyCode.CANCELED
    SourceStagingResult.EMPTY -> Lc04SourceReplyCode.EMPTY
    SourceStagingResult.ACCESS_DENIED -> Lc04SourceReplyCode.ACCESS_DENIED
    SourceStagingResult.INVALID_INPUT -> Lc04SourceReplyCode.INVALID_INPUT
    SourceStagingResult.ALREADY_PREPARED -> Lc04SourceReplyCode.ALREADY_PREPARED
    SourceStagingResult.LOCAL_NETWORK_PERMISSION_REQUIRED ->
        Lc04SourceReplyCode.LOCAL_NETWORK_PERMISSION_REQUIRED
    SourceStagingResult.TV_HTTP_APPROVAL_REQUIRED ->
        Lc04SourceReplyCode.TV_HTTP_APPROVAL_REQUIRED
    SourceStagingResult.CLEANUP_PENDING -> Lc04SourceReplyCode.CLEANUP_PENDING
    SourceStagingResult.ACTIVATION_OUTCOME_UNKNOWN ->
        Lc04SourceReplyCode.ACTIVATION_OUTCOME_UNKNOWN
    SourceStagingResult.FAILED -> Lc04SourceReplyCode.FAILED
}
