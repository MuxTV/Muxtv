package app.muxtv.localcontrol.cryptolab

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * LC04-S2-B: proof-of-possession research. JDK17 Ed25519 is used solely in this JVM lab.
 * No assumption that Android API26, mobile Safari or an HTTP web page supports this API.
 *
 * An independent trusted UI must show the phone fingerprint before the TV owner approves.
 * The current LAN HTTP bootstrap is NOT such a UI and must never mint an authorized sender.
 */
class LabPhoneIdentity private constructor(
    private val privateKey: PrivateKey,
    publicKey: ByteArray,
) {
    private val encodedPublicKey = publicKey.clone()
    val fingerprint: String = sha256Hex(encodedPublicKey)

    fun publicKeyBytes(): ByteArray = encodedPublicKey.clone()

    fun sign(
        binding: LabBinding,
        tvHpkeKeyFingerprint: String,
        challenge: ByteArray,
    ): LabPhoneProof {
        require(tvHpkeKeyFingerprint.isSha256Hex())
        require(challenge.size == 32)
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(enrollmentTranscript(binding, tvHpkeKeyFingerprint, fingerprint, challenge))
        return LabPhoneProof(
            encodedPublicKey,
            challenge,
            signer.sign(),
        )
    }

    /** Signs a canonical domain-separated command transcript, never raw URL payload. */
    internal fun signLabEnvelope(statement: ByteArray): ByteArray {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(statement)
        return signer.sign()
    }

    override fun toString(): String = "LabPhoneIdentity(privateKey=[REDACTED])"

    companion object {
        fun generate(): LabPhoneIdentity {
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            return LabPhoneIdentity(pair.private, pair.public.encoded)
        }
    }
}

/** Immutable byte-copy wrapper; never accept a caller-mutated proof after checking its fields. */
class LabPhoneProof(
    publicKey: ByteArray,
    challenge: ByteArray,
    signature: ByteArray,
) {
    private val key = publicKey.clone()
    private val nonce = challenge.clone()
    private val signed = signature.clone()

    fun publicKeyBytes(): ByteArray = key.clone()
    internal fun challengeBytes(): ByteArray = nonce.clone()
    internal fun signatureBytes(): ByteArray = signed.clone()

    override fun toString(): String =
        "LabPhoneProof(publicKey=[REDACTED], challenge=[REDACTED], signature=[REDACTED])"
}

enum class LabEnrollmentDenial {
    INVALID,
    EXPIRED,
    TV_CONSENT_REQUIRED,
    WRONG_PHONE_KEY,
    ALREADY_REDEEMED,
    TOO_MANY_FAILURES,
}

sealed interface LabEnrollmentOutcome {
    /** Still LAB ONLY. This is not a production authorization token or principal. */
    class ProofVerified(
        val approvedPhoneFingerprint: String,
        approvedPhoneKey: ByteArray,
        internal val tvInvitationId: String,
        internal val pairingId: String,
        internal val approvedTvFingerprint: String,
    ) : LabEnrollmentOutcome {
        internal val publicKeyBytes = approvedPhoneKey.clone()
        override fun toString(): String = "ProofVerified(approvedPhoneKey=[REDACTED])"
    }
    class Denied(val reason: LabEnrollmentDenial) : LabEnrollmentOutcome
}

/**
 * One pending phone key per TV activation window. All mutation serialized.
 * A QR-pinned TV HPKE key AND an independently trusted phone UI are prerequisites
 * to giving this proof an authorization meaning in a real app.
 */
