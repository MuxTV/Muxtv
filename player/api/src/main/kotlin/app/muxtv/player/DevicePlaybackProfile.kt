package app.muxtv.player

import java.util.Collections

enum class DeviceVideoCodec {
    AVC,
    HEVC,
    VP9,
    AV1,
}

enum class HardwareAccelerationEvidence {
    PRESENT,
    ABSENT,
    UNKNOWN,
}

data class DeviceVideoDecodeCapability(
    val codec: DeviceVideoCodec,
    val hardwareAcceleration: HardwareAccelerationEvidence,
)

enum class DeviceHdrType {
    HDR10,
    HLG,
    HDR10_PLUS,
    DOLBY_VISION,
}

data class DeviceDisplayMode(
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateMilliHz: Int,
) {
    init {
        require(widthPixels > 0)
        require(heightPixels > 0)
        require(refreshRateMilliHz > 0)
    }
}

class DeviceDisplayCapabilities(
    val currentMode: DeviceDisplayMode?,
    supportedModes: List<DeviceDisplayMode>,
    hdrTypes: Set<DeviceHdrType>,
) {
    val supportedModes: List<DeviceDisplayMode> =
        Collections.unmodifiableList(ArrayList(supportedModes))
    val hdrTypes: Set<DeviceHdrType> =
        Collections.unmodifiableSet(LinkedHashSet(hdrTypes))

    init {
        require(this.supportedModes.size <= MAX_SUPPORTED_DISPLAY_MODES)
        require(this.supportedModes.distinct().size == this.supportedModes.size)
        require(currentMode == null || currentMode in this.supportedModes)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DeviceDisplayCapabilities) return false
        return currentMode == other.currentMode &&
            supportedModes == other.supportedModes &&
            hdrTypes == other.hdrTypes
    }

    override fun hashCode(): Int {
        var result = currentMode?.hashCode() ?: 0
        result = 31 * result + supportedModes.hashCode()
        result = 31 * result + hdrTypes.hashCode()
        return result
    }
}

data class DeviceMemoryCapabilities(
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
) {
    init {
        require(memoryClassMb > 0)
    }
}

class DevicePlaybackProfile(
    videoDecoders: List<DeviceVideoDecodeCapability>,
    val display: DeviceDisplayCapabilities,
    val memory: DeviceMemoryCapabilities,
) {
    val videoDecoders: List<DeviceVideoDecodeCapability> =
        Collections.unmodifiableList(ArrayList(videoDecoders))

    init {
        require(this.videoDecoders.size <= DeviceVideoCodec.entries.size)
        require(
            this.videoDecoders.map(DeviceVideoDecodeCapability::codec).distinct().size ==
                this.videoDecoders.size,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DevicePlaybackProfile) return false
        return videoDecoders == other.videoDecoders &&
            display == other.display &&
            memory == other.memory
    }

    override fun hashCode(): Int {
        var result = videoDecoders.hashCode()
        result = 31 * result + display.hashCode()
        result = 31 * result + memory.hashCode()
        return result
    }
}

private const val MAX_SUPPORTED_DISPLAY_MODES = 64
