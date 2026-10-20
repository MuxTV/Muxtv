package app.muxtv.localcontrol.enrollment

import app.muxtv.localcontrol.qr.TvQrPublicKey
import app.muxtv.localcontrol.wirelab.Lc04HpkeContext
import app.muxtv.localcontrol.wirelab.Lc04OneShotReplyGuard
import app.muxtv.localcontrol.wirelab.Lc04ProbeBody
import app.muxtv.localcontrol.wirelab.Lc04TypedWire
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.hybrid.HybridConfig
import java.security.SecureRandom

/**
 * Native Android/JVM crypto component, NOT a socket or SourceOnboarding client.
 * An attacker may carry/rewrite serialized bytes, but cannot derive the random
 * AES-256-GCM reply key which is delivered ONLY under the scanned TV's HPKE key.
 *
 * The real TV must separately verify TV-local consent, Ed25519 proof and
 * the entire signed probe before sending an authenticated TV reply.
 */
class PhoneProtectedProbeFactory(
    private val scannedTv: TvQrPublicKey,
    private val signingPhone: PhoneSigningIdentity,
    private val elapsedMillis: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) : AutoCloseable {
    private var counter = 0
    private var closed = false

    @Synchronized
    fun create(): PhonePendingProtectedProbe {
        check(!closed) { "Phone protected channel was revoked." }
        check(counter < 128) { "QR session probe counter capacity exceeded." }
        val now = elapsedMillis()
        require(now >= 0)
        val currentCounter = counter + 1
        val secret = ByteArray(32).also(random::nextBytes)
        val challenge = ByteArray(16).also(random::nextBytes)
        val plain = secret + challenge
        try {
            val tvFingerprint = rawHex(scannedTv.fingerprint)
            val phoneFingerprint = rawHex(signingPhone.fingerprint)
            // A placeholder is used only for HPKE context derivation. The
            // context excludes the ciphertext and signature, which are not
            // available until encryption and signing respectively.
            val draft = Lc04WireFrame(
                Lc04WireKind.SIGNED_CHANNEL_PROBE,
                rawHex(scannedTv.invitationId), rawHex(scannedTv.pairingId),
                tvFingerprint, phoneFingerprint,
                Lc04TypedWire.encodeProbe(
                    Lc04ProbeBody(currentCounter, ByteArray(17), ByteArray(69)),
                ),
            )
            val context = Lc04HpkeContext.forProbe(draft, currentCounter)
            val encrypted = try {
                HybridConfig.register()
                val encryptor = scannedTv.publicKeyset.getPrimitive(
                    RegistryConfiguration.get(), HybridEncrypt::class.java,
                )
                encryptor.encrypt(plain, context)
            } finally {
                context.fill(0)
            }
            val signedRequest = try {
                signingPhone.signProtectedProbe(scannedTv, currentCounter, encrypted)
            } finally {
                encrypted.fill(0)
            }
            val frame = checkNotNull(Lc04WireLab.decode(signedRequest))
            val guard = Lc04OneShotReplyGuard(frame, secret, challenge, now, elapsedMillis)
            counter = currentCounter
            return PhonePendingProtectedProbe(signedRequest, guard)
        } finally {
            plain.fill(0)
            secret.fill(0)
            challenge.fill(0)
        }
    }

    @Synchronized
    override fun close() { closed = true }

    override fun toString(): String = "PhoneProtectedProbeFactory([PRIVATE_MATERIAL_REDACTED])"
}

/** Owns the original exact signed probe and a single-use authenticated reply guard. */
class PhonePendingProtectedProbe internal constructor(
    signedRequest: ByteArray,
    private val replyGuard: Lc04OneShotReplyGuard,
) : AutoCloseable {
    private val request = signedRequest.clone()
    fun requestBytes(): ByteArray = request.clone()

    /** A malformed packet cannot be mistaken for a successful TV receipt. */
    fun verifyReply(receivedWire: ByteArray): Boolean {
        val frame = Lc04WireLab.decode(receivedWire) ?: return false
        return replyGuard.verify(frame)
    }

    fun isPending(): Boolean = replyGuard.isPending()
    override fun close() = replyGuard.close()
    override fun toString(): String = "PhonePendingProtectedProbe([REDACTED])"
}

private fun rawHex(value: String): ByteArray {
    require(value.length % 2 == 0 &&
        value.all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(value.length / 2) { i ->
        ((value[2 * i].digitToInt(16) shl 4) or value[2 * i + 1].digitToInt(16)).toByte()
    }
}
