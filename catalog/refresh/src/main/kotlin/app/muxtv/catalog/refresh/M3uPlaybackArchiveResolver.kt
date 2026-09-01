package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackArchiveMetadata
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.catalog.PlaybackArchiveResolver
import app.muxtv.catalog.PlaybackArchiveUnavailableReason

class M3uPlaybackArchiveResolver(
    nowEpochMillis: () -> Long = System::currentTimeMillis,
) : PlaybackArchiveResolver {
    private val delegate = M3uCatchupTransportResolver(nowEpochMillis)

    override fun resolve(request: PlaybackArchiveRequest): PlaybackArchiveResolution =
        when (
            val result = delegate.resolve(
                intent = request.intent,
                liveLocator = request.livePlaybackReference,
                metadata = request.metadata.toM3uMetadata(),
            )
        ) {
            M3uCatchupTransportResolution.NotApplicable ->
                PlaybackArchiveResolution.NotApplicable

            is M3uCatchupTransportResolution.Ready ->
                PlaybackArchiveResolution.Ready(
                    locator = result.locator,
                    timeline = result.timeline,
                )

            is M3uCatchupTransportResolution.Unavailable ->
                PlaybackArchiveResolution.Unavailable(result.reason.toArchiveReason())
        }
}

private fun PlaybackArchiveMetadata.toM3uMetadata(): M3uCatchupMetadata = M3uCatchupMetadata(
    mode = mode,
    source = source,
    days = days,
    correction = correction,
)

private fun M3uCatchupUnavailableReason.toArchiveReason(): PlaybackArchiveUnavailableReason =
    when (this) {
        M3uCatchupUnavailableReason.OUTSIDE_RETENTION ->
            PlaybackArchiveUnavailableReason.OutsideRetention
        M3uCatchupUnavailableReason.UNSUPPORTED_MODE ->
            PlaybackArchiveUnavailableReason.UnsupportedMode
        M3uCatchupUnavailableReason.INVALID_METADATA ->
            PlaybackArchiveUnavailableReason.InvalidMetadata
    }
