package app.muxtv.localcontrol.cryptolab

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LC04-S3-D0: isolated bidirectional-authentication feasibility fixture.
 *
 * The native phone generates a fresh random 256-bit reply secret and a 128-bit nonce,
 * places them INSIDE a signed HPKE request to the already QR-pinned TV receiver,
 * and retains the secret only in volatile memory. TV verifies the pre-enrolled
 * phone's signature and decrypts the HPKE envelope before it may use that secret
 * for its AES-256-GCM authenticated reply. An on-path attacker with only the
 * public TV key CANNOT produce a valid TV answer to this phone's challenge.
 *
 * This is NOT a LAN endpoint, not an authorization principal, not an accepted
 * production wire format, and never accepts IPTV source data.
 */
class LabReplyPhone(
    private val binding: LabBinding,
    private val tvFingerprint: String,
    private val phoneFingerprint: String,
    private val signedSender: LabSignedHpkePhone,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(labSha256(tvFingerprint) && labSha256(phoneFingerprint))
    }

    fun initiate(): LabPendingTvReply {
        val secret = ByteArray(32).also(random::nextBytes)
        val challenge = ByteArray(16).also(random::nextBytes)
        val payload = secret + challenge
        return try {
            // A fixed already available lab operation; never dispatched to SourceOnboarding.
            val envelope = signedSender.send(LabOperation.SOURCE_CANCEL, payload)
            LabPendingTvReply(binding, tvFingerprint, phoneFingerprint, envelope, secret, challenge)
        } finally {
            secret.fill(0)
            challenge.fill(0)
            payload.fill(0)
        }
    }
}

class LabPendingTvReply internal constructor(
    private val binding: LabBinding,
    private val tvFingerprint: String,
    private val phoneFingerprint: String,
    private val envelope: LabSignedEnvelope,
    secret: ByteArray,
    challenge: ByteArray,
) : AutoCloseable {
    private var key: ByteArray? = secret.clone()
    private val nonce = challenge.clone()
    private val requestId = labRequestId(envelope)
    private var failures = 0

    /** Return only the encrypted signed envelope, NEVER the local reply key. */
    fun signedRequest(): LabSignedEnvelope = envelope

    @Synchronized
    fun verify(reply: LabSealedTvReply): Boolean {
        val secret = key ?: return false
        if (failures >= 3) return false
        val cipherBytes = reply.ciphertext()
        val iv = reply.iv()
        if (iv.size != 12 || cipherBytes.size !in 17..256 ||
            reply.counter != envelope.cipher().counter ||
            !MessageDigest.isEqual(requestId, reply.requestId())
        ) {
            failures++
            return false
        }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(labReplyAad(binding, tvFingerprint, phoneFingerprint,
                envelope.cipher().counter, requestId))
            cipher.doFinal(cipherBytes)
        } catch (_: GeneralSecurityException) {
            failures++
            return false
        } finally {
            iv.fill(0)
            cipherBytes.fill(0)
        }
        val expected = "MuxTV-TV-RECEIVED-v1|".toByteArray(Charsets.US_ASCII) + nonce
        val accepted = MessageDigest.isEqual(plain, expected)
        plain.fill(0)
        expected.fill(0)
        if (accepted) close() else failures++
        if (failures >= 3) close()
        return accepted
    }

    @Synchronized
    override fun close() {
        key?.fill(0)
        key = null
        nonce.fill(0)
    }

    override fun toString(): String = "LabPendingTvReply([KEY_AND_CHALLENGE_REDACTED])"
}

/** Immutable, copy-owned authenticated TV acknowledgement only; no provider payload. */
class LabSealedTvReply internal constructor(
    val counter: Int,
    requestId: ByteArray,
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    private val id = requestId.clone()
    private val initializationVector = iv.clone()
    private val sealed = ciphertext.clone()
    fun requestId(): ByteArray = id.clone()
    fun iv(): ByteArray = initializationVector.clone()
    fun ciphertext(): ByteArray = sealed.clone()

    /** Negative-test mutation; never alters the original response. */
    internal fun modified(
        counter: Int = this.counter,
        requestId: ByteArray = id,
        iv: ByteArray = initializationVector,
        ciphertext: ByteArray = sealed,
    ): LabSealedTvReply = LabSealedTvReply(counter, requestId, iv, ciphertext)

    override fun toString(): String = "LabSealedTvReply([AUTHENTICATED_CIPHERTEXT_REDACTED])"
}

sealed interface LabTvReplyResult {
    class Sent(val reply: LabSealedTvReply) : LabTvReplyResult
    data object Denied : LabTvReplyResult
}

/**
 * No response can be generated without BOTH a valid approved Ed25519 sender
 * and successful HPKE decryption. The 32-byte reply secret remains transient.
 */
class LabAuthenticatedReplyTv(
    private val binding: LabBinding,
    private val tvFingerprint: String,
    private val phoneFingerprint: String,
    private val signedReceiver: LabSignedHpkeTv,
    private val random: SecureRandom = SecureRandom(),
) {
    init {
        require(labSha256(tvFingerprint) && labSha256(phoneFingerprint))
    }

    @Synchronized
    fun replyTo(request: LabSignedEnvelope): LabTvReplyResult {
        // Server FIRST verifies approved phone signature + per-request HPKE AAD/counter.
        val result = signedReceiver.verifyAndDecrypt(request)
        if (result !is LabSignedReceiveResult.Accepted) return LabTvReplyResult.Denied
        var reply: LabSealedTvReply? = null
        result.payload.consumeOnce { plaintext ->
            if (plaintext.size != 48) return@consumeOnce
            val key = plaintext.copyOfRange(0, 32)
            val challenge = plaintext.copyOfRange(32, 48)
            val iv = ByteArray(12).also(random::nextBytes)
            val requestId = labRequestId(request)
            val counter = request.cipher().counter
            val ack = "MuxTV-TV-RECEIVED-v1|".toByteArray(Charsets.US_ASCII) + challenge
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
                cipher.updateAAD(labReplyAad(binding, tvFingerprint, phoneFingerprint,
                    counter, requestId))
                reply = LabSealedTvReply(counter, requestId, iv, cipher.doFinal(ack))
            } finally {
                key.fill(0)
                challenge.fill(0)
                iv.fill(0)
                requestId.fill(0)
                ack.fill(0)
            }
        }
        return reply?.let(LabTvReplyResult::Sent) ?: LabTvReplyResult.Denied
    }
}

private fun labReplyAad(
    binding: LabBinding,
    tvFp: String,
    phoneFp: String,
    counter: Int,
    requestId: ByteArray,
): ByteArray {
    require(counter in 1..128)
    return ("MuxTV-LC04-TV-REPLY-LAB-v1|" + binding.tvInvitationId + "|" +
        binding.approvedPairingId + "|" + tvFp + "|" + phoneFp +
        "|tv-to-phone|SOURCE_CANCEL|" + counter + "|" + labHex(requestId))
        .toByteArray(Charsets.US_ASCII)
}

private fun labRequestId(envelope: LabSignedEnvelope): ByteArray =
    MessageDigest.getInstance("SHA-256").apply {
        update(envelope.cipher().operation.name.toByteArray(Charsets.US_ASCII))
        update(envelope.cipher().counter.toString().toByteArray(Charsets.US_ASCII))
        update(envelope.cipher().bytes)
        update(envelope.signedBytes())
    }.digest()

private fun labSha256(s: String): Boolean =
    s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' }

private fun labHex(bytes: ByteArray): String {
    val hex = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (b in bytes) {
            val x = b.toInt() and 255
            append(hex[x ushr 4])
            append(hex[x and 15])
        }
    }
}
