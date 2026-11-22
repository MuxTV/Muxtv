package app.muxtv.localcontrol.cryptoandroidcompat

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.localcontrol.cryptolab.LabTvQrKeyBinding
import app.muxtv.localcontrol.qr.TvQrPublicKeyParser
import app.muxtv.localcontrol.tvqr.TvHpkeQrSession
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.proto.KeyData
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Canonical TV emulators API26 and API36: production-candidate public QR generation,
 * real receiver creation and strict scanner compatibility. No INTERNET or credentials.
 */
@RunWith(AndroidJUnit4::class)
class TvPublicKeyQrDeviceTest {
    @Test
    fun actualTvRuntimeGeneratesStrictPublicOnlyPhoneScannableQr() {
        val now = SystemClock.elapsedRealtime()
        val session = TvHpkeQrSession.create(now)
        val presentation = session.presentation(SystemClock.elapsedRealtime())!!
        val scanned = LabTvQrKeyBinding.parse(presentation.qrValue)
        val actualPhoneParser = TvQrPublicKeyParser.parse(presentation.qrValue)
        assertThat(actualPhoneParser.fingerprint).isEqualTo(presentation.fingerprintSha256)
        assertThat(actualPhoneParser.publicKeysetBytes()).isEqualTo(scanned.encodedPublicKeyset())
        assertThat(scanned.keyFingerprint).isEqualTo(presentation.fingerprintSha256)
        assertThat(scanned.publicKeyset.keysetInfo.keyInfoCount).isEqualTo(1)
        assertThat(scanned.publicKeyset.keysetInfo.getKeyInfo(0).typeUrl)
            .isEqualTo("type.googleapis.com/google.crypto.tink.HpkePublicKey")
        assertThat(presentation.qrValue).startsWith("MXLC04:1:")
        assertThat(presentation.qrValue).doesNotContain("http://")
        session.close()
        assertThat(session.presentation(SystemClock.elapsedRealtime())).isNull()
    }
}
