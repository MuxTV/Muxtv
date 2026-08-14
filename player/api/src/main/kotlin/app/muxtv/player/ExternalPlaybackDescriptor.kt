package app.muxtv.player

import java.net.URI

/**
 * Process-local transient descriptor for one external media playback intent.
 *
 * Constraints:
 * - never Parcelable/Serializable;
 * - never stored in SavedState, Room, DataStore or persistent history;
 * - never placed into a MediaSession custom-command Bundle;
 * - [toString] is fully redacted.
 *
 * The locator exists only inside the lease registry, the playback service and the media source
 * factory within this process.
 */
data class ExternalPlaybackDescriptor(
    val locator: String,
    val mimeType: String? = null,
    val displayTitle: String? = null,
    val sourcePackage: String? = null,
    val cleartextApproved: Boolean = false,
) {
    init {
        require(locator.isValidLocator())
        require(mimeType == null || mimeType.isValidMimeType())
        require(displayTitle == null || displayTitle.isValidDisplayText())
        require(sourcePackage == null || sourcePackage.isValidSourcePackage())
    }

    val isCleartext: Boolean
        get() = locator.startsWith(HTTP_SCHEME_PREFIX)

    override fun toString(): String =
        "ExternalPlaybackDescriptor(locator=<redacted>, " +
            "hasMimeType=${mimeType != null}, hasDisplayTitle=${displayTitle != null}, " +
            "sourcePackage=<redacted>, cleartextApproved=$cleartextApproved)"

    private companion object {
        const val HTTP_SCHEME_PREFIX = "http://"
        const val MAX_LOCATOR_LENGTH = 8_192
        const val MAX_MIME_LENGTH = 256
        const val MAX_DISPLAY_TEXT_LENGTH = 512
        const val MAX_SOURCE_PACKAGE_LENGTH = 256
        val VALID_SOURCE_PACKAGE = Regex("[A-Za-z0-9._-]{1,$MAX_SOURCE_PACKAGE_LENGTH}")

        fun String.isValidLocator(): Boolean {
            if (isBlank() || length > MAX_LOCATOR_LENGTH) return false
            if (contains('\r') || contains('\n')) return false
            val parsed = runCatching { URI(this) }.getOrNull() ?: return false
            val scheme = parsed.scheme?.lowercase() ?: return false
            if (scheme != "http" && scheme != "https") return false
            if (parsed.userInfo != null) return false
            if (parsed.host.isNullOrBlank()) return false
            return true
        }

        fun String.isValidMimeType(): Boolean =
            isNotBlank() &&
                length <= MAX_MIME_LENGTH &&
                !contains('\r') &&
                !contains('\n') &&
                none { it.isISOControl() }

        fun String.isValidDisplayText(): Boolean =
            isNotBlank() &&
                length <= MAX_DISPLAY_TEXT_LENGTH &&
                !contains('\r') &&
                !contains('\n') &&
                none { it.isISOControl() }

        fun String.isValidSourcePackage(): Boolean = VALID_SOURCE_PACKAGE.matches(this)
    }
}
