package app.muxtv.player.media3

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Privacy-safe observation emitted only after Media3 renders the first frame for the active setup.
 *
 * Typed consumers receive exact profile/channel identity. Diagnostics redact both identifiers.
 * URLs, headers, provider locators, programme labels and query text never enter this event.
 */
class PlaybackFirstFrameEvent(
    val profileId: String,
    val channelId: String,
    val activationElapsedMillis: Long,
) {
    init {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())
        require(activationElapsedMillis >= 0L)
    }

    override fun equals(other: Any?): Boolean =
        other is PlaybackFirstFrameEvent &&
            profileId == other.profileId &&
            channelId == other.channelId &&
            activationElapsedMillis == other.activationElapsedMillis

    override fun hashCode(): Int {
        var result = profileId.hashCode()
        result = 31 * result + channelId.hashCode()
        result = 31 * result + activationElapsedMillis.hashCode()
        return result
    }

    override fun toString(): String =
        "PlaybackFirstFrameEvent(profileId=<redacted>, channelId=<redacted>, " +
            "activationElapsedMillis=$activationElapsedMillis)"
}

fun interface PlaybackFirstFrameObserver {
    fun onFirstFrame(event: PlaybackFirstFrameEvent)
}

/**
 * Direct fan-out boundary owned by the playback service.
 *
 * Future durable Recent persistence is contributed as another observer. A failing ordinary
 * observer is isolated so it cannot suppress later observers; VM/linkage errors are not hidden.
 */
@Singleton
class PlaybackFirstFrameRecorder @Inject constructor(
    private val observers: Set<@JvmSuppressWildcards PlaybackFirstFrameObserver>,
) {
    fun record(event: PlaybackFirstFrameEvent) {
        observers.forEach { observer ->
            try {
                observer.onFirstFrame(event)
            } catch (_: Exception) {
                // Observer-specific persistence/telemetry failure must not block other consumers.
            }
        }
    }
}

/**
 * Optional process-local observation stream for measurement and diagnostics.
 *
 * This is one recorder observer, not the durable delivery mechanism for Recent.
 */
@Singleton
class PlaybackFirstFrameEvents @Inject constructor() : PlaybackFirstFrameObserver {
    private val mutableEvents = MutableSharedFlow<PlaybackFirstFrameEvent>(
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
    )

    val events: SharedFlow<PlaybackFirstFrameEvent> = mutableEvents.asSharedFlow()

    override fun onFirstFrame(event: PlaybackFirstFrameEvent) {
        mutableEvents.tryEmit(event)
    }

    private companion object {
        const val EVENT_BUFFER_CAPACITY = 16
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PlaybackFirstFrameObserverModule {
    @Binds
    @IntoSet
    abstract fun bindPlaybackFirstFrameEvents(
        events: PlaybackFirstFrameEvents,
    ): PlaybackFirstFrameObserver
}
