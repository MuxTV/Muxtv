package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.player.PlaybackStartResult

fun interface PlaybackLocalNetworkAccessGate {
    fun accessRequired(target: String): Boolean
}

internal object PlaybackLocalNetworkAccessDecision {
    fun requiredResult(
        candidate: PlaybackCandidateIdentity,
        resolution: PlaybackVariantResolution?,
        gate: PlaybackLocalNetworkAccessGate,
    ): PlaybackStartResult.LocalNetworkPermissionRequired? {
        val target = when (resolution) {
            is PlaybackVariantResolution.Ready -> {
                val request = resolution.request
                if (request.channelId != candidate.channelId ||
                    request.variantId != candidate.variantId
                ) return null
                request.locator
            }

            is PlaybackVariantResolution.InsecureTransportApprovalRequired -> {
                if (resolution.channelId != candidate.channelId ||
                    resolution.variantId != candidate.variantId
                ) return null
                resolution.displayOrigin
            }

            is PlaybackVariantResolution.AccessUnavailable,
            null,
            -> return null
        }

        if (!gate.accessRequired(target)) return null
        return PlaybackStartResult.LocalNetworkPermissionRequired(candidate.variantId)
    }
}
