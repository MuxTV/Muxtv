package app.muxtv.localcontrol.cryptolab

import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.Base64

/**
 * A canonical LAB-ONLY scan payload that *contains the receiver's actual Tink HPKE public
 * keyset*, not merely a caller-provided fingerprint. This is not a website URL or a browser
 * security origin and may only be passed out of band via a QR viewed on the real TV.
 *
 * Trust in that QR path, phone JS integrity, bidirectional authentication and Android API26
 * crypto support are NOT established by this JVM wire-format test.
 */
object LabTvQrKeyBinding {
    private const val PREFIX = "MXLC04:1:"
    private const val MAX_KEYSET_BYTES = 512
    private const val MAX_QR_LENGTH = 900
    private const val TYPE_HPKE = "type.googleapis.com/google.crypto.tink.HpkePublicKey"
    private val ID = Regex("[0-9a-f]{32}")
    private val BASE64URL = Regex("[A-Za-z0-9_-]+")

    fun encode(binding: LabBinding, publicKeyset: KeysetHandle): String {
        val blob = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(publicKeyset)
        validate(blob)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(blob)
        val qr = "$PREFIX${binding.tvInvitationId}:${binding.approvedPairingId}:$encoded"
        require(qr.length <= MAX_QR_LENGTH)
        return qr
    }

    fun parse(qrText: String): ScannedTvHpkeKey {
        require(qrText.length in 1..MAX_QR_LENGTH && qrText.startsWith(PREFIX))
        val fields = qrText.removePrefix(PREFIX).split(':')
        require(fields.size == 3)
        val invitation = fields[0]
        val pairing = fields[1]
        require(ID.matches(invitation) && ID.matches(pairing))
        val encoded = fields[2]
        require(encoded.isNotEmpty() && encoded.length <= 684 && BASE64URL.matches(encoded))
        val decoded = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid HPKE public QR encoding")
        }
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == encoded) {
            "Noncanonical HPKE QR payload"
        }
        val handle = validate(decoded)
        return ScannedTvHpkeKey(LabBinding(invitation, pairing), decoded, handle)
    }

    private fun validate(bytes: ByteArray): KeysetHandle {
        require(bytes.size in 1..MAX_KEYSET_BYTES)
        val keyset = try {
            Keyset.parseFrom(bytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid public keyset encoding")
        }
        require(keyset.keyCount == 1 && keyset.primaryKeyId == keyset.getKey(0).keyId)
        val key = keyset.getKey(0)
        require(key.status == KeyStatusType.ENABLED)
        val data = key.keyData
        require(data.typeUrl == TYPE_HPKE &&
            data.keyMaterialType == KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC)
        val hpke = try {
            HpkePublicKey.parseFrom(data.value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid HPKE public key")
        }
        require(hpke.version == 0 && hpke.publicKey.size() == 32)
        require(hpke.params.kem == HpkeKem.DHKEM_X25519_HKDF_SHA256)
        require(hpke.params.kdf == HpkeKdf.HKDF_SHA256)
        require(hpke.params.aead == HpkeAead.AES_256_GCM)

        val parsed = try {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(bytes)
        } catch (_: GeneralSecurityException) {
            throw IllegalArgumentException("Not a valid public-only keyset")
        }
        require(MessageDigest.isEqual(
            bytes, TinkProtoKeysetFormat.serializeKeysetWithoutSecret(parsed),
        )) { "Noncanonical HPKE keyset bytes" }
        HybridConfig.register()
        parsed.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        return parsed
    }
}

/** Immutable, bounded receiver-key material as scanned on the intended TV. */
class ScannedTvHpkeKey internal constructor(
    val binding: LabBinding,
    publicKeyBytes: ByteArray,
    val publicKeyset: KeysetHandle,
) {
    private val serialized = publicKeyBytes.clone()
    val keyFingerprint: String = MessageDigest.getInstance("SHA-256").digest(serialized)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun encodedPublicKeyset(): ByteArray = serialized.clone()

    /** Only this QR-derived binding must be used when constructing a phone sender. */
    fun phoneSender(identity: LabPhoneIdentity): LabSignedHpkePhone =
        LabSignedHpkePhone(binding, keyFingerprint, identity, publicKeyset)

    override fun toString(): String = "ScannedTvHpkeKey([PUBLIC_KEY_FINGERPRINT_REDACTED])"
}

/**
 * Receiver creation must bind to an actually scanned/serialized matching HPKE keyset.
 * A made-up fingerprint or an unrelated receiving key is refused before any command.
 */
fun ScannedTvHpkeKey.tvVerifier(
    receiver: LabReceiver,
    enrollment: LabEnrollmentOutcome.ProofVerified,
    now: () -> Long = { System.nanoTime() / 1_000_000 },
): LabSignedHpkeTv {
    val receiverBytes = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(receiver.publicKeyset)
    require(MessageDigest.isEqual(receiverBytes, encodedPublicKeyset())) {
        "Scanned QR does not belong to this TV HPKE receiver"
    }
    return LabSignedHpkeTv(
        binding, keyFingerprint, receiver, enrollment, elapsedMillis = now,
    )
}
