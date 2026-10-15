package app.muxtv.localcontrol

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Lifecycle/TV-owned credential boundary for future LAN transport.
 *
 * This class neither starts a socket nor grants access to source or credential stores.
 * Secret-bearing values must never be written to logs, saved navigation state or telemetry.
 * Every public mutation is serialized because phone requests and TV approval run on different threads.
 */
fun interface PairingClock {
    fun elapsedRealtimeMillis(): Long
}

enum class PairingCapability {
    SOURCE_TEST,
    SOURCE_CREATE,
}

class PairingPolicy(
    val invitationTtlMillis: Long = 120_000,
    val sessionLifetimeMillis: Long = 900_000,
    val sessionIdleMillis: Long = 300_000,
    val maxFailedProofs: Int = 5,
    val maxSessions: Int = 3,
) {
    init {
        require(invitationTtlMillis in 1_000..600_000)
        require(sessionLifetimeMillis in 1_000..3_600_000)
        require(sessionIdleMillis in 1_000..sessionLifetimeMillis)
        require(maxFailedProofs in 1..16)
        require(maxSessions in 1..8)
    }
}

/** The only cleartext pairing proof; hand directly to ephemeral QR presentation. */
class PairingInvitation(val secret: String, val lifetimeMillis: Long) {
    override fun toString(): String = "PairingInvitation(secret=[REDACTED])"
}

enum class PairingDenial {
    INVALID_OR_EXPIRED,
    REQUEST_ALREADY_PENDING,
    INVALID_CLIENT_LABEL,
    NOT_APPROVED,
    SESSION_LIMIT,
}

sealed interface PairingClaim {
    class AwaitingTvConfirmation(val claimId: String, val clientLabel: String) : PairingClaim
    class Denied(val reason: PairingDenial) : PairingClaim
}

/** The only cleartext session credential; deliver solely via a verified paired exchange. */
class PairingGrant(val sessionId: String, val credential: String, val capabilities: Set<PairingCapability>) {
    override fun toString(): String = "PairingGrant(sessionId=[REDACTED], credential=[REDACTED])"
}

/** TV-owned revocation/listing metadata. Contains no bearer secrets. */
class PairedDeviceSummary(val sessionId: String, val clientLabel: String, val createdAtMillis: Long)

/** Exposed only to TV UI, never an HTTP endpoint. */
class PendingTvPairing(val claimId: String, val clientLabel: String)

sealed interface PairingRedemption {
    class Granted(val grant: PairingGrant) : PairingRedemption
    class Denied(val reason: PairingDenial) : PairingRedemption
}

