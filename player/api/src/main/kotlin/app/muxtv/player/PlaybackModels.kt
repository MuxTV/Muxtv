package app.muxtv.player

import app.muxtv.common.StreamVariantId as CommonStreamVariantId
import app.muxtv.common.TrackId as CommonTrackId
import java.util.Collections


typealias StreamVariantId = CommonStreamVariantId
typealias TrackId = CommonTrackId

class PlaybackRequest(
    val variantId: StreamVariantId,
    val locator: String,
    val mediaId: String = variantId.toString(),
    val displayName: String? = null,
    val artworkUri: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    val insecureHttpApproved: Boolean = false,
) {
    val requestHeaders: Map<String, String> = requestHeaders.immutableSnapshot()

    init {
        require(locator.isNotBlank())
        require(mediaId.isNotBlank())
        require(displayName == null || displayName.isNotBlank())
        require(artworkUri == null || artworkUri.isNotBlank())
        require(this.requestHeaders.size <= MAX_REQUEST_HEADERS)
        this.requestHeaders.forEach { (name, value) ->
            require(name.isNotBlank())
            require(value.isNotBlank())
            require(!name.contains('\r') && !name.contains('\n'))
            require(!value.contains('\r') && !value.contains('\n'))
        }
    }

    operator fun component1(): StreamVariantId = variantId
    operator fun component2(): String = locator
    operator fun component3(): String = mediaId
    operator fun component4(): String? = displayName
    operator fun component5(): String? = artworkUri
    operator fun component6(): Map<String, String> = requestHeaders
    operator fun component7(): Boolean = insecureHttpApproved

    fun copy(
        variantId: StreamVariantId = this.variantId,
        locator: String = this.locator,
        mediaId: String = this.mediaId,
        displayName: String? = this.displayName,
        artworkUri: String? = this.artworkUri,
        requestHeaders: Map<String, String> = this.requestHeaders,
        insecureHttpApproved: Boolean = this.insecureHttpApproved,
    ): PlaybackRequest = PlaybackRequest(
        variantId = variantId,
        locator = locator,
        mediaId = mediaId,
        displayName = displayName,
        artworkUri = artworkUri,
        requestHeaders = requestHeaders,
        insecureHttpApproved = insecureHttpApproved,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackRequest) return false
        return variantId == other.variantId &&
            locator == other.locator &&
            mediaId == other.mediaId &&
            displayName == other.displayName &&
            artworkUri == other.artworkUri &&
            requestHeaders == other.requestHeaders &&
            insecureHttpApproved == other.insecureHttpApproved
    }

    override fun hashCode(): Int {
        var result = variantId.hashCode()
        result = 31 * result + locator.hashCode()
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (artworkUri?.hashCode() ?: 0)
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + insecureHttpApproved.hashCode()
        return result
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

private fun Map<String, String>.immutableSnapshot(): Map<String, String> =
    Collections.unmodifiableMap(LinkedHashMap(this))
