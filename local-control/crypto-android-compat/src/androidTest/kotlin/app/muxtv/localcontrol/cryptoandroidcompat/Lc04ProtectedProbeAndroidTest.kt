package app.muxtv.localcontrol.cryptoandroidcompat

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneProtectedProbeFactory
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * True Android API26/API36 crypto interop: Tink Ed25519, Tink X25519 HPKE,
 * javax.crypto AES-256-GCM and original-request-bound one-shot receipt.
 * No physical devices, HTTP server, provider secrets or SOURCE_* operations.
 */
@RunWith(AndroidJUnit4::class)
class Lc04ProtectedProbeAndroidTest {
    @Test fun qrApprovedPhoneSignsHpkeAndReceivesAuthenticatedTvReceipt() {
        val time = SystemClock.elapsedRealtime()
        val tv = TvHpkeQrSession.create(time)
        val scanned = TvQrPublicKeyParser.parse(tv.presentation(time)!!.qrValue)
        val signer = PhoneSigningIdentity.generate()
        val factory = PhoneProtectedProbeFactory(scanned, signer, SystemClock::elapsedRealtime)
        try {
            val gate = tv.phoneEnrollmentGate(SystemClock::elapsedRealtime)!!
            val challenge = gate.beginPublicOffer(
                Lc04PublicEnrollmentWire.phoneOffer(scanned, signer))!!
            val signed = Lc04PublicEnrollmentWire.phoneProof(scanned, signer,
                signer.prove(scanned,
                    Lc04PublicEnrollmentWire.decodeChallengeForPhone(scanned, signer,
                        Lc04PublicEnrollmentWire.tvChallenge(challenge))!!))
            assertThat(gate.verifyPublicProof(signed)).isInstanceOf(
                PhoneEnrollmentResult.Denied::class.java)
            assertThat(gate.approveOnTv(signer.fingerprint)).isTrue()
            val verified = (gate.verifyPublicProof(signed) as PhoneEnrollmentResult.Verified).identity
            assertThat(tv.activateVerifiedPhone(verified, SystemClock.elapsedRealtime())).isTrue()
            val probe = factory.create()
            try {
                val request = probe.requestBytes()
                val reply = tv.acceptProtectedProbe(request, SystemClock.elapsedRealtime())!!
                assertThat(probe.verifyReply(reply)).isTrue()
                assertThat(probe.verifyReply(reply)).isFalse()
                assertThat(tv.acceptProtectedProbe(request, SystemClock.elapsedRealtime())).isNull()
            } finally { probe.close() }
        } finally { factory.close(); signer.close(); tv.close() }
    }

    @Test fun wrongPhoneAndRevokedQrCannotMakeTvDecrypt() {
        val time = SystemClock.elapsedRealtime()
        val tv = TvHpkeQrSession.create(time)
        val scanned = TvQrPublicKeyParser.parse(tv.presentation(time)!!.qrValue)
        val unknown = PhoneSigningIdentity.generate()
        val requester = PhoneProtectedProbeFactory(scanned, unknown, SystemClock::elapsedRealtime)
        try {
            val probe = requester.create()
            try {
                assertThat(tv.acceptProtectedProbe(probe.requestBytes(),
                    SystemClock.elapsedRealtime())).isNull()
                tv.close()
                assertThat(tv.acceptProtectedProbe(probe.requestBytes(),
                    SystemClock.elapsedRealtime())).isNull()
            } finally { probe.close() }
        } finally { requester.close(); unknown.close(); tv.close() }
    }
}
