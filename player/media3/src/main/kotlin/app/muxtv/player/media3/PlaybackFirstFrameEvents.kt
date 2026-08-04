package app.muxtv.player.media3

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Privacy-safe observation emitted only after Media3 renders the first frame for the active setup.
 *
 * The canonical channel id is available to typed consumers, but diagnostics redact it. URLs,
 * headers, provider locators, programme labels and query text never enter this event.
 */
class PlaybackFirstFrameEvent(
    val channelId: String,
    val activationElapsedMillis: Long,
) {
    init {
        require(channelId.isNotBlank())
        require(activationElapsedMillis >= 0L)
    }

    override fun equals(other: Any?): Boolean =
        other is PlaybackFirstFrameEvent &&
            channelId == other.channelId &&
            activationElapsedMillis == other.activationElapsedMillis

    override fun hashCode(): Int =
        31 * channelId.hashCode() + activationElapsedMillis.hashCode()

    override fun toString(): String =
        "PlaybackFirstFrameEvent(channelId=<redacted>, " +
            "activationElapsedMillis=$activationElapsedMillis)"
}

/**
 * Process-local observation stream for measurement and later durable consumers.
 *
 * The service is the only publisher. This stream is deliberately non-replaying: consumers must
 * be active before playback and durable state must remain idempotent at its own storage boundary.
 */
@Singleton
class PlaybackFirstFrameEvents @Inject constructor() {
    private val mutableEvents = MutableSharedFlow<PlaybackFirstFrameEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )

    val events: SharedFlow<PlaybackFirstFrameEvent> = mutableEvents.asSharedFlow()

    internal fun publish(event: PlaybackFirstFrameEvent) {
        mutableEvents.tryEmit(event)
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 16
    }
}
