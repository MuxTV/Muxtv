package app.muxtv.player.media3

import android.os.Bundle
import app.muxtv.player.PlaybackRequest

data class PlaybackSessionRequest(
    val mediaId: String,
    val variantId: String,
    val locator: String,
    val displayName: String? = null,
    val artworkUri: String? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
) {
    init {
        require(mediaId.isValidField(MAX_ID_LENGTH))
        require(variantId.isValidField(MAX_ID_LENGTH))
        require(locator.isValidField(MAX_LOCATOR_LENGTH))
        require(displayName == null || displayName.isValidField(MAX_DISPLAY_NAME_LENGTH))
        require(artworkUri == null || artworkUri.isValidField(MAX_LOCATOR_LENGTH))
        require(requestHeaders.size <= MAX_HEADER_COUNT)
        requestHeaders.forEach { (name, value) ->
            require(name.isValidField(MAX_HEADER_NAME_LENGTH))
            require(value.isValidField(MAX_HEADER_VALUE_LENGTH))
        }
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_MEDIA_ID, mediaId)
        putString(KEY_VARIANT_ID, variantId)
        putString(KEY_LOCATOR, locator)
        displayName?.let { putString(KEY_DISPLAY_NAME, it) }
        artworkUri?.let { putString(KEY_ARTWORK_URI, it) }
        putBundle(
            KEY_HEADERS,
            Bundle().apply {
                requestHeaders.forEach { (name, value) -> putString(name, value) }
            },
        )
    }

    override fun toString(): String =
        "PlaybackSessionRequest(mediaId=$mediaId, variantId=$variantId, locator=<redacted>, " +
            "displayName=$displayName, artworkUri=${artworkUri != null}, " +
            "requestHeaders=${requestHeaders.keys.sorted()})"

    companion object {
        private const val KEY_MEDIA_ID = "media_id"
        private const val KEY_VARIANT_ID = "variant_id"
        private const val KEY_LOCATOR = "locator"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_ARTWORK_URI = "artwork_uri"
        private const val KEY_HEADERS = "headers"

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
)

private fun String.isValidField(maxLength: Int): Boolean =
    isNotBlank() && length <= maxLength && !contains('\r') && !contains('\n')
