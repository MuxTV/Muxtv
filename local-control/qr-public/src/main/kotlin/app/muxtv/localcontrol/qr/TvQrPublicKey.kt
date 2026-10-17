package app.muxtv.localcontrol.qr

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
import java.security.MessageDigest
import java.util.Base64

/**
 * Read-only identity parsed from the ACTUAL TV public HPKE key QR (MXLC04:1).
 * Possession of this record alone does not authenticate either the physical TV or its
 * owner. No pairing, network or provider-credential API can be reached from this module.
 */
class TvQrPublicKey internal constructor(
    val invitationId: String,
    val pairingId: String,
    publicKey: ByteArray,
    val publicKeyset: KeysetHandle,
) {
    private val encodedKey = publicKey.clone()
    val fingerprint: String = MessageDigest.getInstance("SHA-256")
        .digest(encodedKey).toHex()

    /** No secret keys can be present in this public-only serialized keyset. */
    fun publicKeysetBytes(): ByteArray = encodedKey.clone()

    override fun toString(): String = "TvQrPublicKey([PUBLIC_KEY_REDACTED])"
}

/**
 * Canonical format frozen by research/#405. A reject never contains scanned raw text,
 * provider tokens, or a partially parsed key. No browser URLs accepted here.
 */
object TvQrPublicKeyParser {
    private const val PREFIX = "MXLC04:1:"
    private const val MAX_QR_CHARACTERS = 900
    private const val MAX_KEY_BYTES = 512
    private const val HPKE_TYPE = "type.googleapis.com/google.crypto.tink.HpkePublicKey"
    private val LOWER_HEX_128 = Regex("[0-9a-f]{32}")
    private val UNPADDED_BASE64URL = Regex("[A-Za-z0-9_-]+")

    fun parseOrNull(value: String): TvQrPublicKey? = try {
        parse(value)
    } catch (_: Exception) {
        null
    }

    @Throws(IllegalArgumentException::class)
    fun parse(value: String): TvQrPublicKey {
        require(value.length in 1..MAX_QR_CHARACTERS && value.startsWith(PREFIX)) {
            "Unsupported TV QR format."
        }
        val pieces = value.removePrefix(PREFIX).split(':')
        require(pieces.size == 3)
        val invitation = pieces[0]
        val pairing = pieces[1]
        val text = pieces[2]
        require(LOWER_HEX_128.matches(invitation) && LOWER_HEX_128.matches(pairing))
        require(text.length in 1..684 && UNPADDED_BASE64URL.matches(text))
        val raw = try {
            Base64.getUrlDecoder().decode(text)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid TV public key encoding.")
        }
        require(raw.size in 1..MAX_KEY_BYTES)
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(raw) == text) {
            "Noncanonical TV public key encoding."
        }
        val proto = try {
            Keyset.parseFrom(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid TV public keyset.")
        }
        require(proto.keyCount == 1 && proto.primaryKeyId == proto.getKey(0).keyId)
        val primary = proto.getKey(0)
        require(primary.status == KeyStatusType.ENABLED)
        require(primary.keyData.typeUrl == HPKE_TYPE &&
            primary.keyData.keyMaterialType == KeyData.KeyMaterialType.ASYMMETRIC_PUBLIC)

        val key = try {
            HpkePublicKey.parseFrom(primary.keyData.value)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid TV HPKE public key.")
        }
        require(key.version == 0 && key.publicKey.size() == 32)
        require(key.params.kem == HpkeKem.DHKEM_X25519_HKDF_SHA256)
        require(key.params.kdf == HpkeKdf.HKDF_SHA256)
        require(key.params.aead == HpkeAead.AES_256_GCM)

        val handle = try {
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(raw)
        } catch (_: Exception) {
            throw IllegalArgumentException("Cannot parse public-only HPKE keyset.")
        }
        require(MessageDigest.isEqual(
            raw, TinkProtoKeysetFormat.serializeKeysetWithoutSecret(handle),
        ))
        try {
            HybridConfig.register()
            handle.getPrimitive(RegistryConfiguration.get(), HybridEncrypt::class.java)
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid HPKE public-key primitive.")
        }
        return TvQrPublicKey(invitation, pairing, raw, handle)
    }
}

private fun ByteArray.toHex(): String {
    val alphabet = "0123456789abcdef"
    return buildString(size * 2) {
        for (b in this@toHex) {
            val n = b.toInt() and 0xff
            append(alphabet[n ushr 4])
            append(alphabet[n and 15])
        }
    }
}
