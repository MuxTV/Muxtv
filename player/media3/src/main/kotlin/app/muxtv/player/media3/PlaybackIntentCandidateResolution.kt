package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.player.PlaybackStartRequest

internal suspend fun resolvePlaybackCandidateForRecovery(
    resolver: PlaybackCandidateResolver,
    request: PlaybackStartRequest,
    candidate: PlaybackCandidateIdentity,
): PlaybackVariantResolution? = resolver.resolveIntentCandidate(
    profileId = request.profileId,
    intent = request.intent,
    candidate = candidate,
)
