package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.catalog.PlaybackArchiveResolver
import app.muxtv.catalog.PlaybackArchiveUnavailableReason
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline

/**
 * Resolves persisted Xtream archive availability into a provider-neutral timeline plus an opaque
 * provider reference. Credential-bearing transport materialization remains owned by
 * [XtreamPlaybackReferenceResolver].
 */
class XtreamPlaybackArchiveResolver(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : PlaybackArchiveResolver {
    override fun resolve(request: PlaybackArchiveRequest): PlaybackArchiveResolution {
        if (request.intent is PlaybackIntent.Live) {
            return PlaybackArchiveResolution.NotApplicable
        }

        val liveReference = parseLiveReference(request.livePlaybackReference)
            ?: return if (request.livePlaybackReference.startsWith(XTREAM_PROVIDER_PREFIX)) {
                PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
            } else {
                PlaybackArchiveResolution.NotApplicable
            }

        if (!request.metadata.mode.equals(XTREAM_ARCHIVE_MODE, ignoreCase = true)) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.UnsupportedMode)
        }

        val retentionDays = request.metadata.days
        if (retentionDays == null || retentionDays <= 0) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }

        return when (val intent = request.intent) {
            is PlaybackIntent.Live -> PlaybackArchiveResolution.NotApplicable
            is PlaybackIntent.CatchupPosition ->
                PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.UnsupportedMode)
            is PlaybackIntent.CatchupProgram -> resolveProgramme(
                intent = intent,
                liveReference = liveReference,
                retentionDays = retentionDays,
            )
        }
    }

    private fun resolveProgramme(
        intent: PlaybackIntent.CatchupProgram,
        liveReference: XtreamLiveReference,
        retentionDays: Int,
    ): PlaybackArchiveResolution {
        if (
            intent.startEpochMillis <= 0L ||
            intent.endEpochMillis <= intent.startEpochMillis
        ) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }

        val now = nowEpochMillis()
        if (now <= 0L) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }

        val retentionMillis = try {
            Math.multiplyExact(retentionDays.toLong(), DAY_MILLIS)
        } catch (_: ArithmeticException) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }
        val windowStart = try {
            Math.subtractExact(now, retentionMillis)
        } catch (_: ArithmeticException) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }

        if (intent.startEpochMillis < windowStart || intent.endEpochMillis > now) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.OutsideRetention)
        }

        val transportStart = floorToMinute(intent.startEpochMillis)
        if (transportStart < windowStart) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.OutsideRetention)
        }

        val durationMillis = intent.endEpochMillis - transportStart
        if (durationMillis <= 0L) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }
        val durationMinutes = ((durationMillis - 1L) / MINUTE_MILLIS) + 1L
        if (durationMinutes <= 0L || durationMinutes > Int.MAX_VALUE.toLong()) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }

        val locator = buildString {
            append(XTREAM_ARCHIVE_PREFIX)
            append(liveReference.streamId)
            append('/')
            append(durationMinutes)
            append('/')
            append(transportStart)
            append('/')
            append(liveReference.format)
        }
        if (locator.length > MAX_OPAQUE_REFERENCE_CHARS) {
            return PlaybackArchiveResolution.Unavailable(PlaybackArchiveUnavailableReason.InvalidMetadata)
        }

        return PlaybackArchiveResolution.Ready(
            locator = locator,
            timeline = ResolvedPlaybackTimeline(
                windowStartEpochMillis = windowStart,
                windowEndEpochMillis = now,
                programmeStartEpochMillis = intent.startEpochMillis,
                programmeEndEpochMillis = intent.endEpochMillis,
                initialPositionEpochMillis = intent.startEpochMillis,
                correctionMillis = 0L,
                granularityMillis = MINUTE_MILLIS,
                playAsLive = false,
            ),
        )
    }

    private fun parseLiveReference(reference: String): XtreamLiveReference? {
        if (reference.length > MAX_OPAQUE_REFERENCE_CHARS) return null
        val match = XTREAM_LIVE_REFERENCE.matchEntire(reference) ?: return null
        val streamId = match.groupValues[1].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val format = match.groupValues[2].ifBlank { DEFAULT_OUTPUT_FORMAT }
        return XtreamLiveReference(streamId = streamId, format = format)
    }

    private fun floorToMinute(epochMillis: Long): Long =
        epochMillis - (epochMillis % MINUTE_MILLIS)

    private data class XtreamLiveReference(
        val streamId: Long,
        val format: String,
    )

    private companion object {
        const val XTREAM_PROVIDER_PREFIX = "muxtv-provider://xtream/"
        const val XTREAM_ARCHIVE_PREFIX = "muxtv-provider://xtream/archive/"
        const val XTREAM_ARCHIVE_MODE = "xtream"
        const val DEFAULT_OUTPUT_FORMAT = "ts"
        const val MAX_OPAQUE_REFERENCE_CHARS = 256
        const val MINUTE_MILLIS = 60_000L
        const val DAY_MILLIS = 24L * 60L * MINUTE_MILLIS

        val XTREAM_LIVE_REFERENCE =
            Regex("^muxtv-provider://xtream/live/([1-9][0-9]{0,18})(?:/(ts|m3u8))?$")
    }
}