class PairingAuthority(
    private val clock: PairingClock = PairingClock { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
    private val policy: PairingPolicy = PairingPolicy(),
) {
    private class InvitationState(
        val digest: ByteArray,
        val createdAt: Long,
        var failures: Int = 0,
        var claimId: String? = null,
        var clientLabel: String? = null,
        var approved: Boolean = false,
    ) {
        fun erase() = digest.fill(0)
    }

    private class SessionState(
        val digest: ByteArray,
        val createdAt: Long,
        var lastUsedAt: Long,
        val clientLabel: String,
        val capabilities: Set<PairingCapability>,
    ) {
        fun erase() = digest.fill(0)
    }

    private var pending: InvitationState? = null
    private val sessions = mutableMapOf<String, SessionState>()
    private val setupCapabilities = setOf(PairingCapability.SOURCE_TEST, PairingCapability.SOURCE_CREATE)

    @Synchronized
    fun begin(): PairingInvitation {
        val now = clock.elapsedRealtimeMillis()
        prune(now)
        check(sessions.size < policy.maxSessions) { "Local control session capacity reached." }
        cancelPending()
        val proof = newSecret(32)
        pending = InvitationState(digest(proof), now)
        return PairingInvitation(proof, policy.invitationTtlMillis)
    }

    /** Phone presents the full one-time proof; an opaque claim ID alone is not authorization. */
    @Synchronized
    fun claim(proof: String, proposedLabel: String): PairingClaim {
        val now = clock.elapsedRealtimeMillis()
        prune(now)
        val invitation = pending ?: return PairingClaim.Denied(PairingDenial.INVALID_OR_EXPIRED)
        val label = proposedLabel.trim()
        if (label.isEmpty() || label.length > 48 || label.any { it.isISOControl() }) {
            return PairingClaim.Denied(PairingDenial.INVALID_CLIENT_LABEL)
        }
        if (invitation.claimId != null) {
            return PairingClaim.Denied(PairingDenial.REQUEST_ALREADY_PENDING)
        }
        if (!verifyProof(invitation, proof)) {
            failProof(invitation)
            return PairingClaim.Denied(PairingDenial.INVALID_OR_EXPIRED)
        }
        val id = newSecret(16)
        invitation.claimId = id
        invitation.clientLabel = label
        return PairingClaim.AwaitingTvConfirmation(id, label)
    }

    /** Local TV can render/confirm the pending label without receiving the one-time proof. */
    @Synchronized
    fun pendingClaimForTv(): PendingTvPairing? {
        prune(clock.elapsedRealtimeMillis())
        val invitation = pending ?: return null
        val id = invitation.claimId ?: return null
        val label = invitation.clientLabel ?: return null
        return PendingTvPairing(id, label)
    }

    @Synchronized
    fun deny(claimId: String): Boolean {
        prune(clock.elapsedRealtimeMillis())
        if (pending?.claimId != claimId || claimId.isEmpty()) return false
        cancelPending()
        return true
    }

    /** TV confirmation requires an ID obtained from the pending claim, not a phone-side flag. */
    @Synchronized
    fun approve(claimId: String): Boolean {
        prune(clock.elapsedRealtimeMillis())
        val invitation = pending ?: return false
        if (invitation.claimId != claimId || claimId.isEmpty()) return false
        invitation.approved = true
        return true
    }

    /**
     * Phone must prove possession AGAIN after TV approval. Neither claim ID nor TV approval
     * alone can retrieve a bearer credential.
     */
    @Synchronized
    fun redeem(claimId: String, proof: String): PairingRedemption {
        val now = clock.elapsedRealtimeMillis()
        prune(now)
        val invitation = pending ?: return PairingRedemption.Denied(PairingDenial.INVALID_OR_EXPIRED)
        if (invitation.claimId != claimId || claimId.isEmpty()) {
            return PairingRedemption.Denied(PairingDenial.INVALID_OR_EXPIRED)
        }
        if (!verifyProof(invitation, proof)) {
            failProof(invitation)
            return PairingRedemption.Denied(PairingDenial.INVALID_OR_EXPIRED)
        }
        if (!invitation.approved) return PairingRedemption.Denied(PairingDenial.NOT_APPROVED)
        if (sessions.size >= policy.maxSessions) {
            return PairingRedemption.Denied(PairingDenial.SESSION_LIMIT)
        }
        val id = newSecret(16)
        val credential = newSecret(32)
        sessions[id] = SessionState(
            digest(credential), now, now,
            checkNotNull(invitation.clientLabel), setupCapabilities,
        )
        cancelPending()
        return PairingRedemption.Granted(PairingGrant(id, credential, setupCapabilities))
    }

    /** Capability check is mandatory on EVERY handler, never just on pairing. */
    @Synchronized
    fun authorize(sessionId: String, credential: String, capability: PairingCapability): Boolean {
        val now = clock.elapsedRealtimeMillis()
        prune(now)
        val session = sessions[sessionId] ?: return false
        if (capability !in session.capabilities || !isProofFormat(credential)) return false
        if (!MessageDigest.isEqual(session.digest, digest(credential))) return false
        session.lastUsedAt = now
        return true
    }

    @Synchronized
    fun revoke(sessionId: String): Boolean {
        val state = sessions.remove(sessionId) ?: return false
        state.erase()
        return true
    }

    /** Closing the TV activation window invalidates unused proofs; stopping server revokes sessions. */
    @Synchronized
    fun cancelPending() {
        pending?.erase()
        pending = null
    }

    @Synchronized
    fun stop() {
        cancelPending()
        sessions.values.forEach { it.erase() }
        sessions.clear()
    }

    /** TV-local only: never return this inventory from an unauthenticated HTTP route. */
    @Synchronized
    fun pairedDevices(): List<PairedDeviceSummary> {
        prune(clock.elapsedRealtimeMillis())
        return sessions.map { (id, state) ->
            PairedDeviceSummary(id, state.clientLabel, state.createdAt)
        }
    }

    @Synchronized
    fun activeSessionCount(): Int {
        prune(clock.elapsedRealtimeMillis())
        return sessions.size
    }

    private fun prune(now: Long) {
        val invitation = pending
        if (invitation != null && expired(now, invitation.createdAt, policy.invitationTtlMillis)) {
            cancelPending()
        }
        val iterator = sessions.values.iterator()
        while (iterator.hasNext()) {
            val session = iterator.next()
            if (expired(now, session.createdAt, policy.sessionLifetimeMillis) ||
                expired(now, session.lastUsedAt, policy.sessionIdleMillis)
            ) {
                session.erase()
                iterator.remove()
            }
        }
    }

    private fun expired(now: Long, since: Long, ttl: Long): Boolean =
        now < since || now - since >= ttl

    private fun failProof(invitation: InvitationState) {
        invitation.failures += 1
        if (invitation.failures >= policy.maxFailedProofs) cancelPending()
    }

    private fun verifyProof(invitation: InvitationState, proof: String): Boolean =
        isProofFormat(proof) && MessageDigest.isEqual(invitation.digest, digest(proof))

    private fun isProofFormat(proof: String): Boolean =
        proof.length == 64 && proof.all { it in '0'..'9' || it in 'a'..'f' }

    private fun digest(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.US_ASCII))

    private fun newSecret(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        val alphabet = "0123456789abcdef"
        val chars = CharArray(byteCount * 2)
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[2 * index] = alphabet[value ushr 4]
            chars[2 * index + 1] = alphabet[value and 15]
        }
        bytes.fill(0)
        return String(chars)
    }
}
