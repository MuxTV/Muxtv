package app.muxtv.localcontrol.tvqr

import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneProtectedProbeFactory
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Real product Tink crypto in a GitHub-hosted JVM; no network, no provider data. */
class TvProtectedProbeTest {
    private class Fixture(
        val tv: TvHpkeQrSession,
        val signer: PhoneSigningIdentity,
        val phone: PhoneProtectedProbeFactory,
    ) : AutoCloseable {
        override fun close() { phone.close(); signer.close(); tv.close() }
    }

    private fun paired(now: Long = 1_000L): Fixture {
        val tv = TvHpkeQrSession.create(now)
        val scanned = TvQrPublicKeyParser.parse(tv.presentation(now)!!.qrValue)
        val signer = PhoneSigningIdentity.generate()
        val gate = tv.phoneEnrollmentGate({ now })!!
        val offer = Lc04PublicEnrollmentWire.phoneOffer(scanned, signer)
        val challenge = gate.beginPublicOffer(offer)!!
        val proof = Lc04PublicEnrollmentWire.phoneProof(scanned, signer,
            signer.prove(scanned,
                Lc04PublicEnrollmentWire.decodeChallengeForPhone(scanned, signer,
                    Lc04PublicEnrollmentWire.tvChallenge(challenge))!!))
        assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
        val verified = (gate.verifyPublicProof(proof) as PhoneEnrollmentResult.Verified).identity
        assertThat(tv.activateVerifiedPhone(verified, now)).isTrue()
        return Fixture(tv, signer, PhoneProtectedProbeFactory(scanned, signer, { now }))
    }

    @Test fun actualQrEnrolledPhoneSignsRealHpkeAndTvCreatesSingleGcmReceipt() {
        val f = paired()
        try {
            val request = f.phone.create()
            val signed = request.requestBytes()
            val reply = f.tv.acceptProtectedProbe(signed, 1_000L)!!
            assertThat(request.verifyReply(reply)).isTrue()
            assertThat(request.verifyReply(reply)).isFalse()
            assertThat(f.tv.acceptProtectedProbe(signed, 1_000L)).isNull()
            request.close()
        } finally { f.close() }
    }

    @Test fun tvRefusesNonApprovedIdentityAndAllHostileByteMutations() {
        val tv = TvHpkeQrSession.create(1_000L)
        val qr = TvQrPublicKeyParser.parse(tv.presentation(1_000L)!!.qrValue)
        val stranger = PhoneSigningIdentity.generate()
        try {
            val unapprovedPhone = PhoneProtectedProbeFactory(qr, stranger, { 1_000L })
            val unsolicited = unapprovedPhone.create()
            assertThat(tv.acceptProtectedProbe(unsolicited.requestBytes(), 1_000L)).isNull()
            unsolicited.close()
            unapprovedPhone.close()
        } finally { stranger.close(); tv.close() }

        val f = paired()
        try {
            val probe = f.phone.create()
            val bytes = probe.requestBytes()
            for (i in listOf(0, 5, 6, 22, 38, 70, 105, 115, -1)) {
                val tampered = bytes.clone()
                val n = if (i == -1) tampered.lastIndex else i
                tampered[n] = (tampered[n].toInt() xor 1).toByte()
                assertThat(f.tv.acceptProtectedProbe(tampered, 1_000L)).isNull()
            }
            assertThat(f.tv.acceptProtectedProbe(bytes.copyOf(bytes.size - 1), 1_000L)).isNull()
            val reply = f.tv.acceptProtectedProbe(bytes, 1_000L)!!
            assertThat(probe.verifyReply(reply)).isTrue()
            probe.close()
        } finally { f.close() }
    }

    @Test fun crossQrSessionExpiredOrRevokedReceiverCannotAck() {
        val f = paired()
        val second = TvHpkeQrSession.create(1_000L)
        try {
            val probe = f.phone.create()
            assertThat(second.acceptProtectedProbe(probe.requestBytes(), 1_000L)).isNull()
            assertThat(f.tv.acceptProtectedProbe(probe.requestBytes(), 121_000L)).isNull()
            assertThat(probe.isPending()).isTrue() // mock phone clock remains independent
            assertThat(f.tv.acceptProtectedProbe(probe.requestBytes(), 1_000L)).isNull()
            probe.close()
        } finally { f.close(); second.close() }
    }

    @Test fun monotonicallyIncreasingCounterAllowsFreshProbeButDeniesReplay() {
        val f = paired()
        try {
            val first = f.phone.create()
            val second = f.phone.create()
            val later = f.tv.acceptProtectedProbe(second.requestBytes(), 1_000L)!!
            assertThat(f.tv.acceptProtectedProbe(first.requestBytes(), 1_000L)).isNull()
            assertThat(second.verifyReply(later)).isTrue()
            assertThat(first.verifyReply(later)).isFalse()
            first.close()
            second.close()
        } finally { f.close() }
    }
}
