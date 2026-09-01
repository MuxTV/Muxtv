package app.muxtv.database

import app.muxtv.catalog.PlaybackAccessDecision
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.PlaybackAccessUnavailableReason
import app.muxtv.catalog.PlaybackCatchupMetadata
import app.muxtv.catalog.PlaybackCatchupUnavailableReason
import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline

internal sealed interface CoordinatedPlaybackAccess {
    data class Ready(
        val locator: String,
        val insecureHttpApproved: Boolean,
        val timeline: ResolvedPlaybackTimeline? = null,
    ) : CoordinatedPlaybackAccess {
        override fun toString(): String =
            "Ready(locator=<redacted>, insecureHttpApproved=$insecureHttpApproved, " +
                "timelinePresent=${timeline != null})"
    }

    data class ApprovalRequired(
        val displayOrigin: String,
    ) : CoordinatedPlaybackAccess {
        override fun toString(): String = "ApprovalRequired(displayOrigin=<redacted>)"
    }

    data class CatchupUnavailable(
        val reason: PlaybackCatchupUnavailableReason,
    ) : CoordinatedPlaybackAccess

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
        intent: PlaybackIntent? = null,
        catchupMetadata: PlaybackCatchupMetadata? = null,
    ): CoordinatedPlaybackAccess {
        return when (
            val reference = referenceResolver.resolve(
                PlaybackReferenceRequest(
                    credentialRef = credentialRef,
                    playbackReference = playbackReference,
                    intent = intent,
                    catchupMetadata = catchupMetadata,
                ),
            )
        ) {
            PlaybackReferenceResolution.Unhandled -> {
                if (intent != null && intent !is PlaybackIntent.Live) {
                    catchupUnavailable(PlaybackCatchupUnavailableReason.UNSUPPORTED)
                } else {
                    resolveDirect(credentialRef, playbackReference)
                }
            }
            PlaybackReferenceResolution.InvalidReference -> unavailable(PlaybackAccessUnavailableReason.InvalidLocator)
            PlaybackReferenceResolution.CredentialNotFound -> unavailable(PlaybackAccessUnavailableReason.CredentialNotFound)
            PlaybackReferenceResolution.CredentialCorrupted -> unavailable(PlaybackAccessUnavailableReason.CredentialCorrupted)
            PlaybackReferenceResolution.CredentialUnavailable -> unavailable(PlaybackAccessUnavailableReason.CredentialUnavailable)
            is PlaybackReferenceResolution.CatchupUnavailable ->
                catchupUnavailable(reference.reason)
            is PlaybackReferenceResolution.ApprovalRequired ->
                CoordinatedPlaybackAccess.ApprovalRequired(reference.displayOrigin)
            is PlaybackReferenceResolution.Ready -> {
                if (intent != null && intent !is PlaybackIntent.Live) {
                    catchupUnavailable(PlaybackCatchupUnavailableReason.UNSUPPORTED)
                } else {
                    resolveEphemeral(reference)
                }
            }
            is PlaybackReferenceResolution.MaterializedDirect ->
                resolveMaterializedDirect(credentialRef, reference)
        }
    }

    private suspend fun resolveDirect(
        credentialRef: String,
        locator: String,
    ): CoordinatedPlaybackAccess = mapAccessDecision(
        locator = locator,
        decision = accessPolicyResolver.resolve(credentialRef, locator),
    )

    private suspend fun resolveMaterializedDirect(
        credentialRef: String,
        reference: PlaybackReferenceResolution.MaterializedDirect,
    ): CoordinatedPlaybackAccess = mapAccessDecision(
        locator = reference.locator,
        decision = accessPolicyResolver.resolve(credentialRef, reference.locator),
        timeline = reference.timeline,
    )

    private suspend fun resolveEphemeral(
        reference: PlaybackReferenceResolution.Ready,
    ): CoordinatedPlaybackAccess = mapAccessDecision(
        locator = reference.locator,
        decision = accessPolicyResolver.validateMaterializedTransport(
            playbackLocator = reference.locator,
            insecureHttpPreapproved = reference.insecureHttpPreapproved,
        ),
    )

    private fun mapAccessDecision(
        locator: String,
        decision: PlaybackAccessDecision,
        timeline: ResolvedPlaybackTimeline? = null,
    ): CoordinatedPlaybackAccess = when (decision) {
        PlaybackAccessDecision.SecureTransport ->
            CoordinatedPlaybackAccess.Ready(
                locator = locator,
                insecureHttpApproved = false,
                timeline = timeline,
            )
        PlaybackAccessDecision.Approved ->
            CoordinatedPlaybackAccess.Ready(
                locator = locator,
                insecureHttpApproved = true,
                timeline = timeline,
            )
        is PlaybackAccessDecision.ApprovalRequired ->
            CoordinatedPlaybackAccess.ApprovalRequired(decision.displayOrigin)
        PlaybackAccessDecision.InvalidLocator -> unavailable(PlaybackAccessUnavailableReason.InvalidLocator)
        PlaybackAccessDecision.CredentialNotFound -> unavailable(PlaybackAccessUnavailableReason.CredentialNotFound)
        PlaybackAccessDecision.CredentialCorrupted -> unavailable(PlaybackAccessUnavailableReason.CredentialCorrupted)
        PlaybackAccessDecision.CredentialUnavailable -> unavailable(PlaybackAccessUnavailableReason.CredentialUnavailable)
    }

    private fun catchupUnavailable(
        reason: PlaybackCatchupUnavailableReason,
    ): CoordinatedPlaybackAccess = CoordinatedPlaybackAccess.CatchupUnavailable(reason)

    private fun unavailable(reason: PlaybackAccessUnavailableReason): CoordinatedPlaybackAccess =
        CoordinatedPlaybackAccess.Unavailable(reason)
}
