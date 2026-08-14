package app.muxtv.player.media3

import app.muxtv.player.PlaybackRequest
import java.util.Collections

class PlaybackSessionRequest(
    val profileId: String,
    val mediaId: String,
    val variantId: String,
    val locator: String,
    val displayName: String? = null,
    val artworkUri: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    val insecureHttpApproved: Boolean = false,
    val mimeType: String? = null,
) {
    val requestHeaders: Map<String, String> = requestHeaders.immutableSnapshot()

    init {
        require(profileId.isValidField(MAX_ID_LENGTH))
        require(mediaId.isValidField(MAX_ID_LENGTH))
        require(variantId.isValidField(MAX_ID_LENGTH))
        require(locator.isValidField(MAX_LOCATOR_LENGTH))
        require(displayName == null || displayName.isValidField(MAX_DISPLAY_NAME_LENGTH))
        require(artworkUri == null || artworkUri.isValidField(MAX_LOCATOR_LENGTH))
        require(mimeType == null || mimeType.isValidField(MAX_MIME_LENGTH))
        require(this.requestHeaders.size <= MAX_HEADER_COUNT)
        this.requestHeaders.forEach { (name, value) ->
            require(name.isValidField(MAX_HEADER_NAME_LENGTH))
            require(value.isValidField(MAX_HEADER_VALUE_LENGTH))
        }
    }

    operator fun component1(): String = mediaId
    operator fun component2(): String = variantId
    operator fun component3(): String = locator
    operator fun component4(): String? = displayName
    operator fun component5(): String? = artworkUri
    operator fun component6(): Map<String, String> = requestHeaders
    operator fun component7(): Boolean = insecureHttpApproved
    operator fun component8(): String? = mimeType

    fun copy(
        profileId: String = this.profileId,
        mediaId: String = this.mediaId,
        variantId: String = this.variantId,
        locator: String = this.locator,
        displayName: String? = this.displayName,
        artworkUri: String? = this.artworkUri,
        requestHeaders: Map<String, String> = this.requestHeaders,
        insecureHttpApproved: Boolean = this.insecureHttpApproved,
        mimeType: String? = this.mimeType,
    ): PlaybackSessionRequest = PlaybackSessionRequest(
        profileId = profileId,
        mediaId = mediaId,
        variantId = variantId,
        locator = locator,
        displayName = displayName,
        artworkUri = artworkUri,
        requestHeaders = requestHeaders,
        insecureHttpApproved = insecureHttpApproved,
        mimeType = mimeType,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackSessionRequest) return false
        return profileId == other.profileId &&
            mediaId == other.mediaId &&
            variantId == other.variantId &&
            locator == other.locator &&
            displayName == other.displayName &&
            artworkUri == other.artworkUri &&
            requestHeaders == other.requestHeaders &&
            insecureHttpApproved == other.insecureHttpApproved &&
            mimeType == other.mimeType
    }

    override fun hashCode(): Int {
        var result = profileId.hashCode()
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + variantId.hashCode()
        result = 31 * result + locator.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (artworkUri?.hashCode() ?: 0)
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + insecureHttpApproved.hashCode()
        result = 31 * result + (mimeType?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PlaybackSessionRequest(profileId=<redacted>, mediaId=<redacted>, " +
            "variantId=<redacted>, locator=<redacted>, " +
            "hasDisplayName=${displayName != null}, hasArtworkUri=${artworkUri != null}, " +
            "headerCount=${requestHeaders.size}, insecureHttpApproved=$insecureHttpApproved, " +
            "hasMimeType=${mimeType != null})"

    companion object {
        private const val MAX_ID_LENGTH = 512
        private const val MAX_LOCATOR_LENGTH = 8_192
        private const val MAX_DISPLAY_NAME_LENGTH = 512
        private const val MAX_MIME_LENGTH = 256
        private const val MAX_HEADER_COUNT = 32
        private const val MAX_HEADER_NAME_LENGTH = 256
        private const val MAX_HEADER_VALUE_LENGTH = 8_192

    }
}

fun PlaybackRequest.toPlaybackSessionRequest(profileId: String): PlaybackSessionRequest =
    PlaybackSessionRequest(
        profileId = profileId,
        mediaId = mediaId,
        variantId = variantId.value,
        locator = locator,
        displayName = displayName,
        artworkUri = artworkUri,
        requestHeaders = requestHeaders,
        insecureHttpApproved = insecureHttpApproved,
    )

private fun String.isValidField(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && !contains('\r') && !contains('\n')

private fun Map<String, String>.immutableSnapshot(): Map<String, String> =
    Collections.unmodifiableMap(LinkedHashMap(this))
