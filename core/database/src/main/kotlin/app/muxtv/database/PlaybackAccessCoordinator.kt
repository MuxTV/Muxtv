package app.muxtv.database

import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver

internal sealed interface CoordinatedPlaybackAccess {
    data class Ready(
        val locator: String,
        val insecureHttpApproved: Boolean,
    ) : CoordinatedPlaybackAccess {
        override fun toString(): String =
            "Ready(locator=<redacted>, insecureHttpApproved=$insecureHttpApproved)"
    }

    data class ApprovalRequired(
        val displayOrigin: String,
    ) : CoordinatedPlaybackAccess {
        override fun toString(): String = "ApprovalRequired(displayOrigin=<redacted>)"
    }

    data class Unavailable(
        val reason: PlaybackAccessUnavailableReason,
    ) : CoordinatedPlaybackAccess
}

internal class PlaybackAccessCoordinator(
    private val referenceResolver: PlaybackReferenceResolver,
    private val accessPolicyResolver: PlaybackAccessPolicyResolver,
) {
    suspend fun resolve(
        credentialRef: String,
        playbackReference: String,
    ): CoordinatedPlaybackAccess {
        return when (
            val reference = referenceResolver.resolve(
                PlaybackReferenceRequest(
                    credentialRef = credentialRef,
                    playbackReference = playbackReference,
                ),
            )
        ) {
            PlaybackReferenceResolution.Unhandled -> resolveDirect(credentialRef, playbackReference)
            PlaybackReferenceResolution.InvalidReference -> unavailable(PlaybackAccessUnavailableReason.InvalidLocator)
            PlaybackReferenceResolution.CredentialNotFound -> unavailable(PlaybackAccessUnavailableReason.CredentialNotFound)
            PlaybackReferenceResolution.CredentialCorrupted -> unavailable(PlaybackAccessUnavailableReason.CredentialCorrupted)
            PlaybackReferenceResolution.CredentialUnavailable -> unavailable(PlaybackAccessUnavailableReason.CredentialUnavailable)
            is PlaybackReferenceResolution.ApprovalRequired ->
                CoordinatedPlaybackAccess.ApprovalRequired(reference.displayOrigin)

            is PlaybackReferenceResolution.Ready -> resolveEphemeral(reference)
        }
    }

    private suspend fun resolveDirect(
        credentialRef: String,
        locator: String,
    ): CoordinatedPlaybackAccess = mapAccessDecision(
        locator = locator,
        decision = accessPolicyResolver.resolve(credentialRef, locator),
    )

    private suspend fun resolveEphemeral(
        reference: PlaybackReferenceResolution.Ready,
    ): CoordinatedPlaybackAccess {
        val decision = if (reference.insecureHttpPreapproved) {
            accessPolicyResolver.resolvePreapproved(reference.locator)
        } else {
            // Xtream credential ids are not M3U access records. HTTPS needs no credential-bound
            // approval lookup, so use an empty legacy credential reference while retaining the
            // access-policy resolver as the final transport validation owner.
            accessPolicyResolver.resolve("", reference.locator)
        }
        return mapAccessDecision(reference.locator, decision)
    }

    private fun mapAccessDecision(
        locator: String,
        decision: PlaybackAccessDecision,
    ): CoordinatedPlaybackAccess = when (decision) {
        PlaybackAccessDecision.SecureTransport ->
            CoordinatedPlaybackAccess.Ready(locator, insecureHttpApproved = false)
        PlaybackAccessDecision.Approved ->
            CoordinatedPlaybackAccess.Ready(locator, insecureHttpApproved = true)
        is PlaybackAccessDecision.ApprovalRequired ->
            CoordinatedPlaybackAccess.ApprovalRequired(decision.displayOrigin)
        PlaybackAccessDecision.InvalidLocator -> unavailable(PlaybackAccessUnavailableReason.InvalidLocator)
        PlaybackAccessDecision.CredentialNotFound -> unavailable(PlaybackAccessUnavailableReason.CredentialNotFound)
        PlaybackAccessDecision.CredentialCorrupted -> unavailable(PlaybackAccessUnavailableReason.CredentialCorrupted)
        PlaybackAccessDecision.CredentialUnavailable -> unavailable(PlaybackAccessUnavailableReason.CredentialUnavailable)
    }

    private fun unavailable(reason: PlaybackAccessUnavailableReason): CoordinatedPlaybackAccess =
        CoordinatedPlaybackAccess.Unavailable(reason)
}
