package app.muxtv.player.media3

import android.os.Bundle
import app.muxtv.player.PlaybackRequest
import java.util.Collections

class PlaybackSessionRequest(
    val mediaId: String,
    val variantId: String,
    val locator: String,
    val displayName: String? = null,
    val artworkUri: String? = null,
    requestHeaders: Map<String, String> = emptyMap(),
    val insecureHttpApproved: Boolean = false,
) {
    val requestHeaders: Map<String, String> = requestHeaders.immutableSnapshot()

    init {
        require(mediaId.isValidField(MAX_ID_LENGTH))
        require(variantId.isValidField(MAX_ID_LENGTH))
        require(locator.isValidField(MAX_LOCATOR_LENGTH))
        require(displayName == null || displayName.isValidField(MAX_DISPLAY_NAME_LENGTH))
        require(artworkUri == null || artworkUri.isValidField(MAX_LOCATOR_LENGTH))
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

    fun copy(
        mediaId: String = this.mediaId,
        variantId: String = this.variantId,
        locator: String = this.locator,
        displayName: String? = this.displayName,
        artworkUri: String? = this.artworkUri,
        requestHeaders: Map<String, String> = this.requestHeaders,
        insecureHttpApproved: Boolean = this.insecureHttpApproved,
    ): PlaybackSessionRequest = PlaybackSessionRequest(
        mediaId = mediaId,
        variantId = variantId,
        locator = locator,
        displayName = displayName,
        artworkUri = artworkUri,
        requestHeaders = requestHeaders,
        insecureHttpApproved = insecureHttpApproved,
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PlaybackSessionRequest) return false
        return mediaId == other.mediaId &&
            variantId == other.variantId &&
            locator == other.locator &&
            displayName == other.displayName &&
            artworkUri == other.artworkUri &&
            requestHeaders == other.requestHeaders &&
            insecureHttpApproved == other.insecureHttpApproved
    }

    override fun hashCode(): Int {
        var result = mediaId.hashCode()
        result = 31 * result + variantId.hashCode()
        result = 31 * result + locator.hashCode()
        result = 31 * result + (displayName?.hashCode() ?: 0)
        result = 31 * result + (artworkUri?.hashCode() ?: 0)
        result = 31 * result + requestHeaders.hashCode()
        result = 31 * result + insecureHttpApproved.hashCode()
        return result
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_MEDIA_ID, mediaId)
        putString(KEY_VARIANT_ID, variantId)
        putString(KEY_LOCATOR, locator)
        displayName?.let { putString(KEY_DISPLAY_NAME, it) }
        artworkUri?.let { putString(KEY_ARTWORK_URI, it) }
        putBoolean(KEY_INSECURE_HTTP_APPROVED, insecureHttpApproved)
        if (requestHeaders.isNotEmpty()) {
            putBundle(
                KEY_HEADERS,
                Bundle().apply {
                    requestHeaders.forEach { (name, value) -> putString(name, value) }
                },
            )
        }
    }

    override fun toString(): String =
        "PlaybackSessionRequest(mediaId=<redacted>, variantId=<redacted>, locator=<redacted>, " +
            "hasDisplayName=${displayName != null}, hasArtworkUri=${artworkUri != null}, " +
            "headerCount=${requestHeaders.size}, insecureHttpApproved=$insecureHttpApproved)"

    companion object {
        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_VARIANT_ID = "variant_id"
        private const val KEY_LOCATOR = "locator"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ARTWORK_URI = "artwork_uri"
        private const val KEY_HEADERS = "headers"
        private const val KEY_INSECURE_HTTP_APPROVED = "insecure_http_approved"

        private const val MAX_ID_LENGTH = 512
        private const val MAX_LOCATOR_LENGTH = 8_192
        private const val MAX_DISPLAY_NAME_LENGTH = 512
        private const val MAX_HEADER_COUNT = 32
        private const val MAX_HEADER_NAME_LENGTH = 256
        private const val MAX_HEADER_VALUE_LENGTH = 8_192

        fun fromBundle(bundle: Bundle): PlaybackSessionRequest? = runCatching {
            val mediaId = bundle.getString(KEY_MEDIA_ID) ?: return null
            val variantId = bundle.getString(KEY_VARIANT_ID) ?: return null
            val locator = bundle.getString(KEY_LOCATOR) ?: return null
            val headersBundle = bundle.getBundle(KEY_HEADERS)
            val headers = headersBundle
                ?.keySet()
                ?.associateWith { key -> headersBundle.getString(key) ?: return null }
                .orEmpty()

            PlaybackSessionRequest(
                mediaId = mediaId,
                variantId = variantId,
                locator = locator,
                displayName = bundle.getString(KEY_DISPLAY_NAME),
                artworkUri = bundle.getString(KEY_ARTWORK_URI),
                requestHeaders = headers,
                insecureHttpApproved = bundle.getBoolean(KEY_INSECURE_HTTP_APPROVED, false),
            )
        }.getOrNull()
    }
}

fun PlaybackRequest.toPlaybackSessionRequest(): PlaybackSessionRequest = PlaybackSessionRequest(
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

private fun Map<String, String>.immutableSnapshot(): Map<String, String> = when (size) {
    0 -> Collections.emptyMap()
    1 -> entries.first().let { entry -> Collections.singletonMap(entry.key, entry.value) }
    else -> Collections.unmodifiableMap(LinkedHashMap(this))
}