package app.muxtv.localcontrol.phoneflow

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentChallenge
import app.muxtv.localcontrol.enrollment.PhoneProtectedProbeFactory
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKey
import java.util.concurrent.atomic.AtomicReference

/**
 * An untrusted transport can relay at most one request and its untrusted response.
 * Nothing about the host/IP, an HTTP bearer or the transport result is an identity.
 * A production adapter MUST provide bounded deadlines and no plaintext fallback.
 */
fun interface Lc04PhoneFrameExchange {
    fun exchange(frame: ByteArray): ByteArray?
}

/** Display-only binding. This is NOT a confirmation, grant, or verified TV. */
class Lc04PhoneReview(
    val tvFingerprint: String,
    val phoneFingerprint: String,
) {
    override fun toString(): String = "Lc04PhoneReview([PUBLIC_IDENTITIES_REDACTED])"
}

/**
 * One ephemeral attempt based on the public TV key that the phone really scanned.
 *
 * offer() only returns an untrusted review prompt. The owner must confirm the
 * phone fingerprint on the TV using the TV-local D-pad. finishAfterTvAction()
 * can then send proof, but returns true ONLY when the TV's exact signed-probe
 * response passes the existing QR-pinned, one-shot AES-256-GCM verifier.
 *
 * No source commands, passwords, persisted pairing grant, manifest permission,
 * endpoint selection or socket handling belong to this state machine.
 */
class Lc04PhoneProofFlow(
    private val scannedTv: TvQrPublicKey,
    private val signingPhone: PhoneSigningIdentity,
    private val exchange: Lc04PhoneFrameExchange,
    elapsedMillis: () -> Long,
) : AutoCloseable {
    private enum class State { NEW, OFFERING, WAITING_FOR_TV, VERIFYING, VERIFIED, CLOSED }

    private val state = AtomicReference(State.NEW)
    private val probes = PhoneProtectedProbeFactory(scannedTv, signingPhone, elapsedMillis)
    @Volatile private var challenge: PhoneEnrollmentChallenge? = null

    /**
     * A TV_CHALLENGE is attacker-controlled until the complete authenticated
     * receipt is checked. QR-context matching here only limits which transcript
     * the phone is willing to sign; it does NOT authenticate the endpoint.
     */
    fun offer(): Lc04PhoneReview? {
        if (!state.compareAndSet(State.NEW, State.OFFERING)) return null
        try {
            val wire = Lc04PublicEnrollmentWire.phoneOffer(scannedTv, signingPhone)
            val response = try {
                exchange.exchange(wire)
            } finally {
                wire.fill(0)
            } ?: return null
            val accepted = try {
                Lc04PublicEnrollmentWire.decodeChallengeForPhone(
                    scannedTv, signingPhone, response,
                )
            } finally {
                response.fill(0)
            } ?: return null
            challenge = accepted
            if (!state.compareAndSet(State.OFFERING, State.WAITING_FOR_TV)) return null
            return Lc04PhoneReview(scannedTv.fingerprint, signingPhone.fingerprint)
        } catch (_: Exception) {
            return null
        } finally {
            if (state.get() == State.OFFERING) close()
        }
    }

    /**
     * Call only after the user has had an opportunity to approve on the REAL TV.
     * This phone-side action is never treated as TV consent; the TV independently
     * validates its local approval and Ed25519 signature before any receipt.
     *
     * PHONE_PROOF deliberately receives no success acknowledgement. A null
     * response at that step is never considered evidence of enrollment.
     */
    fun finishAfterTvAction(): Boolean {
        if (!state.compareAndSet(State.WAITING_FOR_TV, State.VERIFYING)) return false
        var verified = false
        try {
            val bound = challenge ?: return false
            val signed = signingPhone.prove(scannedTv, bound)
            val proof = Lc04PublicEnrollmentWire.phoneProof(scannedTv, signingPhone, signed)
            try {
                // A malicious peer can silently discard this frame. Only the
                // subsequent receipt proves the TV actually activated our key.
                exchange.exchange(proof)?.fill(0)
            } finally {
                proof.fill(0)
            }
            if (state.get() != State.VERIFYING) return false
            probes.create().use { pending ->
                val probe = pending.requestBytes()
                val reply = try {
                    exchange.exchange(probe)
                } finally {
                    probe.fill(0)
                } ?: return false
                val authentic = try {
                    pending.verifyReply(reply)
                } finally {
                    reply.fill(0)
                }
                if (!authentic) return false
                verified = state.compareAndSet(State.VERIFYING, State.VERIFIED)
                return verified
            }
        } catch (_: Exception) {
            return false
        } finally {
            challenge = null
            if (!verified) close()
        }
    }

    /** Per-process, per-attempt evidence only; not a reusable source capability. */
    fun hasAuthenticatedReceipt(): Boolean = state.get() == State.VERIFIED

    /**
     * May execute concurrently with a blocking exchange. The atomic CLOSED
     * transition ensures a late network response cannot resurrect verification.
     */
    override fun close() {
        if (state.getAndSet(State.CLOSED) == State.CLOSED) return
        challenge = null
        probes.close()
        signingPhone.close()
    }

    override fun toString(): String = "Lc04PhoneProofFlow([REDACTED])"
}
