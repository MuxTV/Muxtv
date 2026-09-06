package app.muxtv.player.media3

import android.app.ActivityManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import app.muxtv.player.DeviceHdrType
import app.muxtv.player.DevicePlaybackProfile
import app.muxtv.player.DeviceVideoCodec
import kotlin.math.roundToInt

internal class AndroidDevicePlaybackProfileProbe(
    context: Context,
) {
    private val applicationContext = context.applicationContext

    fun capture(): DevicePlaybackProfile {
        val activityManager = requireNotNull(
            applicationContext.getSystemService(ActivityManager::class.java),
        ) { "Android activity manager is unavailable." }
        val displayManager = requireNotNull(
            applicationContext.getSystemService(DisplayManager::class.java),
        ) { "Android display manager is unavailable." }

        return projectDevicePlaybackProfile(
            DevicePlaybackProbeEvidence(
                videoDecoders = collectVideoDecoderEvidence(),
                currentDisplayMode = displayManager.defaultDisplay()?.mode?.toEvidence(),
                supportedDisplayModes = displayManager.defaultDisplay()
                    ?.supportedModes
                    ?.map(Display.Mode::toEvidence)
                    .orEmpty(),
                hdrTypes = displayManager.defaultDisplay()?.reportedHdrTypes().orEmpty(),
                lowRamDevice = activityManager.isLowRamDevice,
                memoryClassMb = activityManager.memoryClass,
            ),
        )
    }

    private fun collectVideoDecoderEvidence(): List<VideoDecoderEvidence> = buildList {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.forEach { codecInfo ->
            if (codecInfo.isEncoder || codecInfo.isAliasCompat()) return@forEach
            val hardwareAccelerated = codecInfo.hardwareAcceleratedCompat()
            codecInfo.supportedTypes.forEach { mimeType ->
                mimeType.toDeviceVideoCodecOrNull()?.let { codec ->
                    add(
                        VideoDecoderEvidence(
                            codec = codec,
                            hardwareAccelerated = hardwareAccelerated,
                        ),
                    )
                }
            }
        }
    }

    private fun DisplayManager.defaultDisplay(): Display? = getDisplay(Display.DEFAULT_DISPLAY)
}

private fun MediaCodecInfo.isAliasCompat(): Boolean =
    Build.VERSION.SDK_INT >= 29 && isAlias

private fun MediaCodecInfo.hardwareAcceleratedCompat(): Boolean? =
    if (Build.VERSION.SDK_INT >= 29) isHardwareAccelerated else null

private fun String.toDeviceVideoCodecOrNull(): DeviceVideoCodec? =
    when (lowercase()) {
        MIME_AVC -> DeviceVideoCodec.AVC
        MIME_HEVC -> DeviceVideoCodec.HEVC
        MIME_VP9 -> DeviceVideoCodec.VP9
        MIME_AV1 -> DeviceVideoCodec.AV1
        else -> null
    }

private fun Display.Mode.toEvidence(): DisplayModeEvidence {
    val milliHz = if (refreshRate.isFinite()) {
        (refreshRate * 1_000f).roundToInt()
    } else {
        0
    }
    return DisplayModeEvidence(
        widthPixels = physicalWidth,
        heightPixels = physicalHeight,
        refreshRateMilliHz = milliHz,
    )
}

private fun Display.reportedHdrTypes(): Set<DeviceHdrType> =
    hdrCapabilities.supportedHdrTypes.mapNotNullTo(LinkedHashSet()) { hdrType ->
        when (hdrType) {
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> DeviceHdrType.DOLBY_VISION
            Display.HdrCapabilities.HDR_TYPE_HDR10 -> DeviceHdrType.HDR10
            Display.HdrCapabilities.HDR_TYPE_HLG -> DeviceHdrType.HLG
            else -> {
                if (
                    Build.VERSION.SDK_INT >= 29 &&
                    hdrType == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS
                ) {
                    DeviceHdrType.HDR10_PLUS
                } else {
                    null
                }
            }
        }
    }

private const val MIME_AVC = "video/avc"
private const val MIME_HEVC = "video/hevc"
private const val MIME_VP9 = "video/x-vnd.on2.vp9"
private const val MIME_AV1 = "video/av01"
