package app.muxtv.di

import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.player.media3.PlaybackFirstFrameEvent
import app.muxtv.player.media3.PlaybackFirstFrameObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Persists successful playback only after the service-owned player renders its first frame.
 *
 * Media3 owns monotonic activation timing. This boundary owns wall-clock history time and forwards
 * only profile/channel identity into the bounded Recent repository.
 */
class RecentPlaybackObserver(
    private val repository: RecentChannelsRepository,
    private val scope: CoroutineScope,
    private val nowEpochMillis: () -> Long,
) : PlaybackFirstFrameObserver {
    override fun onFirstFrame(event: PlaybackFirstFrameEvent) {
        val successfulAtEpochMillis = nowEpochMillis()
        scope.launch {
            try {
                repository.recordSuccessfulPlayback(
                    profileId = event.profileId,
                    channelId = event.channelId,
                    successfulAtEpochMillis = successfulAtEpochMillis,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Recent persistence is ancillary and must never break playback or other observers.
            }
        }
    }
}
