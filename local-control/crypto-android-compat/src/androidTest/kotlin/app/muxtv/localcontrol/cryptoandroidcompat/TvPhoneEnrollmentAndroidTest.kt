package app.muxtv.localcontrol.cryptoandroidcompat

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentDenial
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentProof
import app.muxtv.localcontrol.enrollment.PhoneEnrollmentResult
import app.muxtv.localcontrol.enrollment.PhoneSigningIdentity
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * API26/API36 real Android provider test; standalone proof only, never INTERNET or sources.
 */
@RunWith(AndroidJUnit4::class)
class TvPhoneEnrollmentAndroidTest {
    @Test fun realTvQrAndTinkPhoneSigningRequireTvConsentAndRejectReplay() {
        val start = SystemClock.elapsedRealtime()
        val tv = TvHpkeQrSession.create(start)
        val qr = tv.presentation(SystemClock.elapsedRealtime())!!.qrValue
        val gate = tv.phoneEnrollmentGate(SystemClock::elapsedRealtime)!!
        val phone = PhoneSigningIdentity.generate()
        val parsedQr = TvQrPublicKeyParser.parse(qr)
        val challenge = gate.begin(phone.publicKeysetBytes())!!
        assertThat(gate.pendingFingerprintForTv()).isEqualTo(phone.fingerprint)
        val proof = phone.prove(parsedQr, challenge)
        assertThat((gate.verify(proof) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.TV_CONSENT_REQUIRED)
        assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
        assertThat(gate.verify(proof)).isInstanceOf(PhoneEnrollmentResult.Verified::class.java)
        assertThat((gate.verify(proof) as PhoneEnrollmentResult.Denied).reason)
            .isEqualTo(PhoneEnrollmentDenial.REPLAYED)
        tv.close()
        phone.close()
    }

    @Test fun tvRevocationDeniesPreviouslyApprovedUnredeemedProof() {
        val now = SystemClock.elapsedRealtime()
        val tv = TvHpkeQrSession.create(now)
        val qr = TvQrPublicKeyParser.parse(tv.presentation(now)!!.qrValue)
        val phone = PhoneSigningIdentity.generate()
        val gate = tv.phoneEnrollmentGate(SystemClock::elapsedRealtime)!!
        val nonce = gate.begin(phone.publicKeysetBytes())!!
        assertThat(gate.approveOnTv(phone.fingerprint)).isTrue()
        val signature = phone.prove(qr, nonce)
        tv.close()
        assertThat(gate.verify(signature)).isInstanceOf(PhoneEnrollmentResult.Denied::class.java)
        assertThat(gate.verify(PhoneEnrollmentProof(ByteArray(69))))
            .isInstanceOf(PhoneEnrollmentResult.Denied::class.java)
        phone.close()
    }
}
