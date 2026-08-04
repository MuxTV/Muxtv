package app.muxtv.player.media3

/**
 * Service-thread ownership guard for the first rendered frame of the latest playback setup.
 *
 * The setup id prevents stale cancellation/renderer callbacks from completing a newer request.
 * The current MediaItem id must also match the canonical channel id installed for that setup.
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

    fun onRenderedFirstFrame(currentMediaId: String?): PlaybackFirstFrameEvent? {
        val event = synchronized(this) {
            val current = active ?: return@synchronized null
            if (current.firstFrameReported || currentMediaId != current.channelId) {
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
