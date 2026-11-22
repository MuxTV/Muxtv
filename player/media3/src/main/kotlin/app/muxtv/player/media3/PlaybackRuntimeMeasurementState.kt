package app.muxtv.player.media3

import app.muxtv.player.DevicePlaybackProfileSummary
import app.muxtv.player.PlaybackRuntimeHdr
import app.muxtv.player.PlaybackRuntimeMeasurementReader
import app.muxtv.player.PlaybackRuntimeMeasurementSnapshot
import app.muxtv.player.PlaybackRuntimeTransport
import app.muxtv.player.PlaybackRuntimeVideoCodec

private const val MAX_DECODER_NAME_LENGTH = 128

/**
 * Safe scalar evidence projected from a runtime video format.
 *
 * Values are normalized again by [PlaybackRuntimeMeasurementState] so malformed platform evidence
 * cannot escape through the stable player API or affect playback.
 */
internal data class PlaybackRuntimeVideoFormatEvidence(
    val codec: PlaybackRuntimeVideoCodec? = null,
    val widthPixels: Int? = null,
    val heightPixels: Int? = null,
    val bitrateBitsPerSecond: Int? = null,
    val hdr: PlaybackRuntimeHdr = PlaybackRuntimeHdr.UNKNOWN,
)

/**
 * Process-local state for the active internal playback attempt.
 *
 * Generation is deliberately internal and is never exposed by [snapshot]. All mutators are
 * fail-closed for stale or malformed evidence: diagnostics must never change playback behavior.
 */
internal class PlaybackRuntimeMeasurementState : PlaybackRuntimeMeasurementReader {
    private var activeGeneration: Long? = null
    private var transport: PlaybackRuntimeTransport? = null
    private var videoCodec: PlaybackRuntimeVideoCodec? = null
    private var videoWidthPixels: Int? = null
    private var videoHeightPixels: Int? = null
    private var videoBitrateBitsPerSecond: Int? = null
    private var hdr: PlaybackRuntimeHdr = PlaybackRuntimeHdr.UNKNOWN
    private var firstFrameLatencyMillis: Long? = null
    private var rebufferCount: Int = 0
    private var completedRebufferDurationMillis: Long = 0L
    private var videoDecoderName: String? = null
    private var videoDecoderInitializationDurationMillis: Long? = null
    private var decoderInitializationFailureCount: Int = 0
    private var videoCodecErrorCount: Int = 0
    private var droppedVideoFrameCount: Int = 0
    private var lowRamDevice: Boolean? = null
    private var memoryClassMb: Int? = null
    private var reachedReadyOnce: Boolean = false
    private var rebufferStartedAtRealtimeMs: Long? = null

    @Synchronized
    fun activate(
        generation: Long,
        transport: PlaybackRuntimeTransport,
        deviceSummary: DevicePlaybackProfileSummary?,
    ) {
        if (generation <= 0L) return

        activeGeneration = generation
        this.transport = transport
        videoCodec = null
        videoWidthPixels = null
        videoHeightPixels = null
        videoBitrateBitsPerSecond = null
        hdr = PlaybackRuntimeHdr.UNKNOWN
        firstFrameLatencyMillis = null
        rebufferCount = 0
        completedRebufferDurationMillis = 0L
        clearDecoderMeasurements()
        reachedReadyOnce = false
        rebufferStartedAtRealtimeMs = null
        clearDeviceSummary()
        deviceSummary?.let(::applyDeviceSummary)
    }

    @Synchronized
    fun onDeviceSummary(
        generation: Long,
        deviceSummary: DevicePlaybackProfileSummary,
    ) {
        if (!isActive(generation)) return
        applyDeviceSummary(deviceSummary)
    }

    @Synchronized
    fun onBuffering(generation: Long, realtimeMs: Long) {
        if (!isActive(generation) || realtimeMs < 0L || !reachedReadyOnce) return
        if (rebufferStartedAtRealtimeMs == null) {
            rebufferStartedAtRealtimeMs = realtimeMs
        }
    }

    @Synchronized
    fun onReady(generation: Long, realtimeMs: Long) {
        if (!isActive(generation) || realtimeMs < 0L) return

        if (!reachedReadyOnce) {
            reachedReadyOnce = true
            rebufferStartedAtRealtimeMs = null
            return
        }

        val startedAt = rebufferStartedAtRealtimeMs ?: return
        rebufferStartedAtRealtimeMs = null
        val duration = (realtimeMs - startedAt).coerceAtLeast(0L)
        rebufferCount = saturatedAdd(rebufferCount, 1)
        completedRebufferDurationMillis = saturatedAdd(
            completedRebufferDurationMillis,
            duration,
        )
    }

