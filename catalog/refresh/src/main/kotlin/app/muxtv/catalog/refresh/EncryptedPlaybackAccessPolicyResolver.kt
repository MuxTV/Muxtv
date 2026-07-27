package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.network.ExactHttpOrigin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class EncryptedPlaybackAccessPolicyResolver(
    private val credentialStore: CredentialStore,
) : PlaybackAccessPolicyResolver {
    private val mutationMutex = Mutex()

    override suspend fun resolve(
        credentialRef: String,
        playbackLocator: String,
    ): PlaybackAccessDecision = when (val target = parseTarget(playbackLocator)) {
        PlaybackTarget.Secure -> PlaybackAccessDecision.SecureTransport
        PlaybackTarget.Invalid -> PlaybackAccessDecision.InvalidLocator
        is PlaybackTarget.Insecure -> {
            val credentialId = parseCredentialId(credentialRef)
                ?: return PlaybackAccessDecision.CredentialNotFound
            when (val read = readAccess(credentialId)) {
                is AccessRead.Found -> if (target.origin in read.access.approvedPlaybackOrigins) {
                    PlaybackAccessDecision.Approved
                } else {
                    PlaybackAccessDecision.ApprovalRequired(target.origin.displayValue())
                }

                AccessRead.NotFound -> PlaybackAccessDecision.CredentialNotFound
                AccessRead.Corrupted -> PlaybackAccessDecision.CredentialCorrupted
                AccessRead.Unavailable -> PlaybackAccessDecision.CredentialUnavailable
            }
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
    ): PlaybackAccessMutationResult = mutationMutex.withLock {
        val credentialId = parseCredentialId(credentialRef)
            ?: return@withLock PlaybackAccessMutationResult.NotFound
        when (val read = readAccess(credentialId)) {
            is AccessRead.Found -> {
                val updated = try {
                    transform(read.access)
                } catch (_: PlaybackApprovalCapacityExceededException) {
                    return@withLock PlaybackAccessMutationResult.CapacityExceeded
                }
                if (updated === read.access) {
                    return@withLock PlaybackAccessMutationResult.Unchanged
                }
                RemoteSourceAccessCodec.encode(updated).use { encoded ->
                    when (credentialStore.put(credentialId, encoded)) {
                        CredentialWriteResult.Stored -> PlaybackAccessMutationResult.Applied
                        is CredentialWriteResult.RejectedTooLarge ->
                            PlaybackAccessMutationResult.CapacityExceeded

                        is CredentialWriteResult.Unavailable ->
                            PlaybackAccessMutationResult.Unavailable
                    }
                }
            }

            AccessRead.NotFound -> PlaybackAccessMutationResult.NotFound
            AccessRead.Corrupted -> PlaybackAccessMutationResult.Corrupted
            AccessRead.Unavailable -> PlaybackAccessMutationResult.Unavailable
        }
    }

    private suspend fun readAccess(credentialId: CredentialId): AccessRead =
        when (val credential = credentialStore.read(credentialId)) {
            is CredentialReadResult.Found -> credential.secret.use { secret ->
                try {
                    AccessRead.Found(RemoteSourceAccessCodec.decode(secret))
                } catch (_: RemoteSourceAccessFormatException) {
                    AccessRead.Corrupted
                }
            }

            CredentialReadResult.NotFound -> AccessRead.NotFound
            is CredentialReadResult.Unavailable -> AccessRead.Unavailable
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

    private sealed interface AccessRead {
        data class Found(val access: RemoteSourceAccess) : AccessRead
        data object NotFound : AccessRead
        data object Corrupted : AccessRead
        data object Unavailable : AccessRead
    }

    private companion object {
        const val HTTP_SCHEME = "http"
        const val HTTPS_SCHEME = "https"
    }
}
