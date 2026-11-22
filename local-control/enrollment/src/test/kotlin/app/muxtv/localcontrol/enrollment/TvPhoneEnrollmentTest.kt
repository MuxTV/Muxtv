package app.muxtv.localcontrol.enrollment

import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.hybrid.HybridConfig
import org.junit.Test
import java.util.Base64

class TvPhoneEnrollmentTest {
    private fun qr(invitation: String = "1".repeat(32), pairing: String = "2".repeat(32)): String {
        HybridConfig.register()
        val receiver = KeysetHandle.newBuilder()
            .addEntry(KeysetHandle.generateEntryFromParametersName(
                "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM",
            ).withRandomId().makePrimary()).build()
        val public = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(receiver.publicKeysetHandle)
        return "MXLC04:1:$invitation:$pairing:" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(public)
    }

    @Test fun explicitTvConsentAndProofOfPossessionAreMandatory() {
        val qr = qr()
        val phone = PhoneSigningIdentity.generate()
        var now = 1_000L
        val tv = TvPhoneEnrollment(qr, { now }, { true })
        val challenge = tv.begin(phone.publicKeysetBytes())!!
        assertThat(tv.pendingFingerprintForTv()).isEqualTo(phone.fingerprint)
        val proof = phone.prove(TvQrPublicKeyParser.parse(qr), challenge)
        assertThat((tv.verify(proof) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.TV_CONSENT_REQUIRED)
        assertThat(tv.approveOnTv("0".repeat(64))).isFalse()
        assertThat(tv.approveOnTv(phone.fingerprint)).isTrue()
        val verified = (tv.verify(proof) as PhoneEnrollmentResult.Verified).identity
        assertThat(verified.fingerprint).isEqualTo(phone.fingerprint)
        assertThat(verified.publicKeysetBytes()).isEqualTo(phone.publicKeysetBytes())
        assertThat((tv.verify(proof) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.REPLAYED)
        assertThat(tv.begin(phone.publicKeysetBytes())).isNull()
        phone.close()
    }

    @Test fun maliciousPhoneWithoutApprovedSigningKeyCannotImpersonate() {
        val qr = qr()
        val good = PhoneSigningIdentity.generate()
        val attacker = PhoneSigningIdentity.generate()
        val tv = TvPhoneEnrollment(qr, { 1_000 }, { true })
        val challenge = tv.begin(good.publicKeysetBytes())!!
        assertThat(tv.approveOnTv(good.fingerprint)).isTrue()
        val forged = attacker.prove(
            TvQrPublicKeyParser.parse(qr),
            // The attacker can use only their own enrollment context in a real phone.
            // An identical-context forged signature is constructed below from their private signer.
            TvPhoneEnrollment(qr, { 1_000 }, { true })
                .begin(attacker.publicKeysetBytes())!!,
        )
        assertThat((tv.verify(forged) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.INVALID_PROOF)
        val legitimate = good.prove(TvQrPublicKeyParser.parse(qr), challenge)
        assertThat(tv.verify(legitimate)).isInstanceOf(PhoneEnrollmentResult.Verified::class.java)
    }

    @Test fun challengeCannotBeSignedForWrongTvKeyOrPairingContext() {
        val qr = qr()
        val phone = PhoneSigningIdentity.generate()
        val tv = TvPhoneEnrollment(qr, { 1_000 }, { true })
        val challenge = tv.begin(phone.publicKeysetBytes())!!
        for (wrong in listOf(qr("a".repeat(32)), qr(pairing = "b".repeat(32)), qr())) {
            var rejected = false
            try {
                phone.prove(TvQrPublicKeyParser.parse(wrong), challenge)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
        }
    }

    @Test fun expiryClockRollbackAndReceiverRevocationFailClosed() {
        val qr = qr()
        val phone = PhoneSigningIdentity.generate()
        var now = 1_000L
        var live = true
        val tv = TvPhoneEnrollment(qr, { now }, { live }, ttlMillis = 1_000)
        val challenge = tv.begin(phone.publicKeysetBytes())!!
        assertThat(tv.approveOnTv(phone.fingerprint)).isTrue()
        now = 2_000L
        assertThat((tv.verify(phone.prove(TvQrPublicKeyParser.parse(qr), challenge))
            as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.EXPIRED_OR_REVOKED)
        assertThat(tv.pendingFingerprintForTv()).isNull()
        now = 1_500L
        assertThat(tv.begin(phone.publicKeysetBytes())).isNotNull()
        now = 1_499L
        assertThat(tv.pendingFingerprintForTv()).isNull()
        live = false
        assertThat(tv.begin(phone.publicKeysetBytes())).isNull()
        live = true
        assertThat(tv.begin(phone.publicKeysetBytes())).isNull()
    }

    @Test fun pendingPhoneCannotBeReplacedOrApprovedSilentlyAndFailuresAreBounded() {
        val qr = qr()
        val phone = PhoneSigningIdentity.generate()
        val attacker = PhoneSigningIdentity.generate()
        val tv = TvPhoneEnrollment(qr, { 1_000 }, { true }, maxFailures = 2)
        val nonce = tv.begin(phone.publicKeysetBytes())!!
        assertThat(tv.begin(attacker.publicKeysetBytes())).isNull()
        assertThat(tv.pendingFingerprintForTv()).isEqualTo(phone.fingerprint)
        assertThat(tv.approveOnTv(phone.fingerprint)).isTrue()
        val bad = PhoneEnrollmentProof(ByteArray(69))
        assertThat((tv.verify(bad) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.INVALID_PROOF)
        assertThat((tv.verify(bad) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.INVALID_PROOF)
        assertThat(tv.pendingFingerprintForTv()).isNull()
        assertThat(tv.begin(attacker.publicKeysetBytes())).isNull() // locked until NEW TV QR
        assertThat(tv.verify(phone.prove(TvQrPublicKeyParser.parse(qr), nonce)))
            .isInstanceOf(PhoneEnrollmentResult.Denied::class.java)
    }

    @Test fun invalidKeysetsAreRejectedAndProofObjectsCopyBytes() {
        val tv = TvPhoneEnrollment(qr(), { 1_000 }, { true })
        for (bad in listOf(ByteArray(0), ByteArray(257), ByteArray(32))) {
            var rejected = false
            try { tv.begin(bad) } catch (_: Exception) { rejected = true }
            assertThat(rejected).isTrue()
        }
        val phone = PhoneSigningIdentity.generate()
        val key = phone.publicKeysetBytes()
        key.fill(0)
        assertThat(phone.publicKeysetBytes()).isNotEqualTo(key)
        val sig = ByteArray(64) { 5 }
        val proof = PhoneEnrollmentProof(sig)
        sig.fill(0)
        assertThat(proof.signatureBytes()).isNotEqualTo(sig)
        assertThat(phone.toString()).doesNotContain(phone.fingerprint)
        assertThat(proof.toString()).doesNotContain("0505")
        phone.close()
        var denied = false
        try { phone.prove(TvQrPublicKeyParser.parse(qr()), tv.begin(
            PhoneSigningIdentity.generate().publicKeysetBytes(),
        )!!) } catch (_: IllegalStateException) { denied = true }
        assertThat(denied).isTrue()
    }
}
