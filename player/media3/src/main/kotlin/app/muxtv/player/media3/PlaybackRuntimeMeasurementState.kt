package app.muxtv.player.media3

import app.muxtv.player.DevicePlaybackProfileSummary
import app.muxtv.player.PlaybackRuntimeHdr
import app.muxtv.player.PlaybackRuntimeMeasurementReader
import app.muxtv.player.PlaybackRuntimeMeasurementSnapshot
import app.muxtv.player.PlaybackRuntimeTransport
import app.muxtv.player.PlaybackRuntimeVideoCodec

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
        reachedReadyOnce = false
        rebufferStartedAtRealtimeMs = null

        val safeMemoryClass = deviceSummary?.memoryClassMb?.takeIf { it > 0 }
        if (safeMemoryClass == null) {
            lowRamDevice = null
            memoryClassMb = null
        } else {
            lowRamDevice = deviceSummary.lowRamDevice
            memoryClassMb = safeMemoryClass
        }
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
        if (rebufferCount < Int.MAX_VALUE) rebufferCount++
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
        lowRamDevice = null
        memoryClassMb = null
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
            lowRamDevice = lowRamDevice,
            memoryClassMb = memoryClassMb,
        )
    }

    private fun isActive(generation: Long): Boolean =
        generation > 0L && activeGeneration == generation

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (right > Long.MAX_VALUE - left) Long.MAX_VALUE else left + right
}
