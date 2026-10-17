package app.muxtv.localcontrol.tvqr

import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * LC04-S3-B: TV-owned, short-lived public-key QR preview.
 *
 * The private receiver is generated in memory and NEVER leaves this owner. This is deliberately
 * NOT a sender enrollment, authenticated transport, or SourceOnboarding authorization mechanism.
 * The two 128-bit context IDs in MXLC04:1 are random UNAPPROVED context markers, not LC03
 * bearer/session credentials. The separate trusted phone UI must independently check this QR.
 *
 * close() releases references but cannot promise JVM heap zeroization of Tink key material.
 */
class TvHpkeQrSession private constructor(
    private var privateReceiver: KeysetHandle?,
    private val qr: String,
    private val fingerprint: String,
    private val createdAt: Long,
    val expiresAtElapsedMillis: Long,
) : AutoCloseable {
    /** Do not expose the private key, decryptor, or unauthenticated channel endpoint. */
    @Synchronized
    fun presentation(nowElapsedMillis: Long): TvHpkeQrPresentation? {
        if (privateReceiver == null) return null
        if (nowElapsedMillis < createdAt || nowElapsedMillis >= expiresAtElapsedMillis) {
            close()
            return null
        }
        return TvHpkeQrPresentation(qr, fingerprint, expiresAtElapsedMillis)
    }

    @Synchronized
    override fun close() {
        privateReceiver = null
    }

    override fun toString(): String = "TvHpkeQrSession([EPHEMERAL_KEY_REDACTED])"

    companion object {
        const val TTL_MILLIS = 120_000L
        private const val ALGORITHM = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
        private const val QR_PREFIX = "MXLC04:1:"

        fun create(
            nowElapsedMillis: Long,
            random: SecureRandom = SecureRandom(),
        ): TvHpkeQrSession {
            require(nowElapsedMillis >= 0 && nowElapsedMillis <= Long.MAX_VALUE - TTL_MILLIS)
            HybridConfig.register()
            // Same production-candidate Tink suite qualified on canonical TV API26/API36 in #407.
            val keyset = KeysetHandle.newBuilder()
                .addEntry(
                    KeysetHandle.generateEntryFromParametersName(ALGORITHM)
                        .withRandomId()
                        .makePrimary(),
                ).build()
            // Fail at creation if the real private receiver primitive cannot be constructed.
            keyset.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            val publicBytes = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                keyset.publicKeysetHandle,
            )
            check(publicBytes.size in 1..512) { "TV HPKE public key exceeds canonical QR bound" }
            val invitationId = randomHex(random)
            val contextId = randomHex(random)
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(publicBytes)
            val qr = "$QR_PREFIX$invitationId:$contextId:$encoded"
            check(qr.length <= 900) { "TV HPKE QR exceeds canonical QR bound" }
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(publicBytes).toHex()
            return TvHpkeQrSession(
                privateReceiver = keyset,
                qr = qr,
                fingerprint = fingerprint,
                createdAt = nowElapsedMillis,
                expiresAtElapsedMillis = nowElapsedMillis + TTL_MILLIS,
            )
        }

        private fun randomHex(random: SecureRandom): String {
            val bytes = ByteArray(16).also(random::nextBytes)
            return try {
                bytes.toHex()
            } finally {
                bytes.fill(0)
            }
        }

        private fun ByteArray.toHex(): String {
            val chars = "0123456789abcdef"
            return buildString(size * 2) {
                for (byte in this@toHex) {
                    val unsigned = byte.toInt() and 255
                    append(chars[unsigned ushr 4])
                    append(chars[unsigned and 15])
                }
            }
        }
    }
}

/**
 * Public-only data intended for the actual TV screen. QR IDs are context markers, not a grant.
 * The QR can be scanned, but it CANNOT be used to communicate with the TV yet.
 */
class TvHpkeQrPresentation(
    val qrValue: String,
    val fingerprintSha256: String,
    val expiresAtElapsedMillis: Long,
) {
    override fun toString(): String = "TvHpkeQrPresentation(qrValue=[REDACTED])"
}
