package app.muxtv.localcontrol.enrollment

import app.muxtv.localcontrol.qr.TvQrPublicKey
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.wirelab.Lc04ProbeBody
import app.muxtv.localcontrol.wirelab.Lc04TypedTranscript
import app.muxtv.localcontrol.wirelab.Lc04TypedWire
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeySign
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.Ed25519PublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * LC04-S3-C: a cryptographic phone-identity proof, NOT a paired network principal.
 *
 * This module can run on Android API26 using Tink. There are no sockets, HTTP endpoints,
 * source operations, persistent credentials or Android UI dependencies.
 *
 * A product caller MUST present the proposed phone fingerprint on the TV, and ONLY a
 * trusted TV-local button event may call approveOnTv(). Merely presenting a QR or
 * knowing a legacy HTTP pairing token gives no approval.
 */
class TvPhoneEnrollment(
    tvQr: String,
    private val elapsedMillis: () -> Long,
    private val tvReceiverIsAlive: () -> Boolean,
    private val random: SecureRandom = SecureRandom(),
    private val ttlMillis: Long = 120_000,
    private val maxFailures: Int = 3,
) : AutoCloseable {
    private val binding = TvQrPublicKeyParser.parse(tvQr).asBinding()
    private var pending: Pending? = null
    private var closed = false
    private var redeemed = false

    init {
        require(ttlMillis in 1_000..120_000)
        require(maxFailures in 1..8)
    }

    private class Pending(
        val key: ByteArray,
        val verifier: PublicKeyVerify,
        val fingerprint: String,
        val challenge: ByteArray,
        val issuedAt: Long,
        var approved: Boolean = false,
        var failures: Int = 0,
    ) {
        fun erase() {
            key.fill(0)
            challenge.fill(0)
        }
    }

    /** Only ONE pending identity may exist during a TV QR lifetime. */
    @Synchronized
    fun begin(publicPhoneKeyset: ByteArray): PhoneEnrollmentChallenge? {
        if (!live() || pending != null || redeemed) return null
        val (canonical, verifier) = validatedPhoneKey(publicPhoneKeyset)
        val now = elapsedMillis()
        if (now < 0) return null
        val nonce = ByteArray(32).also(random::nextBytes)
        val fingerprint = sha256(canonical)
        pending = Pending(canonical, verifier, fingerprint, nonce, now)
        return PhoneEnrollmentChallenge(binding, fingerprint, nonce)
    }

    /**
     * Accept ONLY a strict, context-pinned PUBLIC offer. This method must not
     * be exposed from the existing untrusted HTTP /pair server. A remote offer
     * creates pending UI state ONLY; it never approves itself.
     */
    @Synchronized
    fun beginPublicOffer(wire: ByteArray): PhoneEnrollmentChallenge? {
        if (!live() || pending != null || redeemed) return null
        val public = Lc04PublicEnrollmentWire.decodeOfferForTv(wire, binding)
            ?: return null
        return try {
            begin(public)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: java.security.GeneralSecurityException) {
            null
        } finally {
            public.fill(0)
        }
    }

    /**
     * TV may inspect a detached signed proof ONLY against its OWN pending
     * fingerprint. The TV-local approveOnTv() gate still runs inside verify().
     */
    @Synchronized
    fun verifyPublicProof(wire: ByteArray): PhoneEnrollmentResult {
        if (!live()) return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.EXPIRED_OR_REVOKED)
        if (redeemed) return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.REPLAYED)
        val fingerprint = pendingFingerprintForTv()
            ?: return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.EXPIRED_OR_REVOKED)
        val signed = Lc04PublicEnrollmentWire.decodeProofForTv(
            wire, binding, fingerprint,
        ) ?: return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.INVALID_PROOF)
        return verify(signed)
    }

    /** The TV UI must compare this fingerprint with the actual user-intended phone. */
    @Synchronized
    fun pendingFingerprintForTv(): String? =
        if (pendingLive()) pending?.fingerprint else null

    /** Never expose this mutator on a LAN route, nor automatically approve a sender. */
    @Synchronized
    fun approveOnTv(displayedPhoneFingerprint: String): Boolean {
        if (!pendingLive()) return false
        val current = pending ?: return false
        if (current.fingerprint != displayedPhoneFingerprint) return false
        current.approved = true
        return true
    }

    @Synchronized
    fun verify(proof: PhoneEnrollmentProof): PhoneEnrollmentResult {
        if (!live()) return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.EXPIRED_OR_REVOKED)
        if (!pendingLive()) {
            return PhoneEnrollmentResult.Denied(
                if (redeemed) PhoneEnrollmentDenial.REPLAYED else PhoneEnrollmentDenial.EXPIRED_OR_REVOKED,
            )
        }
        val current = pending ?: return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.EXPIRED_OR_REVOKED)
        if (!current.approved) return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.TV_CONSENT_REQUIRED)
        val signature = proof.signatureBytes()
        val valid = try {
            // Tink signatures may contain a 5-byte output-prefix (RAW=64, TINK=69).
            if (signature.size !in 64..69) {
                false
            } else {
                current.verifier.verify(signature, proofTranscript(
                    binding, current.fingerprint, current.challenge,
                ))
                true
            }
        } catch (_: GeneralSecurityException) {
            false
        } finally {
            signature.fill(0)
        }
        if (!valid) {
            current.failures++
            if (current.failures >= maxFailures) {
                current.erase()
                pending = null
                // Do not allow infinite fresh challenges after exhausting the gate.
                // The TV owner must explicitly generate a new QR receiver session.
                closed = true
            }
            return PhoneEnrollmentResult.Denied(PhoneEnrollmentDenial.INVALID_PROOF)
        }
        val accepted = VerifiedPhoneIdentity(current.fingerprint, current.key, binding)
        current.erase()
        pending = null
        redeemed = true
        return PhoneEnrollmentResult.Verified(accepted)
    }

    @Synchronized
    override fun close() {
        closed = true
        pending?.erase()
        pending = null
    }

    private fun live(): Boolean {
        if (closed || !tvReceiverIsAlive()) {
            close()
            return false
        }
        return true
    }

    private fun pendingLive(): Boolean {
        if (!live()) return false
        val current = pending ?: return false
        val now = elapsedMillis()
        if (now < current.issuedAt || now - current.issuedAt >= ttlMillis) {
            current.erase()
            pending = null
            return false
        }
        return true
    }

    override fun toString(): String = "TvPhoneEnrollment([REDACTED])"
}