    @Synchronized
    fun onFirstFrame(generation: Long, latencyMillis: Long) {
        if (!isActive(generation) || latencyMillis < 0L || firstFrameLatencyMillis != null) return
        firstFrameLatencyMillis = latencyMillis
    }

    @Synchronized
    fun onVideoFormat(
        generation: Long,
        evidence: PlaybackRuntimeVideoFormatEvidence,
    ) {
        if (!isActive(generation)) return
        videoCodec = evidence.codec
        videoWidthPixels = evidence.widthPixels?.takeIf { it > 0 }
        videoHeightPixels = evidence.heightPixels?.takeIf { it > 0 }
        videoBitrateBitsPerSecond = evidence.bitrateBitsPerSecond?.takeIf { it > 0 }
        hdr = evidence.hdr
    }

    @Synchronized
    fun onVideoDecoderInitialized(
        generation: Long,
        decoderName: String,
        initializationDurationMillis: Long,
    ) {
        if (!isActive(generation) || initializationDurationMillis < 0L) return
        val safeDecoderName = decoderName.trim()
            .takeIf(String::isNotEmpty)
            ?.take(MAX_DECODER_NAME_LENGTH)
            ?: return
        videoDecoderName = safeDecoderName
        videoDecoderInitializationDurationMillis = initializationDurationMillis
    }

    @Synchronized
    fun onDecoderInitializationFailure(generation: Long) {
        if (!isActive(generation)) return
        decoderInitializationFailureCount = saturatedAdd(decoderInitializationFailureCount, 1)
    }

    @Synchronized
    fun onVideoCodecError(generation: Long) {
        if (!isActive(generation)) return
        videoCodecErrorCount = saturatedAdd(videoCodecErrorCount, 1)
    }

    @Synchronized
    fun onDroppedVideoFrames(generation: Long, count: Int) {
        if (!isActive(generation) || count <= 0) return
        droppedVideoFrameCount = saturatedAdd(droppedVideoFrameCount, count)
    }

    @Synchronized
    fun clear() {
        activeGeneration = null
        transport = null
        videoCodec = null
        videoWidthPixels = null
        videoHeightPixels = null
        videoBitrateBitsPerSecond = null
        hdr = PlaybackRuntimeHdr.UNKNOWN
        firstFrameLatencyMillis = null
        rebufferCount = 0
        completedRebufferDurationMillis = 0L
        clearDecoderMeasurements()
        clearDeviceSummary()
        reachedReadyOnce = false
        rebufferStartedAtRealtimeMs = null
    }

    @Synchronized
    override fun snapshot(): PlaybackRuntimeMeasurementSnapshot? {
        if (activeGeneration == null) return null
        val activeTransport = transport ?: return null
        return PlaybackRuntimeMeasurementSnapshot(
            transport = activeTransport,
            videoCodec = videoCodec,
            videoWidthPixels = videoWidthPixels,
            videoHeightPixels = videoHeightPixels,
            videoBitrateBitsPerSecond = videoBitrateBitsPerSecond,
            hdr = hdr,
            firstFrameLatencyMillis = firstFrameLatencyMillis,
            rebufferCount = rebufferCount,
            completedRebufferDurationMillis = completedRebufferDurationMillis,
            videoDecoderName = videoDecoderName,
            videoDecoderInitializationDurationMillis = videoDecoderInitializationDurationMillis,
            decoderInitializationFailureCount = decoderInitializationFailureCount,
            videoCodecErrorCount = videoCodecErrorCount,
            droppedVideoFrameCount = droppedVideoFrameCount,
            lowRamDevice = lowRamDevice,
            memoryClassMb = memoryClassMb,
        )
    }

    private fun applyDeviceSummary(deviceSummary: DevicePlaybackProfileSummary) {
        lowRamDevice = deviceSummary.lowRamDevice
        memoryClassMb = deviceSummary.memoryClassMb
    }

    private fun clearDeviceSummary() {
        lowRamDevice = null
        memoryClassMb = null
    }

    private fun clearDecoderMeasurements() {
        videoDecoderName = null
        videoDecoderInitializationDurationMillis = null
        decoderInitializationFailureCount = 0
        videoCodecErrorCount = 0
        droppedVideoFrameCount = 0
    }

    private fun isActive(generation: Long): Boolean =
        generation > 0L && activeGeneration == generation

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right

    private fun saturatedAdd(left: Int, right: Int): Int =
        if (right > Int.MAX_VALUE - left) Int.MAX_VALUE else left + right
}
