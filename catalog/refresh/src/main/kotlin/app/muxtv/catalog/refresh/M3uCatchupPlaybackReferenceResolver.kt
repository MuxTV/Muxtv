package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackCatchupMetadata
import app.muxtv.catalog.PlaybackCatchupUnavailableReason
import app.muxtv.catalog.PlaybackReferenceRequest
import app.muxtv.catalog.PlaybackReferenceResolution
import app.muxtv.catalog.PlaybackReferenceResolver
import app.muxtv.player.PlaybackIntent

class M3uCatchupPlaybackReferenceResolver(
    private val fallback: PlaybackReferenceResolver,
    nowEpochMillis: () -> Long = System::currentTimeMillis,
) : PlaybackReferenceResolver {
    private val transportResolver = M3uCatchupTransportResolver(nowEpochMillis)

    override suspend fun resolve(request: PlaybackReferenceRequest): PlaybackReferenceResolution {
        val intent = request.intent
        if (intent == null || intent is PlaybackIntent.Live) {
            return fallback.resolve(request)
        }

        val metadata = request.catchupMetadata ?: return fallback.resolve(request)
        return when (
            val resolution = transportResolver.resolve(
                intent = intent,
                liveLocator = request.playbackReference,
                metadata = metadata.toM3uMetadata(),
            )
        ) {
            M3uCatchupTransportResolution.NotApplicable ->
                PlaybackReferenceResolution.CatchupUnavailable(
                    PlaybackCatchupUnavailableReason.UNSUPPORTED,
                )

            is M3uCatchupTransportResolution.Ready ->
                PlaybackReferenceResolution.MaterializedDirect(
                    locator = resolution.locator,
                    timeline = resolution.timeline,
                )

            is M3uCatchupTransportResolution.Unavailable ->
                PlaybackReferenceResolution.CatchupUnavailable(
                    resolution.reason.toPublicReason(),
                )
        }
    }
}

private fun PlaybackCatchupMetadata.toM3uMetadata(): M3uCatchupMetadata =
    M3uCatchupMetadata(
        mode = mode,
        source = sourceTemplate,
        days = retentionDays,
        correction = correction,
    )

private fun M3uCatchupUnavailableReason.toPublicReason(): PlaybackCatchupUnavailableReason = when (this) {
    M3uCatchupUnavailableReason.OUTSIDE_RETENTION -> PlaybackCatchupUnavailableReason.OUTSIDE_RETENTION
    M3uCatchupUnavailableReason.UNSUPPORTED_MODE -> PlaybackCatchupUnavailableReason.UNSUPPORTED
    M3uCatchupUnavailableReason.INVALID_METADATA -> PlaybackCatchupUnavailableReason.INVALID_METADATA
}
