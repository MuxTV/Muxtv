package app.muxtv.player

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

data class DeviceDisplayCapabilities(
    val currentMode: DeviceDisplayMode?,
    val supportedModes: List<DeviceDisplayMode>,
    val hdrTypes: Set<DeviceHdrType>,
) {
    init {
        require(supportedModes.size <= MAX_SUPPORTED_DISPLAY_MODES)
        require(supportedModes.distinct().size == supportedModes.size)
        require(currentMode == null || currentMode in supportedModes)
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

data class DevicePlaybackProfile(
    val videoDecoders: List<DeviceVideoDecodeCapability>,
    val display: DeviceDisplayCapabilities,
    val memory: DeviceMemoryCapabilities,
) {
    init {
        require(videoDecoders.size <= DeviceVideoCodec.entries.size)
        require(videoDecoders.map(DeviceVideoDecodeCapability::codec).distinct().size == videoDecoders.size)
    }
}

private const val MAX_SUPPORTED_DISPLAY_MODES = 64
