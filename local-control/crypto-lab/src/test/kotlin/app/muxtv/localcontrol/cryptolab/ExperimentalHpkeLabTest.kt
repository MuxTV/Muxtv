package app.muxtv.localcontrol.cryptolab

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExperimentalHpkeLabTest {
    private val binding = LabBinding("a".repeat(32), "b".repeat(32))

    @Test fun hpkeEncryptsAndDecryptsOnlyInExactContext() {
        val receiver = ExperimentalHpkeLab.newReceiver(binding)
        val phone = LabPhoneSender(binding, receiver.publicKeyset)
        val clear = "provider-url-with-private-token".toByteArray()
        val encrypted = phone.encrypt(LabOperation.SOURCE_PREPARE, clear)
        assertThat(encrypted.toString()).doesNotContain("private-token")
        assertThat(encrypted.bytes.toList()).isNotEqualTo(clear.toList())
        val result = receiver.decrypt(encrypted)
        assertThat(result).isInstanceOf(LabDecryptResult.Accepted::class.java)
        var observed: String? = null
        (result as LabDecryptResult.Accepted).consumeOnce { observed = it.toString(Charsets.UTF_8) }
        assertThat(observed).isEqualTo("provider-url-with-private-token")
        assertThat(result.toString()).doesNotContain("private-token")
    }

    @Test fun secondUseOfIdenticalCiphertextIsRejected() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val phone = LabPhoneSender(binding, tv.publicKeyset)
        val msg = phone.encrypt(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        assertThat(tv.decrypt(msg)).isInstanceOf(LabDecryptResult.Accepted::class.java)
        val second = tv.decrypt(msg) as LabDecryptResult.Denied
        assertThat(second.reason).isEqualTo(LabDecryptDenial.REPLAYED)
    }

    @Test fun wrongTvQrKeyCannotDecrypt() {
        val intendedTv = ExperimentalHpkeLab.newReceiver(binding)
        val wrongTv = ExperimentalHpkeLab.newReceiver(binding)
        val msg = LabPhoneSender(binding, intendedTv.publicKeyset)
            .encrypt(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        assertThat((wrongTv.decrypt(msg) as LabDecryptResult.Denied).reason)
            .isEqualTo(LabDecryptDenial.INVALID)
    }

    @Test fun wrongInvitationOrPairingContextCannotDecrypt() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val otherInvitation = LabBinding("c".repeat(32), binding.approvedPairingId)
        val otherPairing = LabBinding(binding.tvInvitationId, "d".repeat(32))
        for (wrong in listOf(otherInvitation, otherPairing)) {
            val msg = LabPhoneSender(wrong, tv.publicKeyset)
                .encrypt(LabOperation.SOURCE_PREPARE, "source".toByteArray())
            assertThat((tv.decrypt(msg) as LabDecryptResult.Denied).reason)
                .isEqualTo(LabDecryptDenial.INVALID)
        }
    }

    @Test fun operationSubstitutionFailsAuthenticatedContext() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val msg = LabPhoneSender(binding, tv.publicKeyset)
            .encrypt(LabOperation.SOURCE_PREPARE, "source".toByteArray())
        val substituted = LabCiphertext(LabOperation.SOURCE_ACTIVATE, msg.counter, msg.bytes)
        assertThat((tv.decrypt(substituted) as LabDecryptResult.Denied).reason)
            .isEqualTo(LabDecryptDenial.INVALID)
        assertThat(tv.decrypt(msg)).isInstanceOf(LabDecryptResult.Accepted::class.java)
    }

    @Test fun tamperedCiphertextRejectedWithoutConsumingCounter() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val msg = LabPhoneSender(binding, tv.publicKeyset)
            .encrypt(LabOperation.SOURCE_CANCEL, "cancel".toByteArray())
        val bytes = msg.bytes.clone()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x40).toByte()
        val changed = LabCiphertext(msg.operation, msg.counter, bytes)
        assertThat((tv.decrypt(changed) as LabDecryptResult.Denied).reason)
            .isEqualTo(LabDecryptDenial.INVALID)
        assertThat(tv.decrypt(msg)).isInstanceOf(LabDecryptResult.Accepted::class.java)
    }

    @Test fun invalidAndOversizedInputsRejected() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val phone = LabPhoneSender(binding, tv.publicKeyset)
        var rejected = false
        try { phone.encrypt(LabOperation.SOURCE_PREPARE, ByteArray(4_097)) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
        assertThat((tv.decrypt(LabCiphertext(LabOperation.SOURCE_PREPARE, 0, ByteArray(32)))
            as LabDecryptResult.Denied).reason).isEqualTo(LabDecryptDenial.INVALID)
        assertThat((tv.decrypt(LabCiphertext(LabOperation.SOURCE_PREPARE, 1, ByteArray(5_121)))
            as LabDecryptResult.Denied).reason).isEqualTo(LabDecryptDenial.INVALID)
    }

    @Test fun oldMessageRejectedAfterNewerSequence() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val phone = LabPhoneSender(binding, tv.publicKeyset)
        val old = phone.encrypt(LabOperation.SOURCE_PREPARE, "first".toByteArray())
        val latest = phone.encrypt(LabOperation.SOURCE_PREPARE, "second".toByteArray())
        assertThat(tv.decrypt(latest)).isInstanceOf(LabDecryptResult.Accepted::class.java)
        assertThat((tv.decrypt(old) as LabDecryptResult.Denied).reason)
            .isEqualTo(LabDecryptDenial.REPLAYED)
    }

    @Test fun invalidBindingIsRejected() {
        var invalid = false
        try { LabBinding("not-a-valid-id", "0".repeat(32)) }
        catch (_: IllegalArgumentException) { invalid = true }
        assertThat(invalid).isTrue()
    }
}
