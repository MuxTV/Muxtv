package app.muxtv.player

import app.muxtv.common.StreamVariantId as CommonStreamVariantId
import app.muxtv.common.TrackId as CommonTrackId

typealias StreamVariantId = CommonStreamVariantId
typealias TrackId = CommonTrackId

data class PlaybackRequest(
    val variantId: StreamVariantId,
    val locator: String,
    val mediaId: String = variantId.toString(),
    val displayName: String? = null,
    val artworkUri: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
    val insecureHttpApproved: Boolean = false,
) {
    init {
        require(locator.isNotBlank())
        require(mediaId.isNotBlank())
        require(displayName == null || displayName.isNotBlank())
        require(artworkUri == null || artworkUri.isNotBlank())
        require(requestHeaders.size <= MAX_REQUEST_HEADERS)
        requestHeaders.forEach { (name, value) ->
            require(name.isNotBlank())
            require(value.isNotBlank())
            require(!name.contains('\r') && !name.contains('\n'))
            require(!value.contains('\r') && !value.contains('\n'))
        }
    }

    override fun toString(): String =
        "PlaybackRequest(variantId=<redacted>, mediaId=<redacted>, locator=<redacted>, " +
            "hasDisplayName=${displayName != null}, hasArtworkUri=${artworkUri != null}, " +
            "headerCount=${requestHeaders.size}, insecureHttpApproved=$insecureHttpApproved)"

    private companion object {
        const val MAX_REQUEST_HEADERS = 32
    }
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
