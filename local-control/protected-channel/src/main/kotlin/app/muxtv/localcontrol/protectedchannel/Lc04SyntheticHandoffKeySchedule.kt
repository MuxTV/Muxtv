package app.muxtv.localcontrol.protectedchannel

import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LC04-S2C: experimental post-handshake key schedule, NOT an authenticated
 * handshake, a production wire API or a source-data authorization primitive.
 *
 * Callers must independently authenticate the actual QR, approved Ed25519
 * phone, signed HPKE ciphertext and a fresh TV key confirmation. Supplying
 * a plausible-looking transcript here does NOT prove any of those checks.
 * The separate 256-bit seed must come from a NEW HPKE-protected handoff:
 * never reuse the consumed one-shot SEALED_TV_RECEIPT key.
 *
 * Internal to an unreferenced JVM research module; no Android application
 * or IPTV provider module links it.
 */
object Lc04SyntheticHandoffKeySchedule {
    private val EXTRACT_DOMAIN = "MuxTV-LC04-SYNTHETIC-HANDOFF-HKDF-EXTRACT-v1\u0000"
        .toByteArray(Charsets.US_ASCII)
    private val EXPAND_DOMAIN = "MuxTV-LC04-SYNTHETIC-HANDOFF-HKDF-EXPAND-v1\u0000"
        .toByteArray(Charsets.US_ASCII)
    private const val KEY_BYTES = 32

