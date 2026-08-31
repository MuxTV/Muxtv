package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialStore
import app.muxtv.network.ExactHttpOrigin
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class EncryptedPlaybackAccessPolicyResolver(
    private val accessManager: RemoteSourceAccessManager,
) : PlaybackAccessPolicyResolver {
    internal constructor(credentialStore: CredentialStore) :
        this(RemoteSourceAccessManager(credentialStore))

    override suspend fun resolve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessDecision {
        return when (val target = parseTarget(playbackLocator)) {
            PlaybackTarget.Secure -> PlaybackAccessDecision.SecureTransport
            PlaybackTarget.Invalid -> PlaybackAccessDecision.InvalidLocator
            is PlaybackTarget.Insecure -> {
                val credentialId = parseCredentialId(credentialRef)
                    ?: return PlaybackAccessDecision.CredentialNotFound
                when (val read = accessManager.read(credentialId)) {
                    is RemoteSourceAccessReadResult.Found ->
                        if (target.origin in read.access.approvedPlaybackOrigins) {
                            PlaybackAccessDecision.Approved
                        } else {
                            PlaybackAccessDecision.ApprovalRequired(target.origin.displayValue())
                        }

                    RemoteSourceAccessReadResult.NotFound ->
                        PlaybackAccessDecision.CredentialNotFound

                    RemoteSourceAccessReadResult.Corrupted ->
                        PlaybackAccessDecision.CredentialCorrupted

                    is RemoteSourceAccessReadResult.Unavailable ->
                        PlaybackAccessDecision.CredentialUnavailable
                }
            }
        }
    }

    override suspend fun validateMaterializedTransport(
        playbackLocator: String,
        insecureHttpPreapproved: Boolean,
    ): PlaybackAccessDecision =
        when (val target = parseTarget(playbackLocator)) {
            PlaybackTarget.Secure -> PlaybackAccessDecision.SecureTransport
            PlaybackTarget.Invalid -> PlaybackAccessDecision.InvalidLocator
            is PlaybackTarget.Insecure ->
                if (insecureHttpPreapproved) {
                    PlaybackAccessDecision.Approved
                } else {
                    PlaybackAccessDecision.ApprovalRequired(target.origin.displayValue())
                }
        }

    override suspend fun approve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult {
        val origin = (parseTarget(playbackLocator) as? PlaybackTarget.Insecure)?.origin
            ?: return PlaybackAccessMutationResult.InvalidLocator
        return mutate(credentialRef) { access ->
            access.withApprovedPlaybackOrigin(origin)
        }
    }

    override suspend fun revoke(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessMutationResult {
        val origin = (parseTarget(playbackLocator) as? PlaybackTarget.Insecure)?.origin
            ?: return PlaybackAccessMutationResult.InvalidLocator
        return mutate(credentialRef) { access ->
            access.withoutApprovedPlaybackOrigin(origin)
        }
    }

    override suspend fun revokeAll(credentialRef: String): PlaybackAccessMutationResult =
        mutate(credentialRef, RemoteSourceAccess::withoutPlaybackApprovals)

    private suspend fun mutate(
        credentialRef: String,
        transform: (RemoteSourceAccess) -> RemoteSourceAccess,
    ): PlaybackAccessMutationResult {
        val credentialId = parseCredentialId(credentialRef)
            ?: return PlaybackAccessMutationResult.NotFound
        val updated = try {
            accessManager.update(credentialId, transform)
        } catch (_: PlaybackApprovalCapacityExceededException) {
            return PlaybackAccessMutationResult.CapacityExceeded
        }
        return when (updated) {
            RemoteSourceAccessUpdateResult.Updated -> PlaybackAccessMutationResult.Applied
            RemoteSourceAccessUpdateResult.Unchanged -> PlaybackAccessMutationResult.Unchanged
            RemoteSourceAccessUpdateResult.NotFound -> PlaybackAccessMutationResult.NotFound
            RemoteSourceAccessUpdateResult.Corrupted -> PlaybackAccessMutationResult.Corrupted
            is RemoteSourceAccessUpdateResult.RejectedTooLarge ->
                PlaybackAccessMutationResult.CapacityExceeded

            is RemoteSourceAccessUpdateResult.Unavailable ->
                PlaybackAccessMutationResult.Unavailable
        }
    }

    private fun parseTarget(playbackLocator: String): PlaybackTarget {
        val parsed = playbackLocator.toHttpUrlOrNull() ?: return PlaybackTarget.Invalid
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return PlaybackTarget.Invalid
        }
        return when (parsed.scheme) {
            HTTPS_SCHEME -> PlaybackTarget.Secure
            HTTP_SCHEME -> ExactHttpOrigin.fromUrl(playbackLocator)
                ?.let(PlaybackTarget::Insecure)
                ?: PlaybackTarget.Invalid

            else -> PlaybackTarget.Invalid
        }
    }

    private fun parseCredentialId(raw: String): CredentialId? =
        runCatching { CredentialId.parse(raw) }.getOrNull()

    private sealed interface PlaybackTarget {
        data object Secure : PlaybackTarget
        data object Invalid : PlaybackTarget
        data class Insecure(val origin: ExactHttpOrigin) : PlaybackTarget
    }

    private companion object {
        const val HTTP_SCHEME = "http"
        const val HTTPS_SCHEME = "https"
    }
}
