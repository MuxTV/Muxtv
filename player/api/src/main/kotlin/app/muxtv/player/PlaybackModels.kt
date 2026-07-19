package app.muxtv.player

import app.muxtv.common.StreamVariantId as CommonStreamVariantId
import app.muxtv.common.TrackId as CommonTrackId

typealias StreamVariantId = CommonStreamVariantId
typealias TrackId = CommonTrackId

data class PlaybackRequest(
    val variantId: StreamVariantId,
    val locator: String,
) {
    init { require(locator.isNotBlank()) }
}

enum class PlaybackTrackKind { AUDIO, SUBTITLE, VIDEO }

data class PlaybackTrack(
    val id: TrackId,
    val kind: PlaybackTrackKind,
    val language: String?,
    val label: String?,
)

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Preparing : PlaybackState
    data object Playing : PlaybackState
    data object Paused : PlaybackState
    data object Stopped : PlaybackState
    data class Failed(val error: PlaybackError) : PlaybackState
}
