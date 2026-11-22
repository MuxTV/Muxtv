package app.muxtv.player.media3

import app.muxtv.player.DeviceDisplayCapabilities
import app.muxtv.player.DeviceDisplayMode
import app.muxtv.player.DeviceMemoryCapabilities
import app.muxtv.player.DevicePlaybackProfile
import app.muxtv.player.DevicePlaybackProfileSummaryReader
import app.muxtv.player.DeviceVideoCodec
import app.muxtv.player.DeviceVideoDecodeCapability
import app.muxtv.player.HardwareAccelerationEvidence
import app.muxtv.player.toDevicePlaybackProfileSummary
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevicePlaybackProfileSummaryReaderTest {
    @Test
    fun `reader projects supplied profile through the stable summary boundary`() {
        val profile = DevicePlaybackProfile(
            videoDecoders = listOf(
                DeviceVideoDecodeCapability(
                    codec = DeviceVideoCodec.HEVC,
                    hardwareAcceleration = HardwareAccelerationEvidence.UNKNOWN,
                ),
                DeviceVideoDecodeCapability(
                    codec = DeviceVideoCodec.AVC,
                    hardwareAcceleration = HardwareAccelerationEvidence.PRESENT,
                ),
            ),
            display = DeviceDisplayCapabilities(
                currentMode = DeviceDisplayMode(1_920, 1_080, 60_000),
                supportedModes = listOf(DeviceDisplayMode(1_920, 1_080, 60_000)),
                hdrTypes = emptySet(),
            ),
            memory = DeviceMemoryCapabilities(
                lowRamDevice = false,
                memoryClassMb = 512,
            ),
        )
        val implementationClass = runCatching {
            Class.forName("app.muxtv.player.media3.DevicePlaybackProfileSummaryReaderImpl")
        }.getOrNull()
        assertThat(implementationClass).isNotNull()
        implementationClass!!
        assertThat(
            DevicePlaybackProfileSummaryReader::class.java.isAssignableFrom(implementationClass),
        ).isTrue()

        val constructor = implementationClass.declaredConstructors.single()
        constructor.isAccessible = true
        val capture: () -> DevicePlaybackProfile = { profile }
        val reader = constructor.newInstance(capture) as DevicePlaybackProfileSummaryReader

        assertThat(reader.snapshot()).isEqualTo(profile.toDevicePlaybackProfileSummary())
    }
}
