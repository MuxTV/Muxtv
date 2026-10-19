package app.muxtv.localcontrol.tvqr

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentDenial
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.wirelab.Lc04WireLab
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Product Tink Ed25519 key + real TV-owned HPKE QR. No LAN/HTTP, source or
 * serialized provider credentials; run on exact HEAD GitHub-hosted JVM.
 */
class TvPublicEnrollmentWireTest {
    @Test fun fullThreeMessageWireRoundTripNeedsExplicitTvOwnerConsent() {
        val tv = TvHpkeQrSession.create(1_000L)
        val scanned = TvQrPublicKeyParser.parse(tv.presentation(1_000L)!!.qrValue)
        val gate = tv.phoneEnrollmentGate({ 1_000L })!!
        val phone = PhoneSigningIdentity.generate()
        val offer = Lc04PublicEnrollmentWire.phoneOffer(scanned, phone)
        assertThat(offer.size).isLessThan(4201)
        val parsed = Lc04WireLab.decode(offer)!!
        assertThat(parsed.payload()).isEqualTo(phone.publicKeysetBytes())
        val challenge = gate.beginPublicOffer(offer)!!
        assertThat(gate.pendingFingerprintForTv()).isEqualTo(phone.fingerprint)
        assertThat(gate.beginPublicOffer(offer)).isNull() // candidate cannot be replaced

        val challengeWire = Lc04PublicEnrollmentWire.tvChallenge(challenge)
        val phoneChallenge = Lc04PublicEnrollmentWire.decodeChallengeForPhone(
            scanned, phone, challengeWire)!!
        val proof = phone.prove(scanned, phoneChallenge)
        val detachedProof = Lc04PublicEnrollmentWire.phoneProof(scanned, phone, proof)
        assertThat((gate.verifyPublicProof(detachedProof) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.TV_CONSENT_REQUIRED)
        assertThat(gate.approveOnTv("f".repeat(64))).isFalse()
        assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
        val accepted = gate.verifyPublicProof(detachedProof) as PhoneEnrollmentResult.Verified
        assertThat(accepted.identity.fingerprint).isEqualTo(phone.fingerprint)
        assertThat(accepted.identity.tvFingerprint).isEqualTo(scanned.fingerprint)
        assertThat((gate.verifyPublicProof(detachedProof) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.REPLAYED)
        tv.close()
        phone.close()
    }

    @Test fun untrustedHeaderMutationsAndWrongTvQrAreRejectedWithoutGrantingPending() {
        val tv = TvHpkeQrSession.create(10L)
        val other = TvHpkeQrSession.create(10L)
        val qr = TvQrPublicKeyParser.parse(tv.presentation(10L)!!.qrValue)
        val otherQr = TvQrPublicKeyParser.parse(other.presentation(10L)!!.qrValue)
        val phone = PhoneSigningIdentity.generate()
        val offer = Lc04PublicEnrollmentWire.phoneOffer(qr, phone)
        for (offset in listOf(0, 4, 5, 6, 22, 38, 70, 102, 103, -1)) {
            val bad = offer.clone()
            val i = if (offset < 0) bad.lastIndex else offset
            bad[i] = (bad[i].toInt() xor 1).toByte()
            val gate = TvHpkeQrSession.create(10L).let {
                // Different original TV QR always rejects even perfectly
                // framed but cross-session public offers.
                it.phoneEnrollmentGate({ 10L })!!
            }
            assertThat(gate.beginPublicOffer(bad)).isNull()
        }
        val otherGate = other.phoneEnrollmentGate({ 10L })!!
        assertThat(otherGate.beginPublicOffer(offer)).isNull()
        assertThat(otherQr.fingerprint).isNotEqualTo(qr.fingerprint)
        val actual = tv.phoneEnrollmentGate({ 10L })!!
        assertThat(actual.beginPublicOffer(offer)).isNotNull()
        tv.close()
        other.close()
        phone.close()
    }

    @Test fun challengeSubstitutionAcrossPhoneOrTvFailsClosed() {
        val tv = TvHpkeQrSession.create(10L)
        val qr = TvQrPublicKeyParser.parse(tv.presentation(10L)!!.qrValue)
        val gate = tv.phoneEnrollmentGate({ 10L })!!
        val phone = PhoneSigningIdentity.generate()
        val attacker = PhoneSigningIdentity.generate()
        val nonce = gate.beginPublicOffer(Lc04PublicEnrollmentWire.phoneOffer(qr, phone))!!
        val encoded = Lc04PublicEnrollmentWire.tvChallenge(nonce)
        assertThat(Lc04PublicEnrollmentWire.decodeChallengeForPhone(qr, attacker, encoded)).isNull()
        val other = TvHpkeQrSession.create(10L)
        val wrongQr = TvQrPublicKeyParser.parse(other.presentation(10L)!!.qrValue)
        assertThat(Lc04PublicEnrollmentWire.decodeChallengeForPhone(wrongQr, phone, encoded)).isNull()
        for (offset in listOf(0, 5, 6, 22, 38, 70, -1)) {
            val changed = encoded.clone()
            val i = if (offset < 0) changed.lastIndex else offset
            changed[i] = (changed[i].toInt() xor 1).toByte()
            if (offset != -1) {
                assertThat(Lc04PublicEnrollmentWire.decodeChallengeForPhone(qr, phone, changed))
                    .isNull()
            }
        }
        // A changed nonce is public but must not convince the TV verifier:
        // TV will only verify its originally generated 32-byte challenge.
        val modified = encoded.clone()
        modified[modified.lastIndex] = (modified.last().toInt() xor 1).toByte()
        val altered = Lc04PublicEnrollmentWire.decodeChallengeForPhone(qr, phone, modified)!!
        assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
        val forged = Lc04PublicEnrollmentWire.phoneProof(
            qr, phone, phone.prove(qr, altered),
        )
        assertThat((gate.verifyPublicProof(forged) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.INVALID_PROOF)
        tv.close()
        other.close()
        phone.close()
        attacker.close()
    }

    @Test fun proofAndPublicOfferAreCopyOwnedBoundedAndNeverEnableSecretSourceInput() {
        val tv = TvHpkeQrSession.create(5L)
        val qr = TvQrPublicKeyParser.parse(tv.presentation(5L)!!.qrValue)
        val gate = tv.phoneEnrollmentGate({ 5L })!!
        val phone = PhoneSigningIdentity.generate()
        val offer = Lc04PublicEnrollmentWire.phoneOffer(qr, phone)
        for (bad in listOf(ByteArray(0), ByteArray(104), offer + 0.toByte(),
                offer.copyOf(offer.size - 1), ByteArray(4201))) {
            assertThat(gate.beginPublicOffer(bad)).isNull()
        }
        val acceptedOffer = gate.beginPublicOffer(offer)!!
        assertThat(Lc04PublicEnrollmentWire.decodeChallengeForPhone(
            qr, phone, Lc04PublicEnrollmentWire.tvChallenge(acceptedOffer))).isNotNull()
        val proof = Lc04PublicEnrollmentWire.phoneProof(qr, phone,
            phone.prove(qr, acceptedOffer))
        val invalid = proof.clone()
        invalid[70] = (invalid[70].toInt() xor 1).toByte()
        assertThat(gate.verifyPublicProof(invalid)).isInstanceOf(PhoneEnrollmentResult.Denied::class.java)
        assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
        assertThat(gate.verifyPublicProof(proof)).isInstanceOf(PhoneEnrollmentResult.Verified::class.java)
        tv.close()
        assertThat(gate.beginPublicOffer(offer)).isNull()
        phone.close()
    }
}
