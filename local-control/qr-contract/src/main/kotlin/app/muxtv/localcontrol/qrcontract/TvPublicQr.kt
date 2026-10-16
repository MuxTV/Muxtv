package app.muxtv.localcontrol.qrcontract

import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkeKdf
import com.google.crypto.tink.proto.HpkeKem
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.KeyStatusType
import com.google.crypto.tink.proto.Keyset
import java.security.MessageDigest
import java.util.Base64

/**
 * Production-intended public QR parser only. This is **not** a pairing/session grant.
 * A fingerprint from an unverified QR does NOT prove the QR came from the intended TV.
 *
 * No network, provider credentials, phone private keys or access tokens cross this API.
 * Keys stay public: the TV must show the same fingerprint to the user independently.
 */
object TvPublicQr {
    private const val PREFIX = "MXLC04:1:"
    private const val TYPE_HPKE = "type.googleapis.com/google.crypto.tink.HpkePublicKey"
    private val id = Regex("[0-9a-f]{32}")
    private val base64 = Regex("[A-Za-z0-9_-]+")

    fun inspect(text: String): TvQrPreview {
        require(text.length in 70..900 && text.startsWith(PREFIX)) { "Invalid QR version" }
        val fields = text.removePrefix(PREFIX).split(':')
        require(fields.size == 3 && id.matches(fields[0]) && id.matches(fields[1])) {
            "Invalid public TV identifiers"
        }
        val encoded = fields[2]
        require(encoded.length in 1..684 && base64.matches(encoded)) {
            "Invalid QR key encoding"
        }
        val bytes = try {
            Base64.getUrlDecoder().decode(encoded)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid public key encoding")
        }
        require(bytes.size in 1..512 &&
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == encoded) {
            "Noncanonical QR public key encoding"
        }

        val keyset = try {
            Keyset.parseFrom(bytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid public HPKE keyset")
        }
        require(keyset.keyCount == 1 && keyset.primaryKeyId == keyset.getKey(0).keyId)
        val key = keyset.getKey(0)
        require(key.status == KeyStatusType.ENABLED)
        require(key.keyData.typeUrl == TYPE_HPKE &&
            key.keyData.keyMaterialType == KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC)
        val hpke = try {
            HpkePublicKey.parseFrom(key.keyData.value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid HPKE public key")
        }
        require(hpke.version == 0 && hpke.publicKey.size() == 32)
        require(hpke.params.kem == HpkeKem.DHKEM_X25519_HKDF_SHA256)
        require(hpke.params.kdf == HpkeKdf.HKDF_SHA256)
        require(hpke.params.aead == HpkeAead.AES_256_GCM)
        val publicHandle = try {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(bytes)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid public-only keyset")
        }
        require(MessageDigest.isEqual(
            TinkProtoKeysetFormat.serializeKeysetWithoutSecret(publicHandle), bytes,
        )) { "Noncanonical public keyset" }
        // A fingerprint is an identifier, not proof of a trusted TV.
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return TvQrPreview(fields[0], fields[1], hash.joinToString("") {
            "%02x".format(it.toInt() and 255)
        })
    }
}

/** Does not expose the QR public key, pairing secret or any provider configuration. */
data class TvQrPreview(
    val invitationId: String,
    val pairingId: String,
    val fingerprintSha256: String,
) {
    override fun toString(): String = "TvQrPreview([PUBLIC_ID_REDACTED])"
}