class LabTvPhoneEnrollment(
    private val binding: LabBinding,
    private val tvHpkeKeyFingerprint: String,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val rng: SecureRandom = SecureRandom(),
    private val ttlMillis: Long = 120_000,
    private val maxFailures: Int = 3,
) {
    init {
        require(tvHpkeKeyFingerprint.isSha256Hex())
        require(ttlMillis in 1_000..600_000)
        require(maxFailures in 1..16)
    }

    private var pending: Pending? = null
    private var lastRedeemedDigest: ByteArray? = null

    private class Pending(
        val phonePublic: ByteArray,
        val fingerprint: String,
        val issuedAt: Long,
        val challenge: ByteArray,
        var approved: Boolean = false,
        var failures: Int = 0,
    ) {
        fun erase() {
            challenge.fill(0)
            phonePublic.fill(0)
        }
    }

    @Synchronized
    fun beginEnrollment(phonePublicKey: ByteArray): ByteArray {
        require(phonePublicKey.size in 32..256)
        // Reject arbitrary blobs, not merely a digest whose corresponding key is unavailable.
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(phonePublicKey))
        pending?.erase()
        pending = null
        lastRedeemedDigest = null
        val now = elapsedMillis()
        require(now >= 0)
        val nonce = ByteArray(32).also(rng::nextBytes)
        pending = Pending(phonePublicKey.clone(), sha256Hex(phonePublicKey), now, nonce)
        return nonce.clone()
    }

    /** Only the actual TV owner-facing screen is permitted to call this method. */
    @Synchronized
    fun approveOnTv(fingerprint: String): Boolean {
        val current = livePending() ?: return false
        if (fingerprint != current.fingerprint) return false
        current.approved = true
        return true
    }

    /** No bearer cookie or existing plaintext pairing proof can bypass this check. */
    @Synchronized
    fun verifyProof(proof: LabPhoneProof): LabEnrollmentOutcome {
        val current = livePending() ?: return if (lastRedeemedDigest != null) {
            LabEnrollmentOutcome.Denied(LabEnrollmentDenial.ALREADY_REDEEMED)
        } else {
            LabEnrollmentOutcome.Denied(LabEnrollmentDenial.EXPIRED)
        }
        if (!current.approved) return LabEnrollmentOutcome.Denied(LabEnrollmentDenial.TV_CONSENT_REQUIRED)

        val public = proof.publicKeyBytes()
        val nonce = proof.challengeBytes()
        val signature = proof.signatureBytes()
        if (!MessageDigest.isEqual(current.phonePublic, public) ||
            !MessageDigest.isEqual(current.challenge, nonce)
        ) {
            return denyFailure(current, LabEnrollmentDenial.WRONG_PHONE_KEY)
        }
        val verified = try {
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(public)))
            verifier.update(enrollmentTranscript(
                binding, tvHpkeKeyFingerprint, current.fingerprint, nonce,
            ))
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        } finally {
            public.fill(0)
            nonce.fill(0)
            signature.fill(0)
        }
        if (!verified) return denyFailure(current, LabEnrollmentDenial.INVALID)

        val fingerprint = current.fingerprint
        val publicKey = current.phonePublic.clone()
        lastRedeemedDigest = digest(current.challenge)
        current.erase()
        pending = null
        return LabEnrollmentOutcome.ProofVerified(
            fingerprint, publicKey, binding.tvInvitationId,
            binding.approvedPairingId, tvHpkeKeyFingerprint,
        )
    }

    @Synchronized
    fun revoke() {
        pending?.erase()
        pending = null
        lastRedeemedDigest = null
    }

    private fun denyFailure(state: Pending, reason: LabEnrollmentDenial): LabEnrollmentOutcome {
        state.failures++
        if (state.failures >= maxFailures) {
            state.erase()
            pending = null
            return LabEnrollmentOutcome.Denied(LabEnrollmentDenial.TOO_MANY_FAILURES)
        }
        return LabEnrollmentOutcome.Denied(reason)
    }

    private fun livePending(): Pending? {
        val s = pending ?: return null
        val now = elapsedMillis()
        if (now < s.issuedAt || now - s.issuedAt >= ttlMillis) {
            s.erase()
            pending = null
            return null
        }
        return s
    }

    override fun toString(): String = "LabTvPhoneEnrollment([REDACTED])"
}

private fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun enrollmentTranscript(
    binding: LabBinding,
    tvFingerprint: String,
    phoneFingerprint: String,
    challenge: ByteArray,
): ByteArray {
    require(tvFingerprint.isSha256Hex() && phoneFingerprint.isSha256Hex())
    require(challenge.size == 32)
    // Fixed-length ASCII hex fields; no ambiguous delimiters or attacker-controlled URL.
    return ("MuxTV-LC04-PHONE-PROOF-LAB-v1|" +
        binding.tvInvitationId + "|" +
        binding.approvedPairingId + "|" +
        tvFingerprint + "|" + phoneFingerprint + "|" +
        challenge.toHex()).toByteArray(Charsets.US_ASCII)
}

private fun digest(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

private fun sha256Hex(bytes: ByteArray): String = digest(bytes).toHex()

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val n = byte.toInt() and 0xff
            append(digits[n ushr 4])
            append(digits[n and 15])
        }
    }
}
