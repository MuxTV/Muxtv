package app.muxtv.localcontrol.cryptolab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LabAuthenticatedTvReplyTest {
    private val binding = LabBinding("1".repeat(32), "2".repeat(32))
    private val fingerprint = "3".repeat(64)

    private class Fixture(
        val identity: LabPhoneIdentity,
        val signedSender: LabSignedHpkePhone,
        val signedReceiver: LabSignedHpkeTv,
        val phone: LabReplyPhone,
        val tv: LabAuthenticatedReplyTv,
        val publicReceiver: LabReceiver,
    )

    private fun fixture(): Fixture {
        val receiver = ExperimentalHpkeLab.newReceiver(binding)
        val identity = LabPhoneIdentity.generate()
        val enrollment = LabTvPhoneEnrollment(binding, fingerprint, elapsedMillis = { 1_000 })
        val challenge = enrollment.beginEnrollment(identity.publicKeyBytes())
        assertThat(enrollment.approveOnTv(identity.fingerprint)).isTrue()
        val approved = enrollment.verifyProof(identity.sign(binding, fingerprint, challenge))
            as LabEnrollmentOutcome.ProofVerified
        val sender = LabSignedHpkePhone(binding, fingerprint, identity, receiver.publicKeyset)
        val verifier = LabSignedHpkeTv(binding, fingerprint, receiver, approved)
        return Fixture(
            identity, sender, verifier,
            LabReplyPhone(binding, fingerprint, identity.fingerprint, sender),
            LabAuthenticatedReplyTv(binding, fingerprint, identity.fingerprint, verifier),
            receiver,
        )
    }

    @Test fun honestTvProvesItDecryptedTheAuthorizedPhoneEncryptedNonce() {
        val f = fixture()
        val request = f.phone.initiate()
        val sent = f.tv.replyTo(request.signedRequest()) as LabTvReplyResult.Sent
        assertThat(request.verify(sent.reply)).isTrue()
        assertThat(request.verify(sent.reply)).isFalse() // success consumes reply secret
        assertThat(sent.reply.toString()).doesNotContain("TV-RECEIVED")
        request.close()
    }

    @Test fun publicTvKeyAndNetworkAttackDoNotForgeAuthenticatedReply() {
        val f = fixture()
        val request = f.phone.initiate()
        val real = (f.tv.replyTo(request.signedRequest()) as LabTvReplyResult.Sent).reply
        val fake = real.modified(ciphertext = ByteArray(real.ciphertext().size))
        assertThat(request.verify(fake)).isFalse()
        assertThat(request.verify(real)).isTrue()
    }

    @Test fun tamperingCounterRequestDigestCiphertextAndIvAllFailBeforeRealReply() {
        val f = fixture()
        val p = f.phone.initiate()
        val legit = (f.tv.replyTo(p.signedRequest()) as LabTvReplyResult.Sent).reply
        val modified = legit.ciphertext().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThat(p.verify(legit.modified(ciphertext = modified))).isFalse()
        assertThat(p.verify(legit.modified(counter = legit.counter + 1))).isFalse()
        assertThat(p.verify(legit.modified(requestId = ByteArray(32)))).isFalse()
        assertThat(p.verify(legit)).isFalse() // fail-closed budget exhausted
        val fresh = f.phone.initiate()
        val freshReply = (f.tv.replyTo(fresh.signedRequest()) as LabTvReplyResult.Sent).reply
        assertThat(fresh.verify(freshReply.modified(iv = ByteArray(12)))).isFalse()
        assertThat(fresh.verify(freshReply)).isTrue()
    }

    @Test fun signedEnvelopeCannotBeReplayedToGenerateAnotherTvReply() {
        val f = fixture()
        val probe = f.phone.initiate()
        assertThat(f.tv.replyTo(probe.signedRequest())).isInstanceOf(LabTvReplyResult.Sent::class.java)
        assertThat(f.tv.replyTo(probe.signedRequest())).isEqualTo(LabTvReplyResult.Denied)
    }

    @Test fun unapprovedPhoneSignerCannotObtainSignedReceiverTvReply() {
        val f = fixture()
        val imposter = LabPhoneIdentity.generate()
        val attackerSender = LabSignedHpkePhone(
            binding, fingerprint, imposter, f.publicReceiver.publicKeyset,
        )
        val attackerPhone = LabReplyPhone(
            binding, fingerprint, imposter.fingerprint, attackerSender,
        )
        assertThat(f.tv.replyTo(attackerPhone.initiate().signedRequest()))
            .isEqualTo(LabTvReplyResult.Denied)
    }

    @Test fun wrongTvReceiverAndChangedSessionBindingFailClosed() {
        val f = fixture()
        val differentReceiver = ExperimentalHpkeLab.newReceiver(binding)
        val wrongSender = LabSignedHpkePhone(
            binding, fingerprint, f.identity, differentReceiver.publicKeyset,
        )
        val wrongPhone = LabReplyPhone(
            binding, fingerprint, f.identity.fingerprint, wrongSender,
        )
        assertThat(f.tv.replyTo(wrongPhone.initiate().signedRequest()))
            .isEqualTo(LabTvReplyResult.Denied)

        val honest = f.phone.initiate()
        val wrongContextResponder = LabAuthenticatedReplyTv(
            LabBinding("a".repeat(32), binding.approvedPairingId),
            fingerprint, f.identity.fingerprint, f.signedReceiver,
        )
        val reply = wrongContextResponder.replyTo(honest.signedRequest())
            as LabTvReplyResult.Sent
        assertThat(honest.verify(reply.reply)).isFalse()
    }

    @Test fun tvRevocationAndMalformedSecretNeverProduceAck() {
        val f = fixture()
        val badRequest = f.signedSender.send(LabOperation.SOURCE_CANCEL, "short".toByteArray())
        assertThat(f.tv.replyTo(badRequest)).isEqualTo(LabTvReplyResult.Denied)
        val valid = f.phone.initiate()
        f.signedReceiver.revoke()
        assertThat(f.tv.replyTo(valid.signedRequest())).isEqualTo(LabTvReplyResult.Denied)
    }

    @Test fun phoneStateClosedBeforeAnswerCannotAcceptEvenGenuineTvProof() {
        val f = fixture()
        val probe = f.phone.initiate()
        val genuine = f.tv.replyTo(probe.signedRequest()) as LabTvReplyResult.Sent
        probe.close()
        assertThat(probe.verify(genuine.reply)).isFalse()
        assertThat(probe.toString()).doesNotContain("SECRET")
    }
}
