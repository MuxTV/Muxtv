package app.muxtv.localcontrol.tvqr

import app.muxtv.localcontrol.enrollment.TvPhoneEnrollment
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.enrollment.VerifiedPhoneIdentity
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticDerivedKeys
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticHandoffKeySchedule
import app.muxtv.localcontrol.protectedchannel.Lc04SyntheticHandoffTranscript
import app.muxtv.localcontrol.wirelab.Lc04HandoffFrame
import app.muxtv.localcontrol.wirelab.Lc04HandoffKind
import app.muxtv.localcontrol.wirelab.Lc04HandoffWireLab
import app.muxtv.localcontrol.wirelab.Lc04MxH1HpkeContext
import app.muxtv.localcontrol.wirelab.Lc04HpkeContext
import app.muxtv.localcontrol.wirelab.Lc04ReceiptBody
import app.muxtv.localcontrol.wirelab.Lc04TypedTranscript
import app.muxtv.localcontrol.wirelab.Lc04TypedWire
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.crypto.tink.PublicKeyVerify
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
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
    @Volatile private var privateReceiver: KeysetHandle?,
    private val qr: String,
    private val fingerprint: String,
    private val createdAt: Long,
    val expiresAtElapsedMillis: Long,
) : AutoCloseable {
    private var pendingPhoneEnrollment: TvPhoneEnrollment? = null
    private var approvedPhoneFingerprint: String? = null
    private var approvedPhoneVerifier: PublicKeyVerify? = null
    private var lastVerifiedProbeCounter = 0

    private enum class MxH1Phase { NEW, WAIT_PHONE, VERIFIED, REVOKED }
    // Same QR, private HPKE receiver and TV-approved Ed25519 verifier as MXW1.
    // These fields NEVER grant SourceOnboarding, bearer or application authority.
    private var mxh1Phase = MxH1Phase.NEW
    private var mxh1AcceptedOffer: ByteArray? = null
    private var mxh1Keys: Lc04SyntheticDerivedKeys? = null


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

    /**
     * The exact receiver that owns this public QR also owns the phone enrollment gate.
     * A separate caller-supplied QR string cannot manufacture a TV-approved identity.
     * This is intentionally NOT called by the legacy plaintext LAN server.
     */
    @Synchronized
    fun phoneEnrollmentGate(elapsedMillis: () -> Long): TvPhoneEnrollment? {
        if (presentation(elapsedMillis()) == null) return null
        val existing = pendingPhoneEnrollment
        if (existing != null) return existing
        return TvPhoneEnrollment(
            tvQr = qr,
            elapsedMillis = elapsedMillis,
            // Do not call a synchronized TV session method while holding the synchronized
            // enrollment gate lock: session.close() takes locks in the opposite order.
            tvReceiverIsAlive = {
                val now = elapsedMillis()
                privateReceiver != null &&
                    now >= createdAt && now < expiresAtElapsedMillis
            },
        ).also { pendingPhoneEnrollment = it }
    }

    /**
     * Complete the TV-local enrollment handoff: only the identity actually
     * redeemed by THIS QR receiver's consent/proof gate can activate the
     * public-key verifier. A parsed phone offer alone cannot activate it.
     */
    @Synchronized
    fun activateVerifiedPhone(identity: VerifiedPhoneIdentity, nowElapsedMillis: Long): Boolean {
        if (presentation(nowElapsedMillis) == null || approvedPhoneVerifier != null) return false
        val gate = pendingPhoneEnrollment ?: return false
        if (gate.verifiedFingerprintForTv() != identity.fingerprint) return false
        val tv = TvQrPublicKeyParser.parse(qr)
        if (identity.tvInvitationId != tv.invitationId ||
            identity.tvPairingId != tv.pairingId ||
            identity.tvFingerprint != tv.fingerprint
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
        approvedPhoneFingerprint = identity.fingerprint
        approvedPhoneVerifier = verifier
        lastVerifiedProbeCounter = 0
        return true
    }

    /**
     * LC04-S2F, in-process SYNTHETIC ONLY. Reuse THIS installed TV QR owner's
     * HPKE private key and exact TV-local-approved Ed25519 phone identity.
     * No alternative QR, TV consent gate, LAN socket, source or credentials.
     *
     * One MXH1 offer may be authenticated for this QR lifetime. All malicious
     * inputs fail closed and revoke the entire QR owner, including MXW1.
     */
    @Synchronized
    fun acceptSyntheticMxH1SeedOffer(wire: ByteArray, nowElapsedMillis: Long): ByteArray? {
        if (presentation(nowElapsedMillis) == null ||
            mxh1Phase != MxH1Phase.NEW
        ) return revokeMxH1()
        val receiver = privateReceiver ?: return revokeMxH1()
        val verifier = approvedPhoneVerifier ?: return revokeMxH1()
        val approved = approvedPhoneFingerprint ?: return revokeMxH1()
        if (pendingPhoneEnrollment?.verifiedFingerprintForTv() != approved) {
            return revokeMxH1()
        }
        val frame = Lc04HandoffWireLab.decode(wire) ?: return revokeMxH1()
        val tv = TvQrPublicKeyParser.parse(qr)
        if (frame.kind != Lc04HandoffKind.SIGNED_SEED_OFFER ||
            !MessageDigest.isEqual(frame.invitation(), fromHex(tv.invitationId)) ||
            !MessageDigest.isEqual(frame.pairing(), fromHex(tv.pairingId)) ||
            !MessageDigest.isEqual(frame.tv(), fromHex(tv.fingerprint)) ||
            !MessageDigest.isEqual(frame.phone(), fromHex(approved))
        ) return revokeMxH1()

        val offerBody = Lc04HandoffWireLab.decodeSignedOfferBody(frame.payload())
            ?: return revokeMxH1()
        val ciphertext = offerBody.first
        val signature = offerBody.second
        val statement = Lc04HandoffWireLab.offerSigningStatement(wire)
            ?: return revokeMxH1()
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
            return revokeMxH1()
        }

        val info = Lc04MxH1HpkeContext.forOffer(frame)
        val seed = try {
            receiver.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
                .decrypt(ciphertext, info)
        } catch (_: GeneralSecurityException) {
            return revokeMxH1()
        } finally {
            info.fill(0)
            ciphertext.fill(0)
        }
        try {
            if (seed.size != 32 || seed.all { it == 0.toByte() }) return revokeMxH1()
            val digest = Lc04HandoffWireLab.digestOfSignedOffer(wire)
                ?: return revokeMxH1()
            val nonce = ByteArray(32).also(SecureRandom()::nextBytes)
            try {
                val transcript = Lc04SyntheticHandoffTranscript(
                    frame.invitation(), frame.pairing(), frame.tv(), frame.phone(),
                    digest, nonce,
                )
                val newKeys = Lc04SyntheticHandoffKeySchedule.derive(seed, transcript)
                val tvTag = newKeys.syntheticTvConfirmation()
                    ?: run { newKeys.close(); return revokeMxH1() }
                try {
                    val confirmation = Lc04HandoffWireLab.encode(Lc04HandoffFrame(
                        Lc04HandoffKind.TV_KEY_CONFIRM,
                        frame.invitation(), frame.pairing(), frame.tv(), frame.phone(),
                        digest, frame.counter,
                        Lc04HandoffWireLab.tvConfirmationBody(nonce, tvTag),
                    ))
                    mxh1AcceptedOffer = wire.clone()
                    mxh1Keys = newKeys
                    mxh1Phase = MxH1Phase.WAIT_PHONE
                    return confirmation
                } finally { tvTag.fill(0) }
            } catch (_: GeneralSecurityException) {
                return revokeMxH1()
            } catch (_: IllegalArgumentException) {
                return revokeMxH1()
            } finally {
                digest.fill(0)
                nonce.fill(0)
            }
        } finally { seed.fill(0) }
    }

    /**
     * Verify the real independent phone key-confirmation MAC, never just the
     * public MXH1 digest. Success is a SYNTHETIC proof bit only. The derived
     * keys are immediately revoked: this method provides no data-plane key.
     */
    @Synchronized
    fun acceptSyntheticMxH1PhoneConfirmation(
        confirmation: ByteArray,
        nowElapsedMillis: Long,
    ): Boolean {
        if (presentation(nowElapsedMillis) == null ||
            mxh1Phase != MxH1Phase.WAIT_PHONE
        ) return revokeMxH1Boolean()
        val original = mxh1AcceptedOffer ?: return revokeMxH1Boolean()
        if (!Lc04HandoffWireLab.structurallyMatchesOffer(
                original, confirmation, Lc04HandoffKind.PHONE_KEY_CONFIRM,
            )
        ) return revokeMxH1Boolean()
        val parsed = Lc04HandoffWireLab.decode(confirmation)
            ?: return revokeMxH1Boolean()
        val tag = parsed.payload()
        return try {
            if (mxh1Keys?.verifySyntheticPhoneConfirmation(tag) != true) {
                revokeMxH1Boolean()
            } else {
                // No production MXH1 data transport exists: discard the
                // derived keys rather than retaining a dormant capability.
                mxh1Keys?.close()
                mxh1Keys = null
                mxh1AcceptedOffer?.fill(0)
                mxh1AcceptedOffer = null
                mxh1Phase = MxH1Phase.VERIFIED
                true
            }
        } finally { tag.fill(0) }
    }

    /** Read-only diagnostic, not a pairing grant or source authorization. */
    @Synchronized
    fun hasSyntheticMxH1Proof(nowElapsedMillis: Long): Boolean =
        presentation(nowElapsedMillis) != null && mxh1Phase == MxH1Phase.VERIFIED

    private fun revokeMxH1(): ByteArray? {
        close()
        return null
    }

    private fun revokeMxH1Boolean(): Boolean {
        close()
        return false
    }

    /**
     * In-process protected CHANNEL_PROBE receiver; NOT a network listener.
     * Ed25519 signature and exact QR owner / approved phone binding are
     * verified BEFORE invoking the ephemeral Tink HPKE private receiver.
     * Reply contains only an authenticated receipt, no source capability.
     */
    @Synchronized
    fun acceptProtectedProbe(wire: ByteArray, nowElapsedMillis: Long): ByteArray? {
        if (presentation(nowElapsedMillis) == null ||
            mxh1Phase != MxH1Phase.NEW
        ) return null
        val hpkeReceiver = privateReceiver ?: return null
        val verifier = approvedPhoneVerifier ?: return null
        val approvedFp = approvedPhoneFingerprint ?: return null
        val frame = Lc04WireLab.decode(wire) ?: return null
        if (frame.kind != Lc04WireKind.SIGNED_CHANNEL_PROBE) return null
        val tv = TvQrPublicKeyParser.parse(qr)
        if (!MessageDigest.isEqual(frame.tvInvitationId(), fromHex(tv.invitationId)) ||
            !MessageDigest.isEqual(frame.tvPairingId(), fromHex(tv.pairingId)) ||
            !MessageDigest.isEqual(frame.tvHpkeFingerprint(), fromHex(tv.fingerprint)) ||
            !MessageDigest.isEqual(frame.phoneKeyFingerprint(), fromHex(approvedFp))
        ) return null
        val typed = Lc04TypedWire.decodeProbeFrame(frame) ?: return null
        if (typed.counter <= lastVerifiedProbeCounter) return null
        val signature = typed.signature()
        val statement = Lc04TypedTranscript.probeSigningStatement(frame, typed)
        val signed = try {
            verifier.verify(signature, statement)
            true
        } catch (_: GeneralSecurityException) {
            false
        } finally {
            signature.fill(0)
            statement.fill(0)
        }
        if (!signed) return null
        val contextInfo = Lc04HpkeContext.forProbe(frame, typed.counter)
        val cipher = typed.ciphertext()
        val plain = try {
            hpkeReceiver.getPrimitive(RegistryConfiguration.get(), HybridDecrypt::class.java)
                .decrypt(cipher, contextInfo)
        } catch (_: GeneralSecurityException) {
            return null
        } finally {
            contextInfo.fill(0)
            cipher.fill(0)
        }
        if (plain.size != 48) {
            plain.fill(0)
            return null
        }
        try {
            val key = plain.copyOfRange(0, 32)
            val challenge = plain.copyOfRange(32, 48)
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val ack = "MuxTV-TV-RECEIVED-v1|".toByteArray(Charsets.US_ASCII) + challenge
            val digest = Lc04TypedTranscript.originalSignedRequestDigest(frame)
            try {
                val placeholder = Lc04ReceiptBody(typed.counter, digest, iv,
                    ByteArray(ack.size + 16))
                val header = Lc04WireFrame(Lc04WireKind.SEALED_TV_RECEIPT,
                    frame.tvInvitationId(), frame.tvPairingId(),
                    frame.tvHpkeFingerprint(), frame.phoneKeyFingerprint(),
                    Lc04TypedWire.encodeReceipt(placeholder))
                val aad = Lc04TypedTranscript.receiptAad(header, placeholder)
                val sealed = try {
                    val encryptor = Cipher.getInstance("AES/GCM/NoPadding")
                    encryptor.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                        GCMParameterSpec(128, iv))
                    encryptor.updateAAD(aad)
                    encryptor.doFinal(ack)
                } finally { aad.fill(0) }
                val reply = Lc04WireLab.encode(Lc04WireFrame(
                    Lc04WireKind.SEALED_TV_RECEIPT,
                    frame.tvInvitationId(), frame.tvPairingId(),
                    frame.tvHpkeFingerprint(), frame.phoneKeyFingerprint(),
                    Lc04TypedWire.encodeReceipt(
                        Lc04ReceiptBody(typed.counter, digest, iv, sealed)),
                ))
                lastVerifiedProbeCounter = typed.counter
                return reply
            } finally {
                key.fill(0)
                challenge.fill(0)
                iv.fill(0)
                ack.fill(0)
                digest.fill(0)
            }
        } finally { plain.fill(0) }
    }

    @Synchronized
    override fun close() {
        mxh1Phase = MxH1Phase.REVOKED
        mxh1Keys?.close()
        mxh1Keys = null
        mxh1AcceptedOffer?.fill(0)
        mxh1AcceptedOffer = null
        pendingPhoneEnrollment?.close()
        pendingPhoneEnrollment = null
        approvedPhoneFingerprint = null
        approvedPhoneVerifier = null
        lastVerifiedProbeCounter = 0
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

private fun fromHex(value: String): ByteArray {
    require(value.length % 2 == 0 &&
        value.all { it in '0'..'9' || it in 'a'..'f' })
    return ByteArray(value.length / 2) { i ->
        ((value[2 * i].digitToInt(16) shl 4) or
            value[2 * i + 1].digitToInt(16)).toByte()
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
