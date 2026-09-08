package app.muxtv.player.media3

import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import app.muxtv.player.PlaybackRuntimeHdr
import app.muxtv.player.PlaybackRuntimeTransport
import app.muxtv.player.PlaybackRuntimeVideoCodec

internal fun PlaybackTransport.toPlaybackRuntimeTransport(): PlaybackRuntimeTransport =
    when (this) {
        PlaybackTransport.HLS -> PlaybackRuntimeTransport.HLS
        PlaybackTransport.MPEG_TS_LIVE -> PlaybackRuntimeTransport.MPEG_TS_LIVE
        PlaybackTransport.DASH -> PlaybackRuntimeTransport.DASH
        PlaybackTransport.PROGRESSIVE -> PlaybackRuntimeTransport.PROGRESSIVE
        PlaybackTransport.AUTO -> PlaybackRuntimeTransport.AUTO
    }

internal fun Format.toPlaybackRuntimeVideoFormatEvidence(): PlaybackRuntimeVideoFormatEvidence {
    val mimeType = sampleMimeType
    val colorTransfer = colorInfo?.colorTransfer
    return PlaybackRuntimeVideoFormatEvidence(
        codec = when (mimeType) {
            null -> null
            MimeTypes.VIDEO_H264 -> PlaybackRuntimeVideoCodec.AVC
            MimeTypes.VIDEO_H265 -> PlaybackRuntimeVideoCodec.HEVC
            MimeTypes.VIDEO_VP9 -> PlaybackRuntimeVideoCodec.VP9
            MimeTypes.VIDEO_AV1 -> PlaybackRuntimeVideoCodec.AV1
            else -> PlaybackRuntimeVideoCodec.OTHER
        },
        widthPixels = width.takeIf { it > 0 },
        heightPixels = height.takeIf { it > 0 },
        bitrateBitsPerSecond = averageBitrate.takeIf { it > 0 },
        hdr = when {
            mimeType == MimeTypes.VIDEO_DOLBY_VISION -> PlaybackRuntimeHdr.DOLBY_VISION
            colorTransfer == null || colorTransfer == Format.NO_VALUE -> PlaybackRuntimeHdr.UNKNOWN
            colorTransfer == C.COLOR_TRANSFER_ST2084 -> PlaybackRuntimeHdr.PQ
            colorTransfer == C.COLOR_TRANSFER_HLG -> PlaybackRuntimeHdr.HLG
            colorTransfer == C.COLOR_TRANSFER_LINEAR ||
                colorTransfer == C.COLOR_TRANSFER_SDR ||
                colorTransfer == C.COLOR_TRANSFER_SRGB ||
                colorTransfer == C.COLOR_TRANSFER_GAMMA_2_2 -> PlaybackRuntimeHdr.SDR
            else -> PlaybackRuntimeHdr.UNKNOWN
        },
    )
}

/** Resolves only the MediaItem associated with this analytics event, never the current player item. */
internal fun AnalyticsListener.EventTime.playbackRuntimeGeneration(): Long? {
    if (windowIndex !in 0 until timeline.windowCount) return null
    val window = Timeline.Window()
    timeline.getWindow(windowIndex, window)
    return window.mediaItem.playbackSeekToken()?.generation
}

/**
 * Measurement-only adapter from Media3 analytics callbacks into the process-local runtime state.
 *
 * [eventGeneration] resolves the generation owned by the callback's EventTime. Keeping generation
 * resolution outside this listener makes stale/prebuffered event attribution explicit and testable.
 */
@AndroidXOptIn(UnstableApi::class)
internal class PlaybackRuntimeAnalyticsListener(
    private val state: PlaybackRuntimeMeasurementState,
    private val eventGeneration: (AnalyticsListener.EventTime) -> Long?,
) : AnalyticsListener {
    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) {
        val generation = eventGeneration(eventTime) ?: return
        when (state) {
            Player.STATE_BUFFERING -> this.state.onBuffering(generation, eventTime.realtimeMs)
            Player.STATE_READY -> this.state.onReady(generation, eventTime.realtimeMs)
        }
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) {
        val generation = eventGeneration(eventTime) ?: return
        state.onVideoFormat(generation, format.toPlaybackRuntimeVideoFormatEvidence())
    }
}
