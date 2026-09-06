package app.muxtv.player.media3

import app.muxtv.player.DeviceDisplayCapabilities
import app.muxtv.player.DeviceDisplayMode
import app.muxtv.player.DeviceHdrType
import app.muxtv.player.DeviceMemoryCapabilities
import app.muxtv.player.DevicePlaybackProfile
import app.muxtv.player.DeviceVideoCodec
import app.muxtv.player.DeviceVideoDecodeCapability
import app.muxtv.player.HardwareAccelerationEvidence

internal data class VideoDecoderEvidence(
    val codec: DeviceVideoCodec,
    val hardwareAccelerated: Boolean?,
)

internal data class DisplayModeEvidence(
    val widthPixels: Int,
    val heightPixels: Int,
    val refreshRateMilliHz: Int,
)

internal data class DevicePlaybackProbeEvidence(
    val videoDecoders: List<VideoDecoderEvidence>,
    val currentDisplayMode: DisplayModeEvidence?,
    val supportedDisplayModes: List<DisplayModeEvidence>,
    val hdrTypes: Set<DeviceHdrType>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
)

internal fun projectDevicePlaybackProfile(
    evidence: DevicePlaybackProbeEvidence,
): DevicePlaybackProfile {
    val videoDecoders = DeviceVideoCodec.entries.mapNotNull { codec ->
        val codecEvidence = evidence.videoDecoders.filter { decoder -> decoder.codec == codec }
        if (codecEvidence.isEmpty()) {
            null
        } else {
            DeviceVideoDecodeCapability(
                codec = codec,
                hardwareAcceleration = codecEvidence.aggregateHardwareAcceleration(),
            )
        }
    }

    val currentMode = evidence.currentDisplayMode?.toStableModeOrNull()
    val normalizedModes = buildList {
        evidence.supportedDisplayModes.forEach { mode ->
            mode.toStableModeOrNull()?.let(::add)
        }
        currentMode?.let(::add)
    }
        .distinct()
        .sortedWith(DISPLAY_MODE_COMPARATOR)
    val supportedModes = normalizedModes.retainCurrentWithinCap(currentMode)
    val hdrTypes = evidence.hdrTypes
        .sortedBy(DeviceHdrType::ordinal)
        .toCollection(LinkedHashSet())

    return DevicePlaybackProfile(
        videoDecoders = videoDecoders,
        display = DeviceDisplayCapabilities(
            currentMode = currentMode,
            supportedModes = supportedModes,
            hdrTypes = hdrTypes,
        ),
        memory = DeviceMemoryCapabilities(
            lowRamDevice = evidence.lowRamDevice,
            memoryClassMb = evidence.memoryClassMb,
        ),
    )
}

private fun List<VideoDecoderEvidence>.aggregateHardwareAcceleration(): HardwareAccelerationEvidence =
    when {
        any { evidence -> evidence.hardwareAccelerated == true } ->
            HardwareAccelerationEvidence.PRESENT
        any { evidence -> evidence.hardwareAccelerated == null } ->
            HardwareAccelerationEvidence.UNKNOWN
        else -> HardwareAccelerationEvidence.ABSENT
    }

private fun DisplayModeEvidence.toStableModeOrNull(): DeviceDisplayMode? {
    if (widthPixels <= 0 || heightPixels <= 0 || refreshRateMilliHz <= 0) return null
    return DeviceDisplayMode(
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        refreshRateMilliHz = refreshRateMilliHz,
    )
}

private fun List<DeviceDisplayMode>.retainCurrentWithinCap(
    currentMode: DeviceDisplayMode?,
): List<DeviceDisplayMode> {
    if (size <= MAX_SUPPORTED_DISPLAY_MODES) return this

    val firstModes = take(MAX_SUPPORTED_DISPLAY_MODES)
    if (currentMode == null || currentMode in firstModes) return firstModes

    return (firstModes.dropLast(1) + currentMode).sortedWith(DISPLAY_MODE_COMPARATOR)
}

private val DISPLAY_MODE_COMPARATOR = compareBy<DeviceDisplayMode>(
    DeviceDisplayMode::widthPixels,
    DeviceDisplayMode::heightPixels,
    DeviceDisplayMode::refreshRateMilliHz,
)

private const val MAX_SUPPORTED_DISPLAY_MODES = 64
