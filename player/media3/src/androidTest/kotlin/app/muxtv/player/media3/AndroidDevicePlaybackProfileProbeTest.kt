package app.muxtv.player.media3

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.player.DeviceHdrType
import app.muxtv.player.DeviceVideoCodec
import app.muxtv.player.HardwareAccelerationEvidence
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDevicePlaybackProfileProbeTest {
    @Test
    fun captureProducesBoundedInternallyConsistentEvidence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val profile = AndroidDevicePlaybackProfileProbe(context).capture()

        assertThat(profile.memory.memoryClassMb).isGreaterThan(0)
        assertThat(profile.videoDecoders.size).isAtMost(DeviceVideoCodec.entries.size)
        assertThat(profile.videoDecoders.map { capability -> capability.codec }.distinct())
            .hasSize(profile.videoDecoders.size)
        assertThat(profile.display.supportedModes.size).isAtMost(64)
        assertThat(profile.display.supportedModes.distinct())
            .hasSize(profile.display.supportedModes.size)
        assertThat(profile.display.supportedModes.all { mode ->
            mode.widthPixels > 0 && mode.heightPixels > 0 && mode.refreshRateMilliHz > 0
        }).isTrue()
        profile.display.currentMode?.let { currentMode ->
            assertThat(profile.display.supportedModes).contains(currentMode)
        }
        assertThat(profile.display.hdrTypes.all { hdrType -> hdrType in DeviceHdrType.entries })
            .isTrue()

        if (Build.VERSION.SDK_INT < 29) {
            assertThat(profile.videoDecoders.all { capability ->
                capability.hardwareAcceleration == HardwareAccelerationEvidence.UNKNOWN
            }).isTrue()
        } else {
            assertThat(profile.videoDecoders.none { capability ->
                capability.hardwareAcceleration == HardwareAccelerationEvidence.UNKNOWN
            }).isTrue()
        }
    }
}
