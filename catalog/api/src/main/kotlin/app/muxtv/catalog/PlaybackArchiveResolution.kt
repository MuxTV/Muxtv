package app.muxtv.catalog

import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline

/** Persisted provider archive metadata projected only at the playback resolution boundary. */
data class PlaybackArchiveMetadata(
    val mode: String?,
    val source: String?,
    val days: Int?,
    val correction: String?,
) {
    override fun toString(): String =
        "PlaybackArchiveMetadata(hasMode=${mode != null}, source=<redacted>, days=$days, " +
            "hasCorrection=${correction != null})"
}

/** Provider-neutral archive materialization request. */
data class PlaybackArchiveRequest(
    val intent: PlaybackIntent,
    val livePlaybackReference: String,
    val metadata: PlaybackArchiveMetadata,
) {
    init {
        require(livePlaybackReference.isNotBlank())
    }

    override fun toString(): String =
        "PlaybackArchiveRequest(intent=$intent, livePlaybackReference=<redacted>, metadata=$metadata)"
}

enum class PlaybackArchiveUnavailableReason {
    OutsideRetention,
    UnsupportedMode,
    InvalidMetadata,
}

sealed interface PlaybackArchiveResolution {
    data object NotApplicable : PlaybackArchiveResolution

    data class Ready(
        val locator: String,
        val timeline: ResolvedPlaybackTimeline,
    ) : PlaybackArchiveResolution {
        init {
            require(locator.isNotBlank())
        }

        override fun toString(): String =
            "PlaybackArchiveResolution.Ready(locator=<redacted>, timeline=$timeline)"
    }

    data class Unavailable(
        val reason: PlaybackArchiveUnavailableReason,
    ) : PlaybackArchiveResolution
}

fun interface PlaybackArchiveResolver {
    fun resolve(request: PlaybackArchiveRequest): PlaybackArchiveResolution
}

/** Safe default for call sites that have no provider archive adapter installed. */
object UnhandledPlaybackArchiveResolver : PlaybackArchiveResolver {
    override fun resolve(request: PlaybackArchiveRequest): PlaybackArchiveResolution =
        PlaybackArchiveResolution.NotApplicable
}
