package app.muxtv

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.localcontrol.TvPairingController
import com.google.common.truth.Truth.assertThat
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Canonical GitHub-hosted Android TV API26/API36 integration evidence:
 * no fake socket/transport; bind the device's selected numeric private
 * LAN address after TV opt-in and confirm immediate onStop revocation.
 *
 * This does NOT claim a phone protocol handshake or source provisioning.
 */
@RunWith(AndroidJUnit4::class)
class TvNativeTcpLifecycleDeviceTest {
    @Test fun nativePrivateListenerNeedsTvOptInAndClosesImmediatelyOnRevocation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var visible = true
        var networkInvalidations = 0
        val owner = TvPairingController(
            context = context,
            invalidated = { networkInvalidations++ },
            isTvVisible = { visible },
        )
        try {
            val qr = owner.startPublicKeyQrPreview()
            assertThat(qr.expiresAtElapsedMillis).isGreaterThan(SystemClock.elapsedRealtime())
            assertThat(owner.isActive()).isFalse()
            val endpoint = owner.enableNativeProbeForVisibleQr()
            assertThat(endpoint.privateIpv4).isNotEmpty()
            assertThat(endpoint.port).isAtLeast(1)
            assertThat(endpoint.port).isAtMost(65535)
            assertThat(owner.isPublicKeyQrPreviewActive()).isTrue()
            assertThat(networkInvalidations).isEqualTo(0)
            Socket().use { socket ->
                socket.connect(
                    InetSocketAddress(endpoint.privateIpv4, endpoint.port),
                    1_500,
                )
                assertThat(socket.isConnected).isTrue()
            }
            visible = false // Activity.onStop authority boundary.
            owner.revokeImmediately()
            assertThat(owner.isPublicKeyQrPreviewActive()).isFalse()
            assertThat(owner.pendingPhonePublicKeyFingerprint()).isNull()
            val refused = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(endpoint.privateIpv4, endpoint.port), 1_500)
                }
            }
            assertThat(refused.isFailure).isTrue()
        } finally {
            visible = false
            owner.revokeImmediately()
            owner.stop()
        }
    }
}
