package app.muxtv.external

import java.net.URI

/**
 * Validates and sanitizes an external playback intent without touching Android types.
 *
 * The Android activity adapts [android.content.Intent] into plain strings so every rejection
 * path stays JVM-testable. Accepted locators keep their full query (including any TorrServer
 * torrent/file identifiers), which must remain process-local.
 */
object ExternalPlaybackIntentParser {
    const val ACTION_VIEW = "android.intent.action.VIEW"

    private const val MAX_LOCATOR_LENGTH = 8_192
    private const val MAX_MIME_LENGTH = 256
    private const val MAX_TITLE_LENGTH = 512
    private const val MAX_SOURCE_PACKAGE_LENGTH = 256

    fun parse(
        action: String?,
        uriString: String?,
        mimeType: String?,
        displayTitle: String?,
        sourcePackage: String?,
    ): ExternalPlaybackIntentResult {
        if (action != ACTION_VIEW) {
            return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.WrongAction,
            )
        }
        val rawUri = uriString
            ?: return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.MissingUri,
            )
        if (rawUri.length > MAX_LOCATOR_LENGTH) {
            return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.UriTooLong,
            )
        }
        val uri = runCatching { URI(rawUri) }.getOrNull()
            ?: return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.MissingUri,
            )
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.UnsupportedScheme,
            )
        }
        if (uri.userInfo != null) {
            return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.EmbeddedCredentials,
            )
        }
        if (uri.host.isNullOrBlank()) {
            return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.MissingHost,
            )
        }
        if (mimeType != null && mimeType.length > MAX_MIME_LENGTH) {
            return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.InvalidMetadata,
            )
        }
        if (!isSupportedMime(mimeType)) {
            return ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.UnsupportedMimeType,
            )
        }
        return ExternalPlaybackIntentResult.Accepted(
            locator = rawUri,
            mimeType = mimeType,
            displayTitle = sanitizeTitle(displayTitle),
            sourcePackage = sourcePackage?.takeIf { it.isValidSourcePackage() },
        )
    }

    private fun isSupportedMime(mimeType: String?): Boolean = when {
        mimeType == null -> true
        mimeType.any { it.isISOControl() } -> false
        else -> mimeType.startsWith("video/")
    }
    private fun sanitizeTitle(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .filterNot { it.isISOControl() || it.isBidiOverride() }
            .trim()
        return cleaned.takeIf { it.isNotEmpty() && it.length <= MAX_TITLE_LENGTH }
    }

    private fun String.isValidSourcePackage(): Boolean =
        isNotBlank() &&
            length <= MAX_SOURCE_PACKAGE_LENGTH &&
            all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

    private fun Char.isBidiOverride(): Boolean =
        code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0x200E || code == 0x200F
}
