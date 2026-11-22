package app.muxtv.player

/** Stable transport category observed for the active internal playback attempt. */
enum class PlaybackRuntimeTransport {
    HLS,
    MPEG_TS_LIVE,
    DASH,
    PROGRESSIVE,
    AUTO,
}

/** Runtime-observed video codec family. No provider/source name inference is allowed. */
enum class PlaybackRuntimeVideoCodec {
    AVC,
    HEVC,
    VP9,
    AV1,
    OTHER,
}

/**
 * Coarse runtime HDR evidence.
 *
 * PQ intentionally does not claim HDR10 versus HDR10+ because Media3 color-transfer evidence alone
 * cannot make that distinction reliably for this diagnostic boundary.
 */
enum class PlaybackRuntimeHdr {
    SDR,
    PQ,
    HLG,
    DOLBY_VISION,
    UNKNOWN,
}

/**
 * Bounded, provider-neutral snapshot of the current internal playback attempt.
 *
 * This type intentionally contains only scalar typed evidence. It excludes profile/channel/source/
 * variant identity, locators, request headers, provider metadata and raw Android/Media3 objects.
 * Decoder names are platform component identifiers observed from Media3, never source/provider data.
 */
data class PlaybackRuntimeMeasurementSnapshot(
    val transport: PlaybackRuntimeTransport,
    val videoCodec: PlaybackRuntimeVideoCodec? = null,
    val videoWidthPixels: Int? = null,
    val videoHeightPixels: Int? = null,
    val videoBitrateBitsPerSecond: Int? = null,
    val hdr: PlaybackRuntimeHdr = PlaybackRuntimeHdr.UNKNOWN,
    val firstFrameLatencyMillis: Long? = null,
    val rebufferCount: Int = 0,
    val completedRebufferDurationMillis: Long = 0L,
    val videoDecoderName: String? = null,
    val videoDecoderInitializationDurationMillis: Long? = null,
    val decoderInitializationFailureCount: Int = 0,
    val videoCodecErrorCount: Int = 0,
    val droppedVideoFrameCount: Int = 0,
    val lowRamDevice: Boolean? = null,
    val memoryClassMb: Int? = null,
) {
    init {
        require(videoWidthPixels == null || videoWidthPixels > 0)
        require(videoHeightPixels == null || videoHeightPixels > 0)
        require(videoBitrateBitsPerSecond == null || videoBitrateBitsPerSecond > 0)
        require(firstFrameLatencyMillis == null || firstFrameLatencyMillis >= 0L)
        require(rebufferCount >= 0)
        require(completedRebufferDurationMillis >= 0L)
        require(videoDecoderName == null || (videoDecoderName.isNotBlank() && videoDecoderName.length <= 128))
        require(
            videoDecoderInitializationDurationMillis == null ||
                videoDecoderInitializationDurationMillis >= 0L,
        )
        require(decoderInitializationFailureCount >= 0)
        require(videoCodecErrorCount >= 0)
        require(droppedVideoFrameCount >= 0)
        require(memoryClassMb == null || memoryClassMb > 0)
        require((lowRamDevice == null) == (memoryClassMb == null))
    }
}

fun interface PlaybackRuntimeMeasurementReader {
    fun snapshot(): PlaybackRuntimeMeasurementSnapshot?
}
