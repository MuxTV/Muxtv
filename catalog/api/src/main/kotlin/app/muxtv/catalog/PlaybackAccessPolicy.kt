package app.muxtv.catalog

sealed interface PlaybackAccessDecision {
    data object SecureTransport : PlaybackAccessDecision
    data object Approved : PlaybackAccessDecision

    data class ApprovalRequired(
        val displayOrigin: String,
    ) : PlaybackAccessDecision {
        init {
            require(displayOrigin.isNotBlank())
        }

        override fun toString(): String = "ApprovalRequired(displayOrigin=<redacted>)"
    }

    data object CredentialNotFound : PlaybackAccessDecision
    data object CredentialCorrupted : PlaybackAccessDecision
    data object CredentialUnavailable : PlaybackAccessDecision
    data object InvalidLocator : PlaybackAccessDecision
}

sealed interface PlaybackAccessMutationResult {
    data object Applied : PlaybackAccessMutationResult
    data object Unchanged : PlaybackAccessMutationResult
    data object NotFound : PlaybackAccessMutationResult
    data object Corrupted : PlaybackAccessMutationResult
    data object Unavailable : PlaybackAccessMutationResult
    data object InvalidLocator : PlaybackAccessMutationResult
    data object CapacityExceeded : PlaybackAccessMutationResult
}

interface PlaybackAccessPolicyResolver {
    suspend fun resolve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessDecision

    suspend fun approve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult

    suspend fun revoke(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult

    suspend fun revokeAll(credentialRef: String): PlaybackAccessMutationResult
}

/**
 * Safe fallback used only when database initialization is needed without the application security graph.
 * Production playback wiring must provide the encrypted resolver explicitly.
 */
object RejectAllPlaybackAccessPolicyResolver : PlaybackAccessPolicyResolver {
    override suspend fun resolve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessDecision = PlaybackAccessDecision.CredentialUnavailable

    override suspend fun approve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unavailable

    override suspend fun revoke(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unavailable

    override suspend fun revokeAll(credentialRef: String): PlaybackAccessMutationResult =
        PlaybackAccessMutationResult.Unavailable
}
