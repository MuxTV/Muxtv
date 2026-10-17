package app.muxtv.localcontrol.qr

import app.muxtv.localcontrol.cryptolab.ExperimentalHpkeLab
import app.muxtv.localcontrol.cryptolab.LabBinding
import app.muxtv.localcontrol.cryptolab.LabTvQrKeyBinding
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.proto.HpkeAead
import com.google.crypto.tink.proto.HpkePublicKey
import com.google.crypto.tink.proto.KeyData
import com.google.crypto.tink.proto.Keyset
import com.google.crypto.tink.shaded.protobuf.ByteString
import org.junit.Test
import java.util.Base64

class TvQrPublicKeyParserTest {
    private val binding = LabBinding("a".repeat(32), "b".repeat(32))

    private fun sample(): String {
        val receiver = ExperimentalHpkeLab.newReceiver(binding)
        return LabTvQrKeyBinding.encode(binding, receiver.publicKeyset)
    }

    @Test fun acceptsRealCanonicalLabQrAndMatchesFingerprint() {
        val qr = sample()
        val parsed = TvQrPublicKeyParser.parse(qr)
        val lab = LabTvQrKeyBinding.parse(qr)
        assertThat(parsed.fingerprint).isEqualTo(lab.keyFingerprint)
        assertThat(parsed.invitationId).isEqualTo(binding.tvInvitationId)
        assertThat(parsed.pairingId).isEqualTo(binding.approvedPairingId)
        assertThat(parsed.publicKeysetBytes().toList())
            .containsExactlyElementsIn(lab.encodedPublicKeyset().toList()).inOrder()
        assertThat(parsed.toString()).doesNotContain(qr)
        assertThat(parsed.toString()).doesNotContain(parsed.fingerprint)
    }

    @Test fun oldPlainHttpPairingQrIsRejectedRatherThanOpened() {
        assertThat(TvQrPublicKeyParser.parseOrNull("http://192.168.1.27:34214/pair#secret"))
            .isNull()
        assertThat(TvQrPublicKeyParser.parseOrNull("https://example.com/?token=secret"))
            .isNull()
        assertThat(TvQrPublicKeyParser.parseOrNull("MXLC04:1:")).isNull()
    }

    @Test fun malformedNoncanonicalOversizedAndAlteredIdentityAreRejected() {
        val valid = sample()
        val payload = valid.substringAfterLast(':')
        for (bad in listOf(
            valid + ":extra", " " + valid, valid + " ",
            valid.replace("MXLC04:1:", "MXLC04:2:"),
            valid.replace(payload, payload + "="),
            valid.replace(binding.tvInvitationId, "X".repeat(32)),
            valid.replace(binding.approvedPairingId, "a".repeat(31)),
            "x".repeat(901),
            "MXLC04:1:${binding.tvInvitationId}:${binding.approvedPairingId}:!!!",
        )) {
            assertThat(TvQrPublicKeyParser.parseOrNull(bad)).isNull()
        }
    }

    @Test fun mutatedKeyTypeOrDowngradedSuiteCannotBeParsed() {
        val qr = sample()
        val proto = Keyset.parseFrom(
            Base64.getUrlDecoder().decode(qr.substringAfterLast(':')),
        )
        val entry = proto.getKey(0)
        val wrongType = entry.keyData.toBuilder()
            .setKeyMaterialType(KeyData.KeyMaterialType.ASYMMETRIC_PRIVATE).build()
        assertThat(TvQrPublicKeyParser.parseOrNull(withKeyset(
            qr, proto.toBuilder().setKey(0,
                entry.toBuilder().setKeyData(wrongType)).build(),
        ))).isNull()

        val hpke = HpkePublicKey.parseFrom(entry.keyData.value)
        val downgraded = hpke.toBuilder()
            .setParams(hpke.params.toBuilder().setAead(HpkeAead.AES_128_GCM)).build()
        val wrongSuite = entry.keyData.toBuilder()
            .setValue(ByteString.copyFrom(downgraded.toByteArray())).build()
        assertThat(TvQrPublicKeyParser.parseOrNull(withKeyset(
            qr, proto.toBuilder().setKey(0,
                entry.toBuilder().setKeyData(wrongSuite)).build(),
        ))).isNull()
    }

    @Test fun mutateReturnedPublicBytesDoesNotChangeOriginalIdentity() {
        val identity = TvQrPublicKeyParser.parse(sample())
        val expected = identity.fingerprint
        val bytes = identity.publicKeysetBytes()
        bytes.fill(0)
        assertThat(identity.fingerprint).isEqualTo(expected)
        assertThat(identity.publicKeysetBytes().any { it != 0.toByte() }).isTrue()
    }

    private fun withKeyset(old: String, bytes: Keyset): String =
        old.substringBeforeLast(':') + ":" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
}
