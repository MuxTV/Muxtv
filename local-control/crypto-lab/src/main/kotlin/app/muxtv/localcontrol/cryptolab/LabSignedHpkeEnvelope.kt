package app.muxtv.localcontrol.cryptolab

import com.google.crypto.tink.KeysetHandle
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * LC04-S2-B isolated sender-authentication fixture.
 *
 * Sign each encrypted HPKE command with a TV-approved Ed25519 phone key. The current
 * private-IP HTTP bootstrap and browser page CANNOT securely enroll/verify this key.
 * Consequently this class does not grant SourceOnboarding access or open an HTTP API.
 */
class LabSignedHpkePhone(
    private val binding: LabBinding,
    private val declaredTvHpkeFingerprint: String,
    private val identity: LabPhoneIdentity,
    tvHpkePublicKeyset: KeysetHandle,
) {
    private val encryptedSender = LabPhoneSender(binding, tvHpkePublicKeyset)

    init { require(declaredTvHpkeFingerprint.matches(Regex("[0-9a-f]{64}"))) }

    fun send(operation: LabOperation, plaintext: ByteArray): LabSignedEnvelope {
        val cipher = encryptedSender.encrypt(operation, plaintext)
        val data = envelopeStatement(
            binding,
            declaredTvHpkeFingerprint,
            identity.fingerprint,
            cipher,
        )
        return LabSignedEnvelope(cipher, identity.signLabEnvelope(data))
    }
}

/** Copy-owned, no plaintext or private signing key, no logging of envelope contents. */
class LabSignedEnvelope(
    cipher: LabCiphertext,
    signature: ByteArray,
) {
    private val storedCipher =
        LabCiphertext(cipher.operation, cipher.counter, cipher.bytes)
    private val storedSignature = signature.clone()

    internal fun cipher(): LabCiphertext =
        LabCiphertext(storedCipher.operation, storedCipher.counter, storedCipher.bytes)
    internal fun signedBytes(): ByteArray = storedSignature.clone()

    override fun toString(): String = "LabSignedEnvelope(ciphertext=[REDACTED], signature=[REDACTED])"
}

sealed interface LabSignedReceiveResult {
    class Accepted(val payload: LabDecryptResult.Accepted) : LabSignedReceiveResult
    data object RejectedSignature : LabSignedReceiveResult
    data object RejectedCiphertextOrReplay : LabSignedReceiveResult
    data object RevokedOrExpired : LabSignedReceiveResult
}

/**
 * First verify the signature using the specific public key whose fingerprint the TV approved.
 * Only then pass the HPKE command to the context-bound/replay-guarded receiver.
 *
 * This is a demonstration that the post-enrollment sender counterexample can be closed
 * cryptographically. It still does NOT authenticate an untrusted HTTP phone page.
 */
class LabSignedHpkeTv(
    private val binding: LabBinding,
    private val declaredTvHpkeFingerprint: String,
    private val encryptedReceiver: LabReceiver,
    approved: LabEnrollmentOutcome.ProofVerified,
    private val elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val lifetimeMillis: Long = 300_000,
) {
    private val approvedPublicKey = approved.publicKeyBytes.clone()
    private val approvedPhoneFingerprint = approved.approvedPhoneFingerprint
    private val activatedAt = elapsedMillis()
    private var revoked = false

    init {
        require(declaredTvHpkeFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(approved.tvInvitationId == binding.tvInvitationId &&
            approved.pairingId == binding.approvedPairingId &&
            approved.approvedTvFingerprint == declaredTvHpkeFingerprint) {
            "Verified phone enrollment belongs to a different pairing/TV-key context."
        }
        require(lifetimeMillis in 1_000..900_000)
        require(activatedAt >= 0)
        val actual = MessageDigest.getInstance("SHA-256").digest(approvedPublicKey)
        require(toHex(actual) == approvedPhoneFingerprint)
        actual.fill(0)
    }

    @Synchronized
    fun revoke() {
        revoked = true
        approvedPublicKey.fill(0)
    }

    @Synchronized
    fun verifyAndDecrypt(envelope: LabSignedEnvelope): LabSignedReceiveResult {
        val now = elapsedMillis()
        if (revoked || now < activatedAt || now - activatedAt >= lifetimeMillis) {
            revoke()
            return LabSignedReceiveResult.RevokedOrExpired
        }
        val ciphertext = envelope.cipher()
        if (ciphertext.counter !in 1..128 || ciphertext.bytes.size !in 17..5_120) {
            return LabSignedReceiveResult.RejectedCiphertextOrReplay
        }

        val signature = envelope.signedBytes()
        val signatureValid = try {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(approvedPublicKey))
            val verifier = Signature.getInstance("Ed25519")
            verifier.initVerify(key)
            verifier.update(envelopeStatement(
                binding,
                declaredTvHpkeFingerprint,
                approvedPhoneFingerprint,
                ciphertext,
            ))
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        } finally {
            signature.fill(0)
        }
        if (!signatureValid) return LabSignedReceiveResult.RejectedSignature

        return when (val plaintext = encryptedReceiver.decrypt(ciphertext)) {
            is LabDecryptResult.Accepted -> LabSignedReceiveResult.Accepted(plaintext)
            is LabDecryptResult.Denied -> LabSignedReceiveResult.RejectedCiphertextOrReplay
        }
    }
}

private fun envelopeStatement(
    binding: LabBinding,
    tvFingerprint: String,
    phoneFingerprint: String,
    cipher: LabCiphertext,
): ByteArray {
    require(tvFingerprint.matches(Regex("[0-9a-f]{64}")))
    require(phoneFingerprint.matches(Regex("[0-9a-f]{64}")))
    require(cipher.counter in 1..128)
    val ciphertextDigest = MessageDigest.getInstance("SHA-256").digest(cipher.bytes)
    return ("MuxTV-LC04-SIGNED-HPKE-LAB-v1|" +
        binding.tvInvitationId + "|" +
        binding.approvedPairingId + "|" +
        tvFingerprint + "|" + phoneFingerprint + "|" +
        "phone-to-tv|" + cipher.operation.name + "|" + cipher.counter + "|" +
        toHex(ciphertextDigest)).toByteArray(Charsets.US_ASCII)
}

private fun toHex(bytes: ByteArray): String {
    val digits = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val v = byte.toInt() and 0xff
            append(digits[v ushr 4])
            append(digits[v and 15])
        }
    }
}
