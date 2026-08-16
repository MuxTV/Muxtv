package app.muxtv.player.media3

import android.os.SystemClock
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Test-side analytics probe that measures the resilience characteristics the EP-08 evidence
 * slice needs: applied seek counts and seek-to-confirmation latencies, rebuffer cycles after
 * the first ready transition, and player errors. No media URIs, headers or labels are captured.
 */
@AndroidXOptIn(UnstableApi::class)
class PlaybackResilienceProbe : AnalyticsListener {
    @Volatile
    var seekStartedCount = 0
        private set

    @Volatile
    var seekCompletedCount = 0
        private set

    @Volatile
    var rebufferStartCount = 0
        private set

    @Volatile
    var rebufferEndCount = 0
        private set

    @Volatile
    var playerErrorCount = 0
        private set

    @Volatile
    var lastError: PlaybackException? = null
        private set

    /** Elapsed time from `onSeekStarted` to the matching seek discontinuity, per applied seek. */
    val seekLatenciesMillis = CopyOnWriteArrayList<Long>()

    private var reachedReadyOnce = false
    private var pendingSeekStartedAtNanos = -1L

    override fun onSeekStarted(eventTime: AnalyticsListener.EventTime) {
        seekStartedCount++
        pendingSeekStartedAtNanos = SystemClock.elapsedRealtimeNanos()
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            seekCompletedCount++
            val startedAt = pendingSeekStartedAtNanos
            if (startedAt >= 0L) {
                seekLatenciesMillis.add(
                    (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000L,
                )
                pendingSeekStartedAtNanos = -1L
            }
        }
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        when {
            state == Player.STATE_READY && !reachedReadyOnce -> reachedReadyOnce = true
            state == Player.STATE_READY -> rebufferEndCount++
            state == Player.STATE_BUFFERING && reachedReadyOnce -> rebufferStartCount++
        }
    }

    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        playerErrorCount++
        lastError = error
    }
}
