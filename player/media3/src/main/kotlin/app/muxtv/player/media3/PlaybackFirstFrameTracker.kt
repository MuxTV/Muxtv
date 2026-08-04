package app.muxtv.player.media3

/**
 * Service-thread ownership guard for the first rendered frame of the latest playback setup.
 *
 * Both the callback setup id and current MediaItem id must match the active setup. The explicit
 * callback id prevents a delayed renderer callback for the same canonical channel from completing
 * a newer setup generation.
 */
internal class PlaybackFirstFrameTracker(
    private val elapsedRealtimeNanos: () -> Long,
    private val publish: (PlaybackFirstFrameEvent) -> Unit,
) {
    private var active: ActivePlayback? = null

    @Synchronized
    fun activate(
        setupId: PlaybackSetupId,
        profileId: String,
        channelId: String,
    ) {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())
        active = ActivePlayback(
            setupId = setupId,
            profileId = profileId,
            channelId = channelId,
            startedAtNanos = elapsedRealtimeNanos(),
        )
    }

    @Synchronized
    fun clear(setupId: PlaybackSetupId): Boolean {
        val current = active ?: return false
        if (current.setupId != setupId) return false
        active = null
        return true
    }

    @Synchronized
    fun clearActive() {
        active = null
    }

    fun onRenderedFirstFrame(
        setupId: PlaybackSetupId,
        currentMediaId: String?,
    ): PlaybackFirstFrameEvent? {
        val event = synchronized(this) {
            val current = active ?: return@synchronized null
            if (
                current.firstFrameReported ||
                current.setupId != setupId ||
                currentMediaId != current.channelId
            ) {
                return@synchronized null
            }

            current.firstFrameReported = true
            val elapsedNanos = (elapsedRealtimeNanos() - current.startedAtNanos)
                .coerceAtLeast(0L)
            PlaybackFirstFrameEvent(
                profileId = current.profileId,
                channelId = current.channelId,
                activationElapsedMillis = elapsedNanos / NANOS_PER_MILLISECOND,
            )
        }

        event?.let(publish)
        return event
    }

    private class ActivePlayback(
        val setupId: PlaybackSetupId,
        val profileId: String,
        val channelId: String,
        val startedAtNanos: Long,
        var firstFrameReported: Boolean = false,
    )

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}
