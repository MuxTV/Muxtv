package app.muxtv.localcontrol.cryptoandroidcompat

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.localcontrol.enrollment.Lc04PublicEnrollmentWire
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentDenial
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** API26/API36 real Android Tink implementation through canonical PUBLIC byte frames. */
@RunWith(AndroidJUnit4::class)
class Lc04PublicEnrollmentWireAndroidTest {
    @Test fun publicEnrollmentFramesPreserveTvConsentAndProofOnRealAndroidCrypto() {
        val now = SystemClock.elapsedRealtime()
        val tv = TvHpkeQrSession.create(now)
        val parsed = TvQrPublicKeyParser.parse(tv.presentation(now)!!.qrValue)
        val phone = PhoneSigningIdentity.generate()
        try {
            val offer = Lc04PublicEnrollmentWire.phoneOffer(parsed, phone)
            val gate = tv.phoneEnrollmentGate(SystemClock::elapsedRealtime)!!
            val challenge = gate.beginPublicOffer(offer)!!
            val challengeWire = Lc04PublicEnrollmentWire.tvChallenge(challenge)
            val received = Lc04PublicEnrollmentWire.decodeChallengeForPhone(
                parsed, phone, challengeWire)!!
            val proof = Lc04PublicEnrollmentWire.phoneProof(
                parsed, phone, phone.prove(parsed, received))
            assertThat((gate.verifyPublicProof(proof) as PhoneEnrollmentResult.Denied).reason)
                .isEqualTo(PhoneEnrollmentDenial.TV_CONSENT_REQUIRED)
            assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
            val identity = (gate.verifyPublicProof(proof) as PhoneEnrollmentResult.Verified).identity
            assertThat(identity.fingerprint).isEqualTo(phone.fingerprint)
            assertThat(identity.tvInvitationId).isEqualTo(parsed.invitationId)
            assertThat((gate.verifyPublicProof(proof) as PhoneEnrollmentResult.Denied).reason)
                .isEqualTo(PhoneEnrollmentDenial.REPLAYED)
        } finally {
            phone.close()
            tv.close()
        }
    }

    @Test fun changingTvContextOrRevokingQrRejectsPhoneOfferAndProof() {
        val now = SystemClock.elapsedRealtime()
        val tv = TvHpkeQrSession.create(now)
        val differentTv = TvHpkeQrSession.create(now)
        val first = TvQrPublicKeyParser.parse(tv.presentation(now)!!.qrValue)
        val second = TvQrPublicKeyParser.parse(differentTv.presentation(now)!!.qrValue)
        val phone = PhoneSigningIdentity.generate()
        try {
            val offer = Lc04PublicEnrollmentWire.phoneOffer(first, phone)
            val wrongGate = differentTv.phoneEnrollmentGate(SystemClock::elapsedRealtime)!!
            assertThat(wrongGate.beginPublicOffer(offer)).isNull()
            val gate = tv.phoneEnrollmentGate(SystemClock::elapsedRealtime)!!
            val challenge = gate.beginPublicOffer(offer)!!
            assertThat(Lc04PublicEnrollmentWire.decodeChallengeForPhone(
                second, phone, Lc04PublicEnrollmentWire.tvChallenge(challenge))).isNull()
            assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
            val proof = Lc04PublicEnrollmentWire.phoneProof(
                first, phone, phone.prove(first, challenge))
            tv.close()
            assertThat(gate.verifyPublicProof(proof))
                .isInstanceOf(PhoneEnrollmentResult.Denied::class.java)
            assertThat(gate.beginPublicOffer(offer)).isNull()
        } finally {
            tv.close()
            differentTv.close()
            phone.close()
        }
    }
}
