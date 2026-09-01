package app.muxtv.catalog.refresh

import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline

internal sealed interface M3uCatchupTransportResolution {
    data object NotApplicable : M3uCatchupTransportResolution

    data class Ready(
        val locator: String,
        val timeline: ResolvedPlaybackTimeline,
    ) : M3uCatchupTransportResolution {
        override fun toString(): String =
            "M3uCatchupTransportResolution.Ready(locator=<redacted>, timeline=$timeline)"
    }

    data class Unavailable(
        val reason: M3uCatchupUnavailableReason,
    ) : M3uCatchupTransportResolution
}

internal class M3uCatchupTransportResolver(
    nowEpochMillis: () -> Long,
) {
    private val timelineResolver = M3uCatchupResolver(nowEpochMillis)

    fun resolve(
        intent: PlaybackIntent,
        liveLocator: String,
        metadata: M3uCatchupMetadata,
    ): M3uCatchupTransportResolution {
        return when (val timelineResolution = timelineResolver.resolve(intent, metadata)) {
            M3uCatchupResolution.NotApplicable -> M3uCatchupTransportResolution.NotApplicable
            is M3uCatchupResolution.Unavailable -> unavailable(timelineResolution.reason)
            is M3uCatchupResolution.Ready -> materialize(
                liveLocator = liveLocator,
                sourceTemplate = metadata.source,
                timeline = timelineResolution.timeline,
            )
        }
    }

    private fun materialize(
        liveLocator: String,
        sourceTemplate: String?,
        timeline: ResolvedPlaybackTimeline,
    ): M3uCatchupTransportResolution {
        if (liveLocator.isBlank() || sourceTemplate == null) {
            return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        }
        val granularityMillis = timeline.granularityMillis
            ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)

        val correctedPositionMillis = runCatching {
            Math.subtractExact(
                timeline.initialPositionEpochMillis,
                timeline.correctionMillis,
            )
        }.getOrNull() ?: return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)

        val utcSeconds = Math.floorDiv(correctedPositionMillis, granularityMillis)
        val materializedPositionMillis = runCatching {
            Math.multiplyExact(utcSeconds, granularityMillis)
        }.getOrNull() ?: return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)

        if (
            materializedPositionMillis < timeline.windowStartEpochMillis ||
            materializedPositionMillis >= timeline.windowEndEpochMillis
        ) {
            return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)
        }

        val suffix = sourceTemplate.replace(UTC_TOKEN, utcSeconds.toString())
        return M3uCatchupTransportResolution.Ready(
            locator = liveLocator + suffix,
            timeline = timeline,
        )
    }

    private fun unavailable(reason: M3uCatchupUnavailableReason) =
        M3uCatchupTransportResolution.Unavailable(reason)
}

private const val UTC_TOKEN = "{utc}"
