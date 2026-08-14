package app.muxtv.external

sealed interface ExternalPlaybackIntentResult {
    data class Accepted(
        val locator: String,
        val mimeType: String?,
        val displayTitle: String?,
        val sourcePackage: String?,
    ) : ExternalPlaybackIntentResult

    data class Rejected(
        val reason: ExternalPlaybackIntentRejection,
    ) : ExternalPlaybackIntentResult
}

enum class ExternalPlaybackIntentRejection {
    WrongAction,
    MissingUri,
    UnsupportedScheme,
    MissingHost,
    EmbeddedCredentials,
    UnsupportedMimeType,
    UriTooLong,
    InvalidMetadata,
}
