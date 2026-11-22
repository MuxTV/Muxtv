package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevicePlaybackProfileSummaryContractTest {
    @Test
    fun `summary projection is bounded immutable and deterministic`() {
        val profile = DevicePlaybackProfile(
            videoDecoders = listOf(
                DeviceVideoDecodeCapability(
                    codec = DeviceVideoCodec.AVC,
                    hardwareAcceleration = HardwareAccelerationEvidence.PRESENT,
                ),
                DeviceVideoDecodeCapability(
                    codec = DeviceVideoCodec.HEVC,
                    hardwareAcceleration = HardwareAccelerationEvidence.UNKNOWN,
                ),
            ),
            display = DeviceDisplayCapabilities(
                currentMode = DeviceDisplayMode(
                    widthPixels = 1_920,
                    heightPixels = 1_080,
                    refreshRateMilliHz = 60_000,
                ),
                supportedModes = listOf(
                    DeviceDisplayMode(1_280, 720, 50_000),
                    DeviceDisplayMode(1_920, 1_080, 60_000),
                ),
                hdrTypes = linkedSetOf(DeviceHdrType.HLG, DeviceHdrType.HDR10),
            ),
            memory = DeviceMemoryCapabilities(
                lowRamDevice = true,
                memoryClassMb = 256,
            ),
        )

        val summaryClass = runCatching {
            Class.forName("app.muxtv.player.DevicePlaybackProfileSummary")
        }.getOrNull()
        assertThat(summaryClass).isNotNull()
        summaryClass!!

        val projectionOwner = Class.forName("app.muxtv.player.DevicePlaybackProfileSummaryKt")
        val projection = projectionOwner.getDeclaredMethod(
            "toDevicePlaybackProfileSummary",
            DevicePlaybackProfile::class.java,
        )
        val summary = projection.invoke(null, profile)

        @Suppress("UNCHECKED_CAST")
        val videoDecoders = summaryClass.getMethod("getVideoDecoders").invoke(summary) as List<Any?>
        assertThat(videoDecoders).containsExactly(
            profile.videoDecoders[0],
            profile.videoDecoders[1],
        ).inOrder()
        assertThat(
            runCatching { (videoDecoders as MutableList<Any?>).clear() }.exceptionOrNull(),
        ).isInstanceOf(UnsupportedOperationException::class.java)

        assertThat(summaryClass.getMethod("getCurrentDisplayMode").invoke(summary))
            .isEqualTo(profile.display.currentMode)
        assertThat(summaryClass.getMethod("getSupportedDisplayModeCount").invoke(summary))
            .isEqualTo(2)

        @Suppress("UNCHECKED_CAST")
        val hdrTypes = summaryClass.getMethod("getHdrTypes").invoke(summary) as Set<Any?>
        assertThat(hdrTypes).containsExactly(DeviceHdrType.HDR10, DeviceHdrType.HLG).inOrder()
        assertThat(
            runCatching { (hdrTypes as MutableSet<Any?>).clear() }.exceptionOrNull(),
        ).isInstanceOf(UnsupportedOperationException::class.java)

        assertThat(summaryClass.getMethod("getLowRamDevice").invoke(summary)).isEqualTo(true)
        assertThat(summaryClass.getMethod("getMemoryClassMb").invoke(summary)).isEqualTo(256)
    }

    @Test
    fun `summary reader exposes summary rather than raw profile`() {
        val summaryClass = runCatching {
            Class.forName("app.muxtv.player.DevicePlaybackProfileSummary")
        }.getOrNull()
        val readerClass = runCatching {
            Class.forName("app.muxtv.player.DevicePlaybackProfileSummaryReader")
        }.getOrNull()

        assertThat(summaryClass).isNotNull()
        assertThat(readerClass).isNotNull()
        summaryClass!!
        readerClass!!

        val snapshot = readerClass.methods.single { method -> method.name == "snapshot" }
        assertThat(snapshot.parameterCount).isEqualTo(0)
        assertThat(snapshot.returnType).isEqualTo(summaryClass)
        assertThat(snapshot.returnType).isNotEqualTo(DevicePlaybackProfile::class.java)
    }
}