class PhoneSigningIdentity private constructor(
    val fingerprint: String,
    publicKeyset: ByteArray,
    private var signer: PublicKeySign?,
) : AutoCloseable {
    private val publicKey = publicKeyset.clone()
    fun publicKeysetBytes(): ByteArray = publicKey.clone()

    /**
     * Sign only after scanning and parsing the physical TV's MXLC04:1 QR.
     * Reject substituted challenge context before signing.
     */
    @Synchronized
    fun prove(scannedTvQr: TvQrPublicKey, challenge: PhoneEnrollmentChallenge): PhoneEnrollmentProof {
        checkNotNull(signer) { "Phone signing identity has been closed." }
        val fromQr = scannedTvQr.asBinding()
        require(challenge.binding == fromQr && challenge.phoneFingerprint == fingerprint) {
            "Enrollment challenge is not bound to the scanned TV and this phone."
        }
        return PhoneEnrollmentProof(checkNotNull(signer).sign(
            proofTranscript(fromQr, fingerprint, challenge.nonceBytes()),
        ))
    }

    /**
     * Sign ONLY the canonical CHANNEL_PROBE statement for this phone and
     * the physically scanned TV HPKE QR. Never sign an arbitrary LAN payload.
     * No private signing key or provider data is included in the frame.
     */
    @Synchronized
    fun signProtectedProbe(scannedTv: TvQrPublicKey, counter: Int, ciphertext: ByteArray): ByteArray {
        val current = checkNotNull(signer) { "Phone signing identity is closed." }
        require(counter in 1..128)
        val tvFingerprint = scannedTv.fingerprint
        val tvKey = hexBytes(tvFingerprint)
        val phoneKey = hexBytes(fingerprint)
        val invitation = hexBytes(scannedTv.invitationId)
        val pairing = hexBytes(scannedTv.pairingId)
        // The statement excludes the signature itself, avoiding circularity.
        val placeholder = Lc04ProbeBody(counter, ciphertext, ByteArray(69))
        val draft = Lc04WireFrame(Lc04WireKind.SIGNED_CHANNEL_PROBE,
            invitation, pairing, tvKey, phoneKey, Lc04TypedWire.encodeProbe(placeholder))
        val statement = Lc04TypedTranscript.probeSigningStatement(draft, placeholder)
        val signature = try { current.sign(statement) } finally { statement.fill(0) }
        try {
            require(signature.size == 64 || signature.size == 69) { "Unexpected Tink signature format." }
            return Lc04WireLab.encode(Lc04WireFrame(
                Lc04WireKind.SIGNED_CHANNEL_PROBE,
                invitation, pairing, tvKey, phoneKey,
                Lc04TypedWire.encodeProbe(Lc04ProbeBody(counter, ciphertext, signature)),
            ))
        } finally { signature.fill(0) }
    }

    @Synchronized
    override fun close() {
        signer = null // Tink private bytes cannot be guaranteed zeroized on managed heap.
    }

    override fun toString(): String = "PhoneSigningIdentity(privateKey=[REDACTED])"

    companion object {
        fun generate(): PhoneSigningIdentity {
            SignatureConfig.register()
            val keys = KeysetHandle.newBuilder()
                .addEntry(KeysetHandle.generateEntryFromParametersName("ED25519")
                    .withRandomId().makePrimary())
                .build()
            val public = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(keys.publicKeysetHandle)
            validatedPhoneKey(public)
            val signer = keys.getPrimitive(RegistryConfiguration.get(), PublicKeySign::class.java)
            return PhoneSigningIdentity(sha256(public), public, signer)
        }
    }
}

