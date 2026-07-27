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
