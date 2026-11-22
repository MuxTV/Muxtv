package app.muxtv.localcontrol.enrollment

import app.muxtv.localcontrol.qr.TvQrPublicKey
import app.muxtv.localcontrol.wirelab.Lc04WireFrame
import app.muxtv.localcontrol.wirelab.Lc04WireKind
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import java.security.MessageDigest

/**
 * LC04-S3-E1: PUBLIC-only, strictly typed enrollment adapter for the TV QR context.
 *
 * This adapter deliberately has no transport, endpoint, permissions or source APIs.
 * MXW1 remains an experimental wire format until the end-to-end Android protocol
 * has been reviewed and frozen. A decoded frame grants no trust: verified identity
 * requires a fresh TV challenge, TV-local fingerprint consent and Tink Ed25519 proof.
 */
object Lc04PublicEnrollmentWire {
    /** Phone → TV. No passwords, bearer secrets or provider data. */
    fun phoneOffer(scannedTv: TvQrPublicKey, phone: PhoneSigningIdentity): ByteArray {
        val public = phone.publicKeysetBytes()
        require(public.size in 1..256)
        return encode(Lc04WireKind.PHONE_PUBLIC_KEY, context(
            scannedTv.invitationId, scannedTv.pairingId, scannedTv.fingerprint,
            phone.fingerprint, public,
        ))
    }

    /** TV → phone. The challenge is PUBLIC and unauthenticated until later TV proof. */
    fun tvChallenge(pending: PhoneEnrollmentChallenge): ByteArray {
        val b = pending.binding
        return encode(Lc04WireKind.TV_CHALLENGE, context(
            b.invitationId, b.pairingId, b.tvFingerprint,
            pending.phoneFingerprint, pending.nonceBytes(),
        ))
    }

    /**
     * Phone validates EVERY fixed-size context field against its OWN scanned QR
     * and OWN generated Ed25519 identity. An attacker-modified challenge cannot
     * make the phone silently sign for a different TV or different phone key.
     */
    fun decodeChallengeForPhone(
        scannedTv: TvQrPublicKey,
        phone: PhoneSigningIdentity,
        wire: ByteArray,
    ): PhoneEnrollmentChallenge? {
        val frame = decode(wire, Lc04WireKind.TV_CHALLENGE) ?: return null
        if (!matches(frame, scannedTv.invitationId, scannedTv.pairingId,
                scannedTv.fingerprint, phone.fingerprint)) return null
        val nonce = frame.payload()
        if (nonce.size != 32) return null
        return PhoneEnrollmentChallenge(
            EnrollmentBinding(scannedTv.invitationId, scannedTv.pairingId,
                scannedTv.fingerprint),
            phone.fingerprint, nonce,
        )
    }

    /** Phone → TV. A detached Tink Ed25519 proof, NOT an authorization cookie. */
    fun phoneProof(
        scannedTv: TvQrPublicKey,
        phone: PhoneSigningIdentity,
        signed: PhoneEnrollmentProof,
    ): ByteArray {
        val signature = signed.signatureBytes()
        require(signature.size == 64 || signature.size == 69)
        return encode(Lc04WireKind.PHONE_PROOF, context(
            scannedTv.invitationId, scannedTv.pairingId,
            scannedTv.fingerprint, phone.fingerprint, signature,
        ))
    }

    internal fun decodeOfferForTv(wire: ByteArray, b: EnrollmentBinding): ByteArray? {
        val frame = decode(wire, Lc04WireKind.PHONE_PUBLIC_KEY) ?: return null
        if (!matchesTv(frame, b)) return null
        val key = frame.payload()
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        val matches = MessageDigest.isEqual(digest, frame.phoneKeyFingerprint())
        digest.fill(0)
        if (!matches) {
            key.fill(0)
            return null
        }
        // Full canonical public-only Tink Ed25519 keyset verification is in
        // TvPhoneEnrollment.begin, not in the untrusted byte parser.
        return key
    }

    internal fun decodeProofForTv(
        wire: ByteArray,
        b: EnrollmentBinding,
        approvedPhoneFingerprint: String,
    ): PhoneEnrollmentProof? {
        val frame = decode(wire, Lc04WireKind.PHONE_PROOF) ?: return null
        if (!matches(frame, b.invitationId, b.pairingId, b.tvFingerprint,
                approvedPhoneFingerprint)) return null
        val sig = frame.payload()
        if (sig.size != 64 && sig.size != 69) return null
        return PhoneEnrollmentProof(sig)
    }

    private fun encode(kind: Lc04WireKind, c: Context): ByteArray =
        Lc04WireLab.encode(Lc04WireFrame(
            kind, c.invitation, c.pairing, c.tvFingerprint,
            c.phoneFingerprint, c.payload,
        ))

    private fun decode(wire: ByteArray, kind: Lc04WireKind): Lc04WireFrame? =
        Lc04WireLab.decode(wire)?.takeIf { it.kind == kind }

    private fun matchesTv(frame: Lc04WireFrame, binding: EnrollmentBinding): Boolean =
        MessageDigest.isEqual(frame.tvInvitationId(), hexBytes(binding.invitationId)) &&
            MessageDigest.isEqual(frame.tvPairingId(), hexBytes(binding.pairingId)) &&
            MessageDigest.isEqual(frame.tvHpkeFingerprint(), hexBytes(binding.tvFingerprint))

    private fun matches(
        frame: Lc04WireFrame,
        invitation: String,
        pairing: String,
        tvFingerprint: String,
        phoneFingerprint: String,
    ): Boolean = MessageDigest.isEqual(frame.tvInvitationId(), hexBytes(invitation)) &&
        MessageDigest.isEqual(frame.tvPairingId(), hexBytes(pairing)) &&
        MessageDigest.isEqual(frame.tvHpkeFingerprint(), hexBytes(tvFingerprint)) &&
        MessageDigest.isEqual(frame.phoneKeyFingerprint(), hexBytes(phoneFingerprint))

    private data class Context(
        val invitation: ByteArray,
        val pairing: ByteArray,
        val tvFingerprint: ByteArray,
        val phoneFingerprint: ByteArray,
        val payload: ByteArray,
    )

    private fun context(invitation: String, pairing: String, tv: String,
                        phone: String, payload: ByteArray): Context =
        Context(hexBytes(invitation), hexBytes(pairing), hexBytes(tv),
            hexBytes(phone), payload.clone())

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in '0'..'9' || it in 'a'..'f' })
        return ByteArray(hex.length / 2) { index ->
            val hi = hex[index * 2].digitToInt(16)
            val lo = hex[index * 2 + 1].digitToInt(16)
            ((hi shl 4) or lo).toByte()
        }
    }
}