/** This returned identity is NOT a SOURCE_CREATE capability, bearer or durable session. */
class VerifiedPhoneIdentity internal constructor(
    val fingerprint: String,
    keyset: ByteArray,
    binding: EnrollmentBinding,
) {
    val tvInvitationId: String = binding.invitationId
    val tvPairingId: String = binding.pairingId
    val tvFingerprint: String = binding.tvFingerprint
    private val public = keyset.clone()
    fun publicKeysetBytes(): ByteArray = public.clone()
    override fun toString(): String = "VerifiedPhoneIdentity([PUBLIC_KEY_REDACTED])"
}

class PhoneEnrollmentChallenge internal constructor(
    internal val binding: EnrollmentBinding,
    val phoneFingerprint: String,
    nonce: ByteArray,
) {
    private val nonce = nonce.clone()
    fun nonceBytes(): ByteArray = nonce.clone()
    override fun toString(): String = "PhoneEnrollmentChallenge([NONCE_REDACTED])"
}

class PhoneEnrollmentProof(signature: ByteArray) {
    private val signed = signature.clone()
    fun signatureBytes(): ByteArray = signed.clone()
    override fun toString(): String = "PhoneEnrollmentProof([SIGNATURE_REDACTED])"
}

enum class PhoneEnrollmentDenial { EXPIRED_OR_REVOKED, TV_CONSENT_REQUIRED, INVALID_PROOF, REPLAYED }

sealed interface PhoneEnrollmentResult {
    class Verified(val identity: VerifiedPhoneIdentity) : PhoneEnrollmentResult
    class Denied(val reason: PhoneEnrollmentDenial) : PhoneEnrollmentResult
}

internal data class EnrollmentBinding(
    val invitationId: String,
    val pairingId: String,
    val tvFingerprint: String,
)

private fun TvQrPublicKey.asBinding(): EnrollmentBinding =
    EnrollmentBinding(invitationId, pairingId, fingerprint)

private fun validatedPhoneKey(raw: ByteArray): Pair<ByteArray, PublicKeyVerify> {
    require(raw.size in 1..256) { "Invalid bounded Ed25519 keyset." }
    val copy = raw.clone()
    val keyset = try {
        Keyset.parseFrom(copy)
    } catch (_: Exception) {
        throw IllegalArgumentException("Malformed phone public keyset.")
    }
    require(keyset.keyCount == 1 && keyset.primaryKeyId == keyset.getKey(0).keyId)
    val entry = keyset.getKey(0)
    require(entry.status == KeyStatusType.ENABLED)
    require(entry.keyData.typeUrl == "type.googleapis.com/google.crypto.tink.Ed25519PublicKey")
    require(entry.keyData.keyMaterialType == KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC)
    val public = try {
        Ed25519PublicKey.parseFrom(entry.keyData.value)
    } catch (_: Exception) {
        throw IllegalArgumentException("Malformed Ed25519 public key.")
    }
    require(public.version == 0 && public.keyValue.size() == 32)
    SignatureConfig.register()
    val handle = try {
        TinkProtoKeysetFormat.parseKeysetWithoutSecret(copy)
    } catch (_: GeneralSecurityException) {
        throw IllegalArgumentException("Phone public keyset is not valid Tink public material.")
    }
    require(MessageDigest.isEqual(copy, TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle)))
    val verifier = handle.getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
    return copy to verifier
}

private fun proofTranscript(binding: EnrollmentBinding, phoneFingerprint: String, nonce: ByteArray): ByteArray {
    require(nonce.size == 32 && phoneFingerprint.matches(Regex("[0-9a-f]{64}")))
    // All fields have fixed length and canonical lowercase hexadecimal encoding.
    return ("MuxTV-LC04-PHONE-ENROLL-v1|" + binding.invitationId + "|" +
        binding.pairingId + "|" + binding.tvFingerprint + "|" +
        phoneFingerprint + "|" + hex(nonce)).toByteArray(Charsets.US_ASCII)
}

private fun hexBytes(value: String): ByteArray {
    require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(value.length / 2) { index ->
        (((value[index * 2].digitToInt(16) shl 4) or
            value[index * 2 + 1].digitToInt(16))).toByte()
    }
}

private fun sha256(bytes: ByteArray): String =
    hex(MessageDigest.getInstance("SHA-256").digest(bytes))

private fun hex(bytes: ByteArray): String {
    val alphabet = "0123456789abcdef"
    return buildString(bytes.size * 2) {
        for (byte in bytes) {
            val u = byte.toInt() and 255
            append(alphabet[u ushr 4])
            append(alphabet[u and 15])
        }
    }
}
