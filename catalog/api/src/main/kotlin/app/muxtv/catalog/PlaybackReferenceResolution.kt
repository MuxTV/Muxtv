package app.muxtv.catalog

/**
 * Provider-neutral request for resolving a persisted playback identity into an ephemeral transport locator.
 * Controlled reference values are deliberately excluded from diagnostics.
 */
data class PlaybackReferenceRequest(
    val credentialRef: String,
    val playbackReference: String,
) {
    override fun toString(): String =
        "PlaybackReferenceRequest(credentialRefPresent=${credentialRef.isNotEmpty()}, playbackReference=<redacted>)"
}

/**
 * Result of resolving a persisted playback reference before the existing playback access policy is applied.
 */
sealed interface PlaybackReferenceResolution {
    /** The reference is already a direct locator and should continue through the existing path unchanged. */
    data object Unhandled : PlaybackReferenceResolution

    /** The persisted provider reference is malformed or uses an unsupported provider namespace. */
    data object InvalidReference : PlaybackReferenceResolution

    /** The source no longer has the credential record required to resolve its provider reference. */
    data object CredentialNotFound : PlaybackReferenceResolution

    /** The credential record exists but cannot be decoded safely. */
    data object CredentialCorrupted : PlaybackReferenceResolution

    /** Secure credential storage is temporarily unavailable. */
    data object CredentialUnavailable : PlaybackReferenceResolution

    /** Cleartext provider access exists but has not been explicitly approved. */
    data object ApprovalRequired : PlaybackReferenceResolution

    /**
     * Ephemeral transport output. The locator can contain credentials and therefore must never be logged,
     * persisted, used as identity, or included in [toString].
     */
    class Ready(
        val locator: String,
        val insecureHttpPreapproved: Boolean,
    ) : PlaybackReferenceResolution {
        override fun toString(): String =
            "PlaybackReferenceResolution.Ready(locator=<redacted>, insecureHttpPreapproved=$insecureHttpPreapproved)"
    }
}

fun interface PlaybackReferenceResolver {
    suspend fun resolve(request: PlaybackReferenceRequest): PlaybackReferenceResolution
}
