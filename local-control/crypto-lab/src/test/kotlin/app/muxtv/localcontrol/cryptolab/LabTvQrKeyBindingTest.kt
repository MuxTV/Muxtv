package app.muxtv.localcontrol.cryptolab

import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.Keyset
import com.google.protobuf.ByteString
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class LabTvQrKeyBindingTest {
    private val binding = LabBinding("a".repeat(32), "b".repeat(32))

    private fun authenticFlow(): Triple<LabReceiver, ScannedTvHpkeKey, LabPhoneIdentity> {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val qr = LabTvQrKeyBinding.encode(binding, tv.publicKeyset)
        val scanned = LabTvQrKeyBinding.parse(qr)
        return Triple(tv, scanned, LabPhoneIdentity.generate())
    }

    private fun approved(scanned: ScannedTvHpkeKey, phone: LabPhoneIdentity): LabEnrollmentOutcome.ProofVerified {
        val owner = LabTvPhoneEnrollment(scanned.binding, scanned.keyFingerprint, elapsedMillis = { 1_000L })
        val nonce = owner.beginEnrollment(phone.publicKeyBytes())
        assertThat(owner.approveOnTv(phone.fingerprint)).isTrue()
        return owner.verifyProof(phone.sign(scanned.binding, scanned.keyFingerprint, nonce))
            as LabEnrollmentOutcome.ProofVerified
    }

    @Test fun actualTvPublicKeyRoundTripsInCanonicalQrAndWorksForSignedEncryption() {
        val (tv, scanned, phone) = authenticFlow()
        val qr = LabTvQrKeyBinding.encode(binding, tv.publicKeyset)
        assertThat(qr).startsWith("MXLC04:1:")
        assertThat(qr.length).isLessThan(900)
        assertThat(qr).doesNotContain("password")
        assertThat(scanned.binding.tvInvitationId).isEqualTo(binding.tvInvitationId)
        assertThat(scanned.binding.approvedPairingId).isEqualTo(binding.approvedPairingId)
        val directHash = MessageDigest.getInstance("SHA-256")
            .digest(TinkProtoKeysetFormat.serializeKeysetWithoutSecret(tv.publicKeyset))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertThat(scanned.keyFingerprint).isEqualTo(directHash)

        val receiver = scanned.tvVerifier(tv, approved(scanned, phone), now = { 1_000L })
        val message = scanned.phoneSender(phone).send(
            LabOperation.SOURCE_PREPARE, "https://iptv.invalid/token=secret".toByteArray(),
        )
        assertThat(receiver.verifyAndDecrypt(message))
            .isInstanceOf(LabSignedReceiveResult.Accepted::class.java)
    }

    @Test fun anotherRealTvKeyIsNotTheScannedTvKey() {
        val (tv, scanned, phone) = authenticFlow()
        val another = ExperimentalHpkeLab.newReceiver(binding)
        assertThat(LabTvQrKeyBinding.encode(binding, tv.publicKeyset))
            .isNotEqualTo(LabTvQrKeyBinding.encode(binding, another.publicKeyset))
        var rejected = false
        try { scanned.tvVerifier(another, approved(scanned, phone), now = { 1_000L }) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
    }

    @Test fun substitutedQrFromAnotherTvCannotBeMistakenForTheOriginal() {
        val a = ExperimentalHpkeLab.newReceiver(binding)
        val b = ExperimentalHpkeLab.newReceiver(binding)
        val qa = LabTvQrKeyBinding.parse(LabTvQrKeyBinding.encode(binding, a.publicKeyset))
        val qb = LabTvQrKeyBinding.parse(LabTvQrKeyBinding.encode(binding, b.publicKeyset))
        assertThat(qa.keyFingerprint).isNotEqualTo(qb.keyFingerprint)
        val phone = LabPhoneIdentity.generate()
        val approvedA = approved(qa, phone)
        var rejected = false
        try { LabSignedHpkeTv(binding, qb.keyFingerprint, b, approvedA) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
    }

    @Test fun qrRejectsAlteredPrefixWhitespaceOversizedAndPaddedPayload() {
        val (tv, _, _) = authenticFlow()
        val original = LabTvQrKeyBinding.encode(binding, tv.publicKeyset)
        val payload = original.substringAfterLast(':')
        val variants = listOf(
            "https://tv.invalid/$original",
            original.lowercase(),
            " $original",
            "$original ",
            original.replace("MXLC04:1:", "MXLC04:2:"),
            "$original:extra",
            original.replace(payload, "$payload="),
            "MXLC04:1:${"a".repeat(32)}:${"b".repeat(32)}:${"x".repeat(850)}",
            original.replace(binding.tvInvitationId, "not-hex"),
            original.replace(binding.approvedPairingId, "0".repeat(31)),
        )
        variants.forEach { value ->
            var rejected = false
            try { LabTvQrKeyBinding.parse(value) }
            catch (_: IllegalArgumentException) { rejected = true }
            assertThat(rejected).isTrue()
        }
    }

    @Test fun noSecretKeysetCanBeInsertedAndNoUnexpectedKeyTypeIsAccepted() {
        val (tv, _, _) = authenticFlow()
        val data = TinkProtoKeysetFormat.serializeKeysetWithoutSecret(tv.publicKeyset)
        val keyset = Keyset.parseFrom(data)
        val originalKey = keyset.getKey(0)
        val mutableData = originalKey.keyData
        val wrongData = mutableData.toBuilder()
            .setKeyMaterialType(KeyData.KeyMaterialType.SYMMETRIC)
            .build()
        val mutated = keyset.toBuilder()
            .setKey(0, originalKey.toBuilder().setKeyData(wrongData))
            .build().toByteArray()
        assertRejectedKeyset(mutated)
        val hpke = HpkePublicKey.parseFrom(mutableData.value)
        val wrongSuite = hpke.toBuilder()
            .setParams(hpke.params.toBuilder().setAead(HpkeAead.AES_128_GCM))
            .build()
        val alteredData = mutableData.toBuilder()
            .setValue(ByteString.copyFrom(wrongSuite.toByteArray()))
            .build()
        val alteredKeyset = keyset.toBuilder()
            .setKey(0, originalKey.toBuilder().setKeyData(alteredData))
            .build().toByteArray()
        assertRejectedKeyset(alteredKeyset)
    }

    @Test fun randomBytesAndNoncanonicalBase64AreRejected() {
        val prefix = "MXLC04:1:${binding.tvInvitationId}:${binding.approvedPairingId}:"
        val invalid = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(48) { 0x7f })
        var rejected = false
        try { LabTvQrKeyBinding.parse(prefix + invalid) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
    }

    private fun assertRejectedKeyset(bytes: ByteArray) {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val qr = "MXLC04:1:${binding.tvInvitationId}:${binding.approvedPairingId}:$body"
        var rejected = false
        try { LabTvQrKeyBinding.parse(qr) }
        catch (_: IllegalArgumentException) { rejected = true }
        assertThat(rejected).isTrue()
    }
}
