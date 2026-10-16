package app.muxtv.localcontrol.qrcontract

import app.muxtv.localcontrol.cryptolab.ExperimentalHpkeLab
import app.muxtv.localcontrol.cryptolab.LabBinding
import app.muxtv.localcontrol.cryptolab.LabTvQrKeyBinding
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvPublicQrTest {
    private val binding = LabBinding("a".repeat(32), "b".repeat(32))

    @Test fun acceptsRealTinkPublicKeyAndDerivesMatchingFingerprint() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val qr = LabTvQrKeyBinding.encode(binding, tv.publicKeyset)
        val expected = LabTvQrKeyBinding.parse(qr)
        val actual = TvPublicQr.inspect(qr)
        assertThat(actual.invitationId).isEqualTo(binding.tvInvitationId)
        assertThat(actual.pairingId).isEqualTo(binding.approvedPairingId)
        assertThat(actual.fingerprintSha256).isEqualTo(expected.keyFingerprint)
        assertThat(actual.toString()).doesNotContain(actual.pairingId)
    }

    @Test fun otherReceiverKeyIsDistinguishableAndNotAutomaticallyTrusted() {
        val first = ExperimentalHpkeLab.newReceiver(binding)
        val second = ExperimentalHpkeLab.newReceiver(binding)
        val a = TvPublicQr.inspect(LabTvQrKeyBinding.encode(binding, first.publicKeyset))
        val b = TvPublicQr.inspect(LabTvQrKeyBinding.encode(binding, second.publicKeyset))
        assertThat(a.fingerprintSha256).isNotEqualTo(b.fingerprintSha256)
    }

    @Test fun forgedOrMalformedQrFailsClosed() {
        val tv = ExperimentalHpkeLab.newReceiver(binding)
        val valid = LabTvQrKeyBinding.encode(binding, tv.publicKeyset)
        val samples = listOf(
            "", "https://evil.invalid/pair", valid.lowercase(),
            " " + valid, valid + " ", valid + ":extra",
            valid.replace("MXLC04:1:", "MXLC04:2:"),
            valid.replace(binding.tvInvitationId, "bad-id"),
            valid.replace(binding.approvedPairingId, "0".repeat(31)),
            valid + "=",
            "MXLC04:1:${binding.tvInvitationId}:${binding.approvedPairingId}:" + "A".repeat(850),
        )
        samples.forEach { value ->
            var denied = false
            try {
                TvPublicQr.inspect(value)
            } catch (_: IllegalArgumentException) {
                denied = true
            }
            assertThat(denied).isTrue()
        }
    }
}
