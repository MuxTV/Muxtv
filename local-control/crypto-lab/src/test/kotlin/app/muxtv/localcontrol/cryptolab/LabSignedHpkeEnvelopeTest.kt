package app.muxtv.localcontrol.cryptolab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LabSignedHpkeEnvelopeTest {
    private val binding = LabBinding("1".repeat(32), "2".repeat(32))
    private val tvKeyFingerprint = "3".repeat(64)

    private class Fixture(
        val receiver: LabReceiver,
        val sender: LabSignedHpkePhone,
        val verifier: LabSignedHpkeTv,
        val phoneIdentity: LabPhoneIdentity,
        val approved: LabEnrollmentOutcome.ProofVerified,
    )

    private fun fixture(): Fixture {
        val recipient = ExperimentalHpkeLab.newReceiver(binding)
        val identity = LabPhoneIdentity.generate()
        val enroll = LabTvPhoneEnrollment(binding, tvKeyFingerprint, elapsedMillis = { 1_000 })
        val challenge = enroll.beginEnrollment(identity.publicKeyBytes())
        assertThat(enroll.approveOnTv(identity.fingerprint)).isTrue()
        val approved = enroll.verifyProof(identity.sign(binding, tvKeyFingerprint, challenge))
            as LabEnrollmentOutcome.ProofVerified
        return Fixture(
            recipient,
            LabSignedHpkePhone(binding, tvKeyFingerprint, identity, recipient.publicKeyset),
            LabSignedHpkeTv(binding, tvKeyFingerprint, recipient, approved),
            identity,
            approved,
        )
    }

    @Test fun legitimatePhoneCanSendAuthenticatedEncryptedProviderPayload() {
        val f = fixture()
        val message = f.sender.send(LabOperation.SOURCE_PREPARE, "token=secret-value".toByteArray())
        val accepted = f.verifier.verifyAndDecrypt(message) as LabSignedReceiveResult.Accepted
        var plaintext = ""
        accepted.payload.consumeOnce { plaintext = it.toString(Charsets.UTF_8) }
        assertThat(plaintext).isEqualTo("token=secret-value")
        assertThat(message.toString()).doesNotContain("secret-value")
    }

    @Test fun attackerWithOnlyPublicTvKeyIsRejectedAfterTvApprovedPhone() {
        val f = fixture()
        val attacker = LabPhoneIdentity.generate()
        val forged = LabSignedHpkePhone(
            binding, tvKeyFingerprint, attacker, f.receiver.publicKeyset,
        ).send(LabOperation.SOURCE_ACTIVATE, "forged".toByteArray())
        assertThat(f.verifier.verifyAndDecrypt(forged))
            .isEqualTo(LabSignedReceiveResult.RejectedSignature)
        val legit = f.sender.send(LabOperation.SOURCE_PREPARE, "real".toByteArray())
        assertThat(f.verifier.verifyAndDecrypt(legit))
            .isInstanceOf(LabSignedReceiveResult.Accepted::class.java)
    }

    @Test fun signedMessageCannotBeReplayed() {
        val f = fixture()
        val envelope = f.sender.send(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        assertThat(f.verifier.verifyAndDecrypt(envelope))
            .isInstanceOf(LabSignedReceiveResult.Accepted::class.java)
        assertThat(f.verifier.verifyAndDecrypt(envelope))
            .isEqualTo(LabSignedReceiveResult.RejectedCiphertextOrReplay)
    }

    @Test fun operationChangeInvalidatesSignature() {
        val f = fixture()
        val original = f.sender.send(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        val cipher = original.cipher()
        val changed = LabSignedEnvelope(
            LabCiphertext(LabOperation.SOURCE_ACTIVATE, cipher.counter, cipher.bytes),
            original.signedBytes(),
        )
        assertThat(f.verifier.verifyAndDecrypt(changed))
            .isEqualTo(LabSignedReceiveResult.RejectedSignature)
    }

    @Test fun changedCiphertextOrSignatureInvalidatesSenderProof() {
        val f = fixture()
        val message = f.sender.send(LabOperation.SOURCE_PREPARE, "private".toByteArray())
        val cipher = message.cipher()
        val corrupted = cipher.bytes
        corrupted[corrupted.lastIndex] = (corrupted.last().toInt() xor 1).toByte()
        val tampered = LabSignedEnvelope(
            LabCiphertext(cipher.operation, cipher.counter, corrupted),
            message.signedBytes(),
        )
        assertThat(f.verifier.verifyAndDecrypt(tampered))
            .isEqualTo(LabSignedReceiveResult.RejectedSignature)
        assertThat(f.verifier.verifyAndDecrypt(LabSignedEnvelope(cipher, ByteArray(64))))
            .isEqualTo(LabSignedReceiveResult.RejectedSignature)
    }

    @Test fun wrongPairingContextInvalidatesSignature() {
        val f = fixture()
        val bindingForAnotherPairing = LabBinding("1".repeat(32), "4".repeat(32))
        val sender = LabSignedHpkePhone(
            bindingForAnotherPairing, tvKeyFingerprint, f.phoneIdentity, f.receiver.publicKeyset,
        )
        assertThat(f.verifier.verifyAndDecrypt(
            sender.send(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        )).isEqualTo(LabSignedReceiveResult.RejectedSignature)
    }

    @Test fun substitutedTvFingerprintInvalidatesSignature() {
        val f = fixture()
        val sender = LabSignedHpkePhone(
            binding, "5".repeat(64), f.phoneIdentity, f.receiver.publicKeyset,
        )
        assertThat(f.verifier.verifyAndDecrypt(
            sender.send(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        )).isEqualTo(LabSignedReceiveResult.RejectedSignature)
    }

    @Test fun approvedSignatureDoesNotMakeWrongRecipientHpkeKeyDecryptable() {
        val f = fixture()
        val wrongRecipient = ExperimentalHpkeLab.newReceiver(binding)
        val sender = LabSignedHpkePhone(
            binding, tvKeyFingerprint, f.phoneIdentity, wrongRecipient.publicKeyset,
        )
        assertThat(f.verifier.verifyAndDecrypt(
            sender.send(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        )).isEqualTo(LabSignedReceiveResult.RejectedCiphertextOrReplay)
    }

    @Test fun outOfOrderCommandCannotRollbackMonotonicCounter() {
        val f = fixture()
        val one = f.sender.send(LabOperation.SOURCE_PREPARE, "one".toByteArray())
        val two = f.sender.send(LabOperation.SOURCE_CANCEL, "two".toByteArray())
        assertThat(f.verifier.verifyAndDecrypt(two))
            .isInstanceOf(LabSignedReceiveResult.Accepted::class.java)
        assertThat(f.verifier.verifyAndDecrypt(one))
            .isEqualTo(LabSignedReceiveResult.RejectedCiphertextOrReplay)
    }

    @Test fun approvedIdentityCannotBeReusedForAnotherTvOrPairing() {
        val f = fixture()
        for ((otherBinding, otherTvKey) in listOf(
            LabBinding("9".repeat(32), binding.approvedPairingId) to tvKeyFingerprint,
            LabBinding(binding.tvInvitationId, "8".repeat(32)) to tvKeyFingerprint,
            binding to "7".repeat(64),
        )) {
            var rejected = false
            try {
                LabSignedHpkeTv(
                    otherBinding, otherTvKey, f.receiver, f.approved,
                )
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertThat(rejected).isTrue()
        }
    }

    @Test fun explicitTvRevokeRejectsPreviouslySignedCommands() {
        val f = fixture()
        val pending = f.sender.send(LabOperation.SOURCE_PREPARE, "private".toByteArray())
        f.verifier.revoke()
        assertThat(f.verifier.verifyAndDecrypt(pending))
            .isEqualTo(LabSignedReceiveResult.RevokedOrExpired)
    }

    @Test fun expiredSessionAndClockRollbackRejectSignedCommands() {
        val f = fixture()
        var time = 1_000L
        val verifier = LabSignedHpkeTv(
            binding, tvKeyFingerprint, f.receiver, f.approved,
            elapsedMillis = { time }, lifetimeMillis = 1_000,
        )
        val beforeExpiry = f.sender.send(LabOperation.SOURCE_PREPARE, "first".toByteArray())
        time += 1_000
        assertThat(verifier.verifyAndDecrypt(beforeExpiry))
            .isEqualTo(LabSignedReceiveResult.RevokedOrExpired)
        time = 999L
        assertThat(verifier.verifyAndDecrypt(f.sender.send(
            LabOperation.SOURCE_PREPARE, "late".toByteArray(),
        ))).isEqualTo(LabSignedReceiveResult.RevokedOrExpired)

        val rollbackVerifier = LabSignedHpkeTv(
            binding, tvKeyFingerprint, ExperimentalHpkeLab.newReceiver(binding),
            f.approved, elapsedMillis = { time },
        )
        time -= 1
        assertThat(rollbackVerifier.verifyAndDecrypt(f.sender.send(
            LabOperation.SOURCE_CANCEL, "rollback".toByteArray(),
        ))).isEqualTo(LabSignedReceiveResult.RevokedOrExpired)
    }

    @Test fun malformedCiphertextRejectedBeforeSignatureVerification() {
        val f = fixture()
        val message = LabSignedEnvelope(
            LabCiphertext(LabOperation.SOURCE_PREPARE, 0, ByteArray(8)),
            ByteArray(64),
        )
        assertThat(f.verifier.verifyAndDecrypt(message))
            .isEqualTo(LabSignedReceiveResult.RejectedCiphertextOrReplay)
    }
}
