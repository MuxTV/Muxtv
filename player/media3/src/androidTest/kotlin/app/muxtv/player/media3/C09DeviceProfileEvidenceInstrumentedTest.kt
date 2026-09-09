package app.muxtv.player.media3

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.player.PlaybackRuntimeTransport
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C09 environment evidence proving that the existing #347 device summary flows through the
 * authoritative runtime measurement state. This is emulator evidence only and carries no vendor
 * MediaCodec compatibility claim.
 */
@RunWith(AndroidJUnit4::class)
@C09Media3Evidence
class C09DeviceProfileEvidenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun deviceSummaryFlowsThroughRuntimeMeasurementBoundary() {
        val summary = checkNotNull(
            DevicePlaybackProfileSummaryReaderImpl(
                AndroidDevicePlaybackProfileProbe(context)::capture,
            ).snapshot(),
        ) { "C09 device profile summary is unavailable" }

        val state = PlaybackRuntimeMeasurementState()
        state.activate(
            generation = 1L,
            transport = PlaybackRuntimeTransport.AUTO,
            deviceSummary = summary,
        )
        val snapshot = checkNotNull(state.snapshot())

        assertThat(snapshot.lowRamDevice).isEqualTo(summary.lowRamDevice)
        assertThat(snapshot.memoryClassMb).isEqualTo(summary.memoryClassMb)
        assertThat(snapshot.memoryClassMb).isGreaterThan(0)

        Log.i(
            TAG,
            listOf(
                "C09_DEVICE_PROFILE",
                "api=${Build.VERSION.SDK_INT}",
                "low_ram_device=${snapshot.lowRamDevice}",
                "memory_class_mb=${snapshot.memoryClassMb}",
                "decoder_capability_count=${summary.videoDecoders.size}",
                "supported_display_mode_count=${summary.supportedDisplayModeCount}",
                "evidence_authority=emulator_non_vendor",
            ).joinToString("\t"),
        )
    }

    private companion object {
        const val TAG = "C09Media3Evidence"
    }
}