    fun derive(dedicatedHpkeSeed: ByteArray, transcript: Lc04SyntheticHandoffTranscript):
        Lc04SyntheticDerivedKeys {
        require(dedicatedHpkeSeed.size == KEY_BYTES &&
            dedicatedHpkeSeed.any { it != 0.toByte() }) {
            "A separate unpredictable 256-bit HPKE seed is required."
        }
        val context = transcript.digest()
        val extractSalt = sha256(EXTRACT_DOMAIN + context)
        val prk = Lc04Rfc5869HkdfSha256.extract(extractSalt, dedicatedHpkeSeed)
        extractSalt.fill(0)
        try {
            fun deriveLabel(label: String): ByteArray {
                val info = EXPAND_DOMAIN + label.toByteArray(Charsets.US_ASCII) + context
                return try { Lc04Rfc5869HkdfSha256.expand(prk, info, KEY_BYTES) }
                finally { info.fill(0) }
            }
            val phoneToTv = deriveLabel("phone-to-tv|")
            val tvToPhone = deriveLabel("tv-to-phone|")
            val confirmTv = deriveLabel("confirm-tv|")
            val confirmPhone = deriveLabel("confirm-phone|")
            return try {
                Lc04SyntheticDerivedKeys(
                    phoneToTv, tvToPhone, confirmTv, confirmPhone, context,
                )
            } finally {
                phoneToTv.fill(0)
                tvToPhone.fill(0)
                confirmTv.fill(0)
                confirmPhone.fill(0)
            }
        } finally {
            context.fill(0)
            prk.fill(0)
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * Bounded canonical transcript structure for the lab; all values below are
 * public bindings. Neither the TV nonce nor any digest is a bearer credential.
 */
class Lc04SyntheticHandoffTranscript(
    invitationId: ByteArray,
    pairingId: ByteArray,
    tvQrFingerprint: ByteArray,
    approvedPhoneFingerprint: ByteArray,
    exactSignedHandoffDigest: ByteArray,
    freshTvConfirmationNonce: ByteArray,
) {
    private val bytes: ByteArray

    init {
        require(invitationId.size == 16 && pairingId.size == 16)
        require(tvQrFingerprint.size == 32 && approvedPhoneFingerprint.size == 32)
        require(exactSignedHandoffDigest.size == 32 && freshTvConfirmationNonce.size == 32)
        bytes = byteArrayOf(1) + invitationId + pairingId +
            tvQrFingerprint + approvedPhoneFingerprint +
            exactSignedHandoffDigest + freshTvConfirmationNonce
    }

    fun digest(): ByteArray {
        val domain = "MuxTV-LC04-SYNTHETIC-HANDOFF-TRANSCRIPT-v1\u0000"
            .toByteArray(Charsets.US_ASCII)
        return try {
            MessageDigest.getInstance("SHA-256").digest(domain + bytes)
        } finally {
            domain.fill(0)
        }
    }

    override fun toString(): String = "Lc04SyntheticHandoffTranscript([REDACTED])"
}

/**
 * One synthetic handoff's independent direction and key-confirmation keys.
 * Keys never leave the lab except as digests for negative tests. The confirmation
 * functions are deliberately limited to a fixed 32-byte MAC, not arbitrary data.
 */
class Lc04SyntheticDerivedKeys(
    phoneToTv: ByteArray,
    tvToPhone: ByteArray,
    confirmTv: ByteArray,
    confirmPhone: ByteArray,
    bindingDigest: ByteArray,
) : AutoCloseable {
    private var phoneToTvKey: ByteArray? = phoneToTv.clone()
    private var tvToPhoneKey: ByteArray? = tvToPhone.clone()
    private var tvConfirmationKey: ByteArray? = confirmTv.clone()
    private var phoneConfirmationKey: ByteArray? = confirmPhone.clone()
    private val binding = bindingDigest.clone()
    private var closed = false

    @Synchronized
    fun syntheticDirectionDigest(phoneToTv: Boolean): ByteArray? {
        if (closed) return null
        val key = if (phoneToTv) phoneToTvKey else tvToPhoneKey
        return key?.let { MessageDigest.getInstance("SHA-256").digest(it) }
    }

    @Synchronized
    fun syntheticTvConfirmation(): ByteArray? =
        if (closed) null else tvConfirmationKey?.let {
            Lc04Rfc5869HkdfSha256.hmac(it, TV_CONFIRM_DOMAIN + binding)
        }

    @Synchronized
    fun syntheticPhoneConfirmation(): ByteArray? =
        if (closed) null else phoneConfirmationKey?.let {
            Lc04Rfc5869HkdfSha256.hmac(it, PHONE_CONFIRM_DOMAIN + binding)
        }

    @Synchronized
    fun verifySyntheticTvConfirmation(tag: ByteArray): Boolean {
        if (closed) return false
        val expected = syntheticTvConfirmation() ?: return false
        return try { tag.size == 32 && MessageDigest.isEqual(expected, tag) }
        finally { expected.fill(0) }
    }

    @Synchronized
    fun verifySyntheticPhoneConfirmation(tag: ByteArray): Boolean {
        if (closed) return false
        val expected = syntheticPhoneConfirmation() ?: return false
        return try { tag.size == 32 && MessageDigest.isEqual(expected, tag) }
        finally { expected.fill(0) }
    }

    /**
     * Fourth MXH1 frame: TV proves it processed the EXACT authenticated
     * PHONE_KEY_CONFIRM frame. The caller must first verify that phone MAC.
     *
     * A separate HMAC domain is used under the TV-confirm key; the input is
     * ONLY the 32-byte SHA-256 of the canonical signed-session-bound phone
     * confirmation, never arbitrary application data. The transcript binding
     * already covers the exact signed offer and fresh TV nonce.
     */
    @Synchronized
    fun syntheticTvFinishedForPhoneFrameDigest(phoneFrameDigest: ByteArray): ByteArray? {
        if (closed || phoneFrameDigest.size != 32) return null
        val key = tvConfirmationKey ?: return null
        val input = TV_FINISHED_DOMAIN + binding + phoneFrameDigest
        return try { Lc04Rfc5869HkdfSha256.hmac(key, input) }
        finally { input.fill(0) }
    }

    @Synchronized
    fun verifySyntheticTvFinished(
        phoneFrameDigest: ByteArray,
        receivedTag: ByteArray,
    ): Boolean {
        if (closed || phoneFrameDigest.size != 32 || receivedTag.size != 32) return false
        val expected = syntheticTvFinishedForPhoneFrameDigest(phoneFrameDigest) ?: return false
        return try { MessageDigest.isEqual(expected, receivedTag) }
        finally { expected.fill(0) }
    }

    /**
     * One-time claim of the existing HKDF-derived secret owner. The returned
     * digest is PUBLIC transcript binding, never raw AES traffic key material.
     * Reopening a channel with counter reset is prohibited for nonce safety.
     */
    private var syntheticRecordLabClaimed = false

    @Synchronized
    internal fun claimSyntheticRecordLabOwner(): ByteArray? {
        if (closed || syntheticRecordLabClaimed) return null
        syntheticRecordLabClaimed = true
        return binding.clone()
    }

    /** AES-256-GCM is deliberately private to this module; callers cannot submit app data. */
    @Synchronized
    internal fun sealSyntheticLabRecord(
        phoneToTv: Boolean, nonce: ByteArray, plain: ByteArray, aad: ByteArray,
    ): ByteArray? {
        if (closed || !syntheticRecordLabClaimed || nonce.size != 12 ||
            plain.size != 32 || aad.isEmpty()) return null
        val key = (if (phoneToTv) phoneToTvKey else tvToPhoneKey) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(plain)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    @Synchronized
    internal fun openSyntheticLabRecord(
        phoneToTv: Boolean, nonce: ByteArray, cipherText: ByteArray, aad: ByteArray,
    ): ByteArray? {
        if (closed || !syntheticRecordLabClaimed || nonce.size != 12 ||
            cipherText.size != 48 || aad.isEmpty()) return null
        val key = (if (phoneToTv) phoneToTvKey else tvToPhoneKey) ?: return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(cipherText)
        } catch (_: GeneralSecurityException) {
            null
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        phoneToTvKey?.fill(0)
        tvToPhoneKey?.fill(0)
        tvConfirmationKey?.fill(0)
        phoneConfirmationKey?.fill(0)
        phoneToTvKey = null
        tvToPhoneKey = null
        tvConfirmationKey = null
        phoneConfirmationKey = null
        binding.fill(0)
    }

    override fun toString(): String = "Lc04SyntheticDerivedKeys([REDACTED])"

    private companion object {
        val TV_CONFIRM_DOMAIN = "MuxTV-LC04-SYNTHETIC-TV-KEY-CONFIRM-v1\u0000"
            .toByteArray(Charsets.US_ASCII)
        val PHONE_CONFIRM_DOMAIN = "MuxTV-LC04-SYNTHETIC-PHONE-KEY-CONFIRM-v1\u0000"
            .toByteArray(Charsets.US_ASCII)
        val TV_FINISHED_DOMAIN = "MuxTV-LC04-MXH1-TV-FINISHED-v1\u0000"
            .toByteArray(Charsets.US_ASCII)
    }
}

/** RFC 5869 HKDF-SHA256 with all inputs domain-separated by the caller. */
internal object Lc04Rfc5869HkdfSha256 {
    fun extract(salt: ByteArray, keyMaterial: ByteArray): ByteArray {
        require(keyMaterial.isNotEmpty())
        return hmac(if (salt.isEmpty()) ByteArray(32) else salt, keyMaterial)
    }

    fun expand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(prk.size >= 32 && length in 1..(255 * 32))
        val result = ByteArray(length)
        var previous = ByteArray(0)
        var offset = 0
        try {
            for (counter in 1..((length + 31) / 32)) {
                val blockInput = previous + info + byteArrayOf(counter.toByte())
                val next = try { hmac(prk, blockInput) }
                    finally { blockInput.fill(0) }
                previous.fill(0)
                previous = next
                val step = minOf(32, length - offset)
                next.copyInto(result, offset, 0, step)
                offset += step
            }
            return result
        } finally { previous.fill(0) }
    }

    fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        require(key.isNotEmpty())
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }
}
