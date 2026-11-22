package app.muxtv.localcontrol.mxh1

import app.muxtv.localcontrol.enrollment.TvPhoneEnrollment
import app.muxtv.localcontrol.enrollment.VerifiedPhoneIdentity
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticDerivedKeys
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticHandoffKeySchedule
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticHandoffTranscript
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.wirelab.Lc04HandoffFrame
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import app.muxtv.localcontrol.wirelab.Lc04HandoffWireLab
import app.muxtv.localcontrol.wirelab.Lc04MxH1HpkeContext
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * LC04-S2E JVM-ONLY RESEARCH OWNER. The owner creates and retains the actual
 * ephemeral TV HPKE private key and its local Ed25519 approval gate. No
 * caller-supplied identity, QR, bearer, IP, fake consent callback or pre-shared
 * seed may activate the cryptographic state.
 *
 * This is deliberately NOT connected to TV Android, phone Android, native
 * TCP, provider operations or source ingestion. Its final boolean signals
 * synthetic mutual proof ONLY, not any durable grant or data-plane authority.
 */
class Lc04MxH1TvOwnerLab private constructor(
    receiver: KeysetHandle,
    val publicQr: String,
    private val createdAt: Long,
    private val elapsedMillis: () -> Long,
    private val random: SecureRandom,
) : AutoCloseable {
    private enum class Phase { NEW, WAIT_PHONE, VERIFIED, REVOKED }

    private val qr = TvQrPublicKeyParser.parse(publicQr)
    @Volatile private var revoked = false
    private var phase = Phase.NEW
    private var privateReceiver: KeysetHandle? = receiver
    private var approvedVerifier: PublicKeyVerify? = null
    private var approvedFingerprint: String? = null
    private var consentGate: TvPhoneEnrollment? = null
    private var acceptedOffer: ByteArray? = null
    private var keys: Lc04SyntheticDerivedKeys? = null

    private fun live(): Boolean {
        val now = elapsedMillis()
        return !revoked && now >= createdAt && now - createdAt < TTL_MILLIS
    }

    /** The *only* approval source is the gate belonging to this private QR. */
    @Synchronized
    fun tvConsentGate(): TvPhoneEnrollment? {
        if (!live() || phase != Phase.NEW) {
            close()
            return null
        }
        val old = consentGate
        if (old != null) return old
        return TvPhoneEnrollment(
            tvQr = publicQr,
            elapsedMillis = elapsedMillis,
            tvReceiverIsAlive = { live() },
        ).also { consentGate = it }
    }

    /**
     * TV local UI must have called approveOnTv() on THIS gate, followed by a
     * valid Ed25519 challenge proof. A remote caller cannot construct a
     * VerifiedPhoneIdentity using only a claimed fingerprint.
     */
    @Synchronized
    fun activateApprovedPhone(identity: VerifiedPhoneIdentity): Boolean {
        if (!live() || phase != Phase.NEW || approvedVerifier != null) return false
        val gate = consentGate ?: return false
        if (gate.verifiedFingerprintForTv() != identity.fingerprint ||
            identity.tvInvitationId != qr.invitationId ||
            identity.tvPairingId != qr.pairingId ||
            identity.tvFingerprint != qr.fingerprint
        ) return false
        val verifier = try {
            SignatureConfig.register()
            TinkProtoKeysetFormat.parseKeysetWithoutSecret(identity.publicKeysetBytes())
                .getPrimitive(RegistryConfiguration.get(), PublicKeyVerify::class.java)
        } catch (_: GeneralSecurityException) {
            return false
        } catch (_: IllegalArgumentException) {
            return false
        }
        approvedFingerprint = identity.fingerprint
        approvedVerifier = verifier
        return true
    }

    /**
     * One chance per TV QR. Check the exact Ed25519 statement BEFORE
     * decrypting with the QR-private HPKE key; use a fresh MXH1-only context,
     * never MXW1 CHANNEL_PROBE. A replay/mixup/tamper revokes the entire owner.
     */
    @Synchronized
    fun acceptSignedSeedOffer(offerWire: ByteArray): ByteArray? {
        if (!live() || phase != Phase.NEW) return reject()
        val verifier = approvedVerifier ?: return reject()
        val approved = approvedFingerprint ?: return reject()
        val receiver = privateReceiver ?: return reject()
        val frame = Lc04HandoffWireLab.decode(offerWire) ?: return reject()
        if (frame.kind != Lc04HandoffKind.SIGNED_SEED_OFFER ||
            !MessageDigest.isEqual(frame.invitation(), fromHex(qr.invitationId)) ||
            !MessageDigest.isEqual(frame.pairing(), fromHex(qr.pairingId)) ||
            !MessageDigest.isEqual(frame.tv(), fromHex(qr.fingerprint)) ||
            !MessageDigest.isEqual(frame.phone(), fromHex(approved))
        ) return reject()
        val parsed = Lc04HandoffWireLab.decodeSignedOfferBody(frame.payload()) ?: return reject()
        val (ciphertext, signature) = parsed
        val statement = Lc04HandoffWireLab.offerSigningStatement(offerWire) ?: return reject()
        val signed = try {
            verifier.verify(signature, statement)
            true
        } catch (_: GeneralSecurityException) {
            false
        } finally {
            signature.fill(0)
            statement.fill(0)
        }
        if (!signed) {
            ciphertext.fill(0)
            return reject()
        }
        val hpkeContext = Lc04MxH1HpkeContext.forOffer(frame)
        val secret = try {
            HybridConfig.register()
            receiver.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
                .decrypt(ciphertext, hpkeContext)
        } catch (_: GeneralSecurityException) {
            ciphertext.fill(0)
            return reject()
        } finally {
            hpkeContext.fill(0)
            ciphertext.fill(0)
        }
        if (secret.size != 32 || secret.all { it == 0.toByte() }) {
            secret.fill(0)
            return reject()
        }
        val digest = Lc04HandoffWireLab.digestOfSignedOffer(offerWire) ?: return reject()
        val nonce = ByteArray(32).also(random::nextBytes)
        try {
            val context = Lc04SyntheticHandoffTranscript(
                frame.invitation(), frame.pairing(), frame.tv(), frame.phone(),
                digest, nonce,
            )
            val derived = Lc04SyntheticHandoffKeySchedule.derive(secret, context)
            val tag = derived.syntheticTvConfirmation()
            if (tag == null) {
                derived.close()
                return reject()
            }
            try {
                val reply = Lc04HandoffWireLab.encode(Lc04HandoffFrame(
                    Lc04HandoffKind.TV_KEY_CONFIRM,
                    frame.invitation(), frame.pairing(), frame.tv(), frame.phone(),
                    digest, frame.counter,
                    Lc04HandoffWireLab.tvConfirmationBody(nonce, tag),
                ))
                acceptedOffer = offerWire.clone()
                keys = derived
                phase = Phase.WAIT_PHONE
                return reply
            } finally { tag.fill(0) }
        } catch (_: IllegalArgumentException) {
            return reject()
        } finally {
            secret.fill(0)
            digest.fill(0)
            nonce.fill(0)
        }
    }

    /** Accept exactly one matching, authenticated PHONE_KEY_CONFIRM. */
    @Synchronized
    fun acceptPhoneKeyConfirmation(phoneConfirmation: ByteArray): Boolean {
        if (!live() || phase != Phase.WAIT_PHONE) return rejectBoolean()
        val original = acceptedOffer ?: return rejectBoolean()
        if (!Lc04HandoffWireLab.structurallyMatchesOffer(
                original, phoneConfirmation, Lc04HandoffKind.PHONE_KEY_CONFIRM,
            )
        ) return rejectBoolean()
        val frame = Lc04HandoffWireLab.decode(phoneConfirmation) ?: return rejectBoolean()
        val tag = frame.payload()
        return try {
            if (keys?.verifySyntheticPhoneConfirmation(tag) != true) {
                rejectBoolean()
            } else {
                phase = Phase.VERIFIED
                acceptedOffer?.fill(0)
                acceptedOffer = null
                true
            }
        } finally { tag.fill(0) }
    }

    @Synchronized
    fun hasMutualSyntheticProof(): Boolean {
        if (!live()) {
            close()
            return false
        }
        return phase == Phase.VERIFIED
    }

    private fun reject(): ByteArray? {
        close()
        return null
    }

    private fun rejectBoolean(): Boolean {
        close()
        return false
    }

    @Synchronized
    override fun close() {
        if (revoked) return
        revoked = true
        phase = Phase.REVOKED
        privateReceiver = null
        approvedVerifier = null
        approvedFingerprint = null
        acceptedOffer?.fill(0)
        acceptedOffer = null
        keys?.close()
        keys = null
        consentGate?.close()
        consentGate = null
    }

    override fun toString(): String = "Lc04MxH1TvOwnerLab([REDACTED])"

    companion object {
        const val TTL_MILLIS = 120_000L
        private const val ALGORITHM = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"

        fun create(
            nowElapsedMillis: Long,
            elapsedMillis: () -> Long,
            random: SecureRandom = SecureRandom(),
        ): Lc04MxH1TvOwnerLab {
            require(nowElapsedMillis >= 0 && nowElapsedMillis <= Long.MAX_VALUE - TTL_MILLIS)
            HybridConfig.register()
            val keyset = KeysetHandle.newBuilder().addEntry(
                KeysetHandle.generateEntryFromParametersName(ALGORITHM)
                    .withRandomId().makePrimary(),
            ).build()
            keyset.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
            val public = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(
                keyset.publicKeysetHandle,
            )
            require(public.size in 1..512)
            fun randomHex(): String {
                val data = ByteArray(16).also(random::nextBytes)
                return try {
                    data.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
                } finally { data.fill(0) }
            }
            val publicQr = "MXLC04:1:${randomHex()}:${randomHex()}:" +
                Base64.getUrlEncoder().withoutPadding().encodeToString(public)
            TvQrPublicKeyParser.parse(publicQr)
            return Lc04MxH1TvOwnerLab(keyset, publicQr, nowElapsedMillis, elapsedMillis, random)
        }
    }
}

private fun fromHex(value: String): ByteArray {
    require(value.length % 2 == 0 && value.all {
        it in '0'..'9' || it in 'a'..'f'
    })
    return ByteArray(value.length / 2) {
        ((value[2 * it].digitToInt(16) shl 4) or
            value[2 * it + 1].digitToInt(16)).toByte()
    }
}
