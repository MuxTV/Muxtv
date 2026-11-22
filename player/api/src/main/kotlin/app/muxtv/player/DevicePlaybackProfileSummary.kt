package app.muxtv.player

import java.util.Collections

/**
 * Bounded, provider-neutral projection of runtime playback environment evidence for diagnostics.
 *
 * This summary intentionally excludes Android/Media3 objects, device identity, provider/source
 * metadata and playback locators. It is evidence only and must not be interpreted as a physical
 * compatibility claim or a playback-policy decision.
 */
class DevicePlaybackProfileSummary(
    videoDecoders: List<DeviceVideoDecodeCapability>,
    val currentDisplayMode: DeviceDisplayMode?,
    val supportedDisplayModeCount: Int,
    hdrTypes: Set<DeviceHdrType>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
) {
    val videoDecoders: List<DeviceVideoDecodeCapability> =
        Collections.unmodifiableList(ArrayList(videoDecoders))
    val hdrTypes: Set<DeviceHdrType> =
        Collections.unmodifiableSet(LinkedHashSet(hdrTypes))

    init {
        require(this.videoDecoders.size <= DeviceVideoCodec.entries.size)
        require(
            this.videoDecoders.map(DeviceVideoDecodeCapability::codec).distinct().size ==
                this.videoDecoders.size,
        )
        require(supportedDisplayModeCount in 0..MAX_SUMMARY_DISPLAY_MODE_COUNT)
        require(memoryClassMb > 0)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DevicePlaybackProfileSummary) return false
        return videoDecoders == other.videoDecoders &&
            currentDisplayMode == other.currentDisplayMode &&
            supportedDisplayModeCount == other.supportedDisplayModeCount &&
            hdrTypes == other.hdrTypes &&
            lowRamDevice == other.lowRamDevice &&
            memoryClassMb == other.memoryClassMb
    }

    override fun hashCode(): Int {
        var result = videoDecoders.hashCode()
        result = 31 * result + (currentDisplayMode?.hashCode() ?: 0)
        result = 31 * result + supportedDisplayModeCount
        result = 31 * result + hdrTypes.hashCode()
        result = 31 * result + lowRamDevice.hashCode()
        result = 31 * result + memoryClassMb
        return result
    }
}

fun DevicePlaybackProfile.toDevicePlaybackProfileSummary(): DevicePlaybackProfileSummary =
    DevicePlaybackProfileSummary(
        videoDecoders = videoDecoders.sortedBy { capability -> capability.codec.ordinal },
        currentDisplayMode = display.currentMode,
        supportedDisplayModeCount = display.supportedModes.size,
        hdrTypes = display.hdrTypes
            .sortedBy(DeviceHdrType::ordinal)
            .toCollection(LinkedHashSet()),
        lowRamDevice = memory.lowRamDevice,
        memoryClassMb = memory.memoryClassMb,
    )

fun interface DevicePlaybackProfileSummaryReader {
    fun snapshot(): DevicePlaybackProfileSummary?
}

private const val MAX_SUMMARY_DISPLAY_MODE_COUNT = 64
