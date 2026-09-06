package app.muxtv.player.media3

import app.muxtv.player.DeviceDisplayMode
import app.muxtv.player.DeviceHdrType
import app.muxtv.player.DeviceVideoCodec
import app.muxtv.player.DeviceVideoDecodeCapability
import app.muxtv.player.HardwareAccelerationEvidence
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevicePlaybackProfileProjectionTest {
    @Test
    fun decoderAggregationIsDeterministicAndConservative() {
        val rawDecoders = listOf(
            VideoDecoderEvidence(DeviceVideoCodec.HEVC, hardwareAccelerated = false),
            VideoDecoderEvidence(DeviceVideoCodec.AVC, hardwareAccelerated = null),
            VideoDecoderEvidence(DeviceVideoCodec.HEVC, hardwareAccelerated = true),
            VideoDecoderEvidence(DeviceVideoCodec.AV1, hardwareAccelerated = false),
            VideoDecoderEvidence(DeviceVideoCodec.AVC, hardwareAccelerated = false),
        )
        val forward = projectDevicePlaybackProfile(evidence(videoDecoders = rawDecoders))
        val reversed = projectDevicePlaybackProfile(evidence(videoDecoders = rawDecoders.reversed()))

        assertThat(forward).isEqualTo(reversed)
        assertThat(forward.videoDecoders).containsExactly(
            DeviceVideoDecodeCapability(
                codec = DeviceVideoCodec.AVC,
                hardwareAcceleration = HardwareAccelerationEvidence.UNKNOWN,
            ),
            DeviceVideoDecodeCapability(
                codec = DeviceVideoCodec.HEVC,
                hardwareAcceleration = HardwareAccelerationEvidence.PRESENT,
            ),
            DeviceVideoDecodeCapability(
                codec = DeviceVideoCodec.AV1,
                hardwareAcceleration = HardwareAccelerationEvidence.ABSENT,
            ),
        ).inOrder()
    }

    @Test
    fun displayNormalizationDropsInvalidDeduplicatesSortsAndRetainsCurrentWithinCap() {
        val current = DisplayModeEvidence(3840, 2160, 60_000)
        val rawModes = buildList {
            add(DisplayModeEvidence(0, 1080, 60_000))
            add(DisplayModeEvidence(1920, 0, 60_000))
            add(DisplayModeEvidence(1920, 1080, 0))
            repeat(70) { index ->
                add(DisplayModeEvidence(1000 + index, 720, 60_000))
            }
            add(DisplayModeEvidence(1000, 720, 60_000))
        }
        val forward = projectDevicePlaybackProfile(
            evidence(
                currentDisplayMode = current,
                supportedDisplayModes = rawModes,
                hdrTypes = linkedSetOf(DeviceHdrType.HDR10_PLUS, DeviceHdrType.HDR10),
            ),
        )
        val reversed = projectDevicePlaybackProfile(
            evidence(
                currentDisplayMode = current,
                supportedDisplayModes = rawModes.reversed(),
                hdrTypes = linkedSetOf(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS),
            ),
        )

        assertThat(forward).isEqualTo(reversed)
        assertThat(forward.display.currentMode).isEqualTo(
            DeviceDisplayMode(3840, 2160, 60_000),
        )
        assertThat(forward.display.supportedModes).hasSize(64)
        assertThat(forward.display.supportedModes).contains(forward.display.currentMode)
        assertThat(forward.display.supportedModes.distinct())
            .hasSize(forward.display.supportedModes.size)
        assertThat(forward.display.supportedModes.all { mode ->
            mode.widthPixels > 0 && mode.heightPixels > 0 && mode.refreshRateMilliHz > 0
        }).isTrue()
        assertThat(forward.display.supportedModes).isInOrder(DISPLAY_MODE_COMPARATOR)
        assertThat(forward.display.hdrTypes)
            .containsExactly(DeviceHdrType.HDR10, DeviceHdrType.HDR10_PLUS)
    }

    @Test
    fun invalidCurrentDisplayModeBecomesUnknownInsteadOfBeingGuessed() {
        val profile = projectDevicePlaybackProfile(
            evidence(
                currentDisplayMode = DisplayModeEvidence(1920, 1080, 0),
                supportedDisplayModes = listOf(DisplayModeEvidence(1920, 1080, 60_000)),
            ),
        )

        assertThat(profile.display.currentMode).isNull()
        assertThat(profile.display.supportedModes)
            .containsExactly(DeviceDisplayMode(1920, 1080, 60_000))
    }

    private fun evidence(
        videoDecoders: List<VideoDecoderEvidence> = emptyList(),
        currentDisplayMode: DisplayModeEvidence? = null,
        supportedDisplayModes: List<DisplayModeEvidence> = emptyList(),
        hdrTypes: Set<DeviceHdrType> = emptySet(),
    ): DevicePlaybackProbeEvidence = DevicePlaybackProbeEvidence(
        videoDecoders = videoDecoders,
        currentDisplayMode = currentDisplayMode,
        supportedDisplayModes = supportedDisplayModes,
        hdrTypes = hdrTypes,
        lowRamDevice = false,
        memoryClassMb = 256,
    )

    private companion object {
        val DISPLAY_MODE_COMPARATOR = compareBy<DeviceDisplayMode>(
            DeviceDisplayMode::widthPixels,
            DeviceDisplayMode::heightPixels,
            DeviceDisplayMode::refreshRateMilliHz,
        )
    }
}
