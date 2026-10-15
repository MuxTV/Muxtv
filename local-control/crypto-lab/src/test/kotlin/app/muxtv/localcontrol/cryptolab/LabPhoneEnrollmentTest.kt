package app.muxtv.localcontrol.cryptolab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LabPhoneEnrollmentTest {
    private val binding = LabBinding("a".repeat(32), "b".repeat(32))
    private val tvKey = "c".repeat(64)

    private fun tv(clock: () -> Long = { 1_000L }, failures: Int = 3) =
        LabTvPhoneEnrollment(binding, tvKey, elapsedMillis = clock, maxFailures = failures)

    private fun approved(tv: LabTvPhoneEnrollment, phone: LabPhoneIdentity): ByteArray {
        val challenge = tv.beginEnrollment(phone.publicKeyBytes())
        assertThat(tv.approveOnTv(phone.fingerprint)).isTrue()
        return challenge
    }

    @Test fun ownerApprovalAndProofOfPrivateKeyAreBothRequired() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val challenge = tv.beginEnrollment(phone.publicKeyBytes())
        val proof = phone.sign(binding, tvKey, challenge)
        assertThat((tv.verifyProof(proof) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.TV_CONSENT_REQUIRED)
        assertThat(tv.approveOnTv(phone.fingerprint)).isTrue()
        val result = tv.verifyProof(proof) as LabEnrollmentOutcome.ProofVerified
        assertThat(result.approvedPhoneFingerprint).isEqualTo(phone.fingerprint)
        assertThat(phone.toString()).doesNotContain(phone.fingerprint)
        assertThat(proof.toString()).doesNotContain(phone.fingerprint)
    }

    @Test fun duplicateProofCannotAuthorizeTwice() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val proof = phone.sign(binding, tvKey, approved(tv, phone))
        assertThat(tv.verifyProof(proof)).isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)
        assertThat((tv.verifyProof(proof) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.ALREADY_REDEEMED)
    }

    @Test fun stolenOldPlaintextPairingBearerIsNotPhonePrivateKey() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val attacker = LabPhoneIdentity.generate()
        val challenge = approved(tv, phone)
        // Knowledge of the pending binding and challenge without phone private key is insufficient.
        val forgery = attacker.sign(binding, tvKey, challenge)
        assertThat((tv.verifyProof(forgery) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.WRONG_PHONE_KEY)
        assertThat(tv.verifyProof(phone.sign(binding, tvKey, challenge)))
            .isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)
    }

    @Test fun attackerCannotReplaceApprovalFingerprint() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val attacker = LabPhoneIdentity.generate()
        val challenge = tv.beginEnrollment(phone.publicKeyBytes())
        assertThat(tv.approveOnTv(attacker.fingerprint)).isFalse()
        assertThat((tv.verifyProof(phone.sign(binding, tvKey, challenge)) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.TV_CONSENT_REQUIRED)
    }

    @Test fun changedTvKeyOrPairingIsCryptographicallyRejected() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val challenge = approved(tv, phone)
        val wrongTv = phone.sign(binding, "f".repeat(64), challenge)
        assertThat((tv.verifyProof(wrongTv) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.INVALID)
        val wrongPair = phone.sign(LabBinding("a".repeat(32), "e".repeat(32)), tvKey, challenge)
        assertThat((tv.verifyProof(wrongPair) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.INVALID)
        assertThat(tv.verifyProof(phone.sign(binding, tvKey, challenge)))
            .isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)
    }

    @Test fun alteredChallengeAndSignatureAreRejected() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val challenge = approved(tv, phone)
        val original = phone.sign(binding, tvKey, challenge)
        val altered = original.challengeBytes()
        altered[0] = (altered[0].toInt() xor 1).toByte()
        val modified = LabPhoneProof(original.publicKeyBytes(), altered, original.signatureBytes())
        assertThat((tv.verifyProof(modified) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.WRONG_PHONE_KEY)
        assertThat(tv.verifyProof(original))
            .isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)
    }

    @Test fun malformedSignatureDoesNotAuthorize() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val challenge = approved(tv, phone)
        val corrupt = LabPhoneProof(phone.publicKeyBytes(), challenge, ByteArray(64))
        assertThat((tv.verifyProof(corrupt) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.INVALID)
    }

    @Test fun budgetOfBadProofsInvalidatesPendingRequest() {
        val tv = tv(failures = 2)
        val phone = LabPhoneIdentity.generate()
        val challenge = approved(tv, phone)
        val bad = LabPhoneProof(phone.publicKeyBytes(), challenge, ByteArray(64))
        assertThat((tv.verifyProof(bad) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.INVALID)
        assertThat((tv.verifyProof(bad) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.TOO_MANY_FAILURES)
        assertThat(tv.verifyProof(phone.sign(binding, tvKey, challenge)))
            .isInstanceOf(LabEnrollmentOutcome.Denied::class.java)
    }

    @Test fun expiryAndClockRollbackInvalidateProof() {
        var now = 10_000L
        val tv = tv(clock = { now })
        val phone = LabPhoneIdentity.generate()
        val c1 = approved(tv, phone)
        now += 120_000
        assertThat((tv.verifyProof(phone.sign(binding, tvKey, c1)) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.EXPIRED)
        val c2 = approved(tv, phone)
        now -= 1
        assertThat((tv.verifyProof(phone.sign(binding, tvKey, c2)) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.EXPIRED)
    }

    @Test fun cancellationPreventsApprovalAndRedemption() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val challenge = approved(tv, phone)
        tv.revoke()
        assertThat(tv.approveOnTv(phone.fingerprint)).isFalse()
        assertThat((tv.verifyProof(phone.sign(binding, tvKey, challenge)) as LabEnrollmentOutcome.Denied).reason)
            .isEqualTo(LabEnrollmentDenial.EXPIRED)
    }

    @Test fun copyingChallengeAndProofDoesNotPermitCallerMutation() {
        val tv = tv()
        val phone = LabPhoneIdentity.generate()
        val challenge = approved(tv, phone)
        val signed = phone.sign(binding, tvKey, challenge)
        val mutated = signed.signatureBytes()
        mutated.fill(0)
        val fakeCopy = signed.publicKeyBytes()
        fakeCopy.fill(0)
        challenge.fill(0)
        assertThat(tv.verifyProof(signed)).isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)
    }

    @Test fun phoneEnrollmentStillDoesNotAuthenticateSeparateHpkeCiphertext() {
        val enrollment = tv()
        val approvedPhone = LabPhoneIdentity.generate()
        val challenge = approved(enrollment, approvedPhone)
        assertThat(enrollment.verifyProof(approvedPhone.sign(binding, tvKey, challenge)))
            .isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)

        val hpkeTv = ExperimentalHpkeLab.newReceiver(binding)
        // Deliberate break: signing the enrollment handshake alone does NOT bind the
        // sender of a later HPKE ciphertext. A third party with the public key can encrypt.
        val attacker = LabPhoneSender(binding, hpkeTv.publicKeyset)
        val forged = attacker.encrypt(LabOperation.SOURCE_ACTIVATE, "forged".toByteArray())
        assertThat(hpkeTv.decrypt(forged))
            .isInstanceOf(LabDecryptResult.Accepted::class.java)
        // Integration requires sender/channel authentication on EVERY command.
    }

    @Test fun aNewEnrollmentInvalidatesEarlierProof() {
        val tv = tv()
        val first = LabPhoneIdentity.generate()
        val oldChallenge = approved(tv, first)
        val second = LabPhoneIdentity.generate()
        val fresh = approved(tv, second)
        assertThat((tv.verifyProof(first.sign(binding, tvKey, oldChallenge))
            as LabEnrollmentOutcome.Denied).reason).isEqualTo(LabEnrollmentDenial.WRONG_PHONE_KEY)
        assertThat(tv.verifyProof(second.sign(binding, tvKey, fresh)))
            .isInstanceOf(LabEnrollmentOutcome.ProofVerified::class.java)
    }
}
