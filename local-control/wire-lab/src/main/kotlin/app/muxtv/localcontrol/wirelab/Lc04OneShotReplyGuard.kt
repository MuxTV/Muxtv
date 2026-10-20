package app.muxtv.localcontrol.wirelab

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LC04-S3-D1C: JVM-only receipt verification/replay lab.
 *
 * The phone must retain the exact originally SIGNED probe plus a unique 256-bit
 * reply key and 128-bit phone challenge, generated before the request was sent.
 * Only the TV's HPKE-decrypted probe can convey that reply key to the TV.
 *
 * This guard uses the original local request to derive the expected receipt AAD,
 * verifies AES-256-GCM, compares the exact phone challenge and consumes the
 * one-shot secret. It is not a TV enrollment check, a network service or
 * SourceOnboarding authorization. Upstream MUST still verify the phone/TV QR
 * identity, Ed25519 signature, HPKE ciphertext and user consent independently.
 *
 * Every call is serialized and fail-closed. No secrets are written to logs.
 */
class Lc04OneShotReplyGuard(
    private val originalSignedProbe: Lc04WireFrame,
    secretReplyKey: ByteArray,
    challenge: ByteArray,
    private val createdAtElapsedMillis: Long,
    private val elapsedMillis: () -> Long,
    private val ttlMillis: Long = 120_000,
    private val maxFailedReplies: Int = 3,
) : AutoCloseable {
    private var key: ByteArray? = secretReplyKey.clone()
    private val challengeNonce = challenge.clone()
    private var failures = 0
    private var closed = false

    init {
        require(originalSignedProbe.kind == Lc04WireKind.SIGNED_CHANNEL_PROBE &&
            Lc04TypedWire.decodeProbeFrame(originalSignedProbe) != null
        ) { "An exact canonical original signed request is required." }
        require(secretReplyKey.size == 32) { "A fresh 256-bit reply key is required." }
        require(challenge.size == 16) { "A fresh 128-bit challenge is required." }
        require(ttlMillis in 1_000..120_000)
        require(createdAtElapsedMillis >= 0 && createdAtElapsedMillis <= Long.MAX_VALUE - ttlMillis)
        require(maxFailedReplies in 1..8)
    }

    @Synchronized
    fun verify(received: Lc04WireFrame): Boolean {
        if (!alive()) return false
        val aesKey = key ?: return false
        val aad = Lc04TypedTranscript.aadForOutstandingRequest(
            originalSignedProbe, received,
        ) ?: return reject()
        val decoded = Lc04TypedWire.decodeReceiptFrame(received) ?: return reject()
        val iv = decoded.iv()
        val cipherBytes = decoded.ciphertext()
        val plain = try {
            val decryptor = Cipher.getInstance("AES/GCM/NoPadding")
            decryptor.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"),
                GCMParameterSpec(128, iv))
            decryptor.updateAAD(aad)
            decryptor.doFinal(cipherBytes)
        } catch (_: GeneralSecurityException) {
            return reject()
        } finally {
            iv.fill(0)
            cipherBytes.fill(0)
            aad.fill(0)
        }
        val expected = ACK_DOMAIN + challengeNonce
        return try {
            if (!MessageDigest.isEqual(plain, expected)) {
                reject()
            } else {
                close()
                true
            }
        } finally {
            plain.fill(0)
            expected.fill(0)
        }
    }

    @Synchronized
    fun isPending(): Boolean = alive()

    private fun alive(): Boolean {
        if (closed || key == null) return false
        val now = elapsedMillis()
        if (now < createdAtElapsedMillis || now - createdAtElapsedMillis >= ttlMillis) {
            close()
            return false
        }
        return true
    }

    private fun reject(): Boolean {
        failures++
        if (failures >= maxFailedReplies) close()
        return false
    }

    @Synchronized
    override fun close() {
        closed = true
        key?.fill(0)
        key = null
        challengeNonce.fill(0)
    }

    override fun toString(): String = "Lc04OneShotReplyGuard([SECRET_REDACTED])"

    companion object {
        private val ACK_DOMAIN = "MuxTV-TV-RECEIVED-v1|".toByteArray(Charsets.US_ASCII)
    }
}
