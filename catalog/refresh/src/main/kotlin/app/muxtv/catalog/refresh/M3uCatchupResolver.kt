package app.muxtv.catalog.refresh

import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline
import java.math.BigDecimal

internal data class M3uCatchupMetadata(
    val mode: String?,
    val source: String?,
    val days: Int?,
    val correction: String?,
) {
    override fun toString(): String =
        "M3uCatchupMetadata(mode=$mode, source=<redacted>, days=$days, correction=$correction)"
}

internal enum class M3uCatchupUnavailableReason {
    OUTSIDE_RETENTION,
    UNSUPPORTED_MODE,
    INVALID_METADATA,
}

internal sealed interface M3uCatchupResolution {
    data object NotApplicable : M3uCatchupResolution

    data class Ready(
        val timeline: ResolvedPlaybackTimeline,
    ) : M3uCatchupResolution

    data class Unavailable(
        val reason: M3uCatchupUnavailableReason,
    ) : M3uCatchupResolution
}

internal class M3uCatchupResolver(
    private val nowEpochMillis: () -> Long,
) {
    fun resolve(
        intent: PlaybackIntent,
        metadata: M3uCatchupMetadata,
    ): M3uCatchupResolution {
        if (intent is PlaybackIntent.Live) {
            return M3uCatchupResolution.NotApplicable
        }
        if (metadata.mode != MODE_APPEND) {
            return unavailable(M3uCatchupUnavailableReason.UNSUPPORTED_MODE)
        }
        if (metadata.source?.contains(UTC_TOKEN) != true) {
            return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        }

        val days = metadata.days?.takeIf { it > 0 }
            ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        val correctionMillis = metadata.correction.toCorrectionMillisOrNull()
            ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        val now = nowEpochMillis()
        val retentionMillis = runCatching {
            Math.multiplyExact(days.toLong(), DAY_MILLIS)
        }.getOrNull() ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        val windowStart = runCatching {
            Math.subtractExact(now, retentionMillis)
        }.getOrNull() ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)

        return when (intent) {
            is PlaybackIntent.Live -> M3uCatchupResolution.NotApplicable
            is PlaybackIntent.CatchupProgram -> resolveProgramme(
                intent = intent,
                windowStart = windowStart,
                now = now,
                correctionMillis = correctionMillis,
            )
            is PlaybackIntent.CatchupPosition -> resolvePosition(
                intent = intent,
                windowStart = windowStart,
                now = now,
                correctionMillis = correctionMillis,
            )
        }
    }

    private fun resolveProgramme(
        intent: PlaybackIntent.CatchupProgram,
        windowStart: Long,
        now: Long,
        correctionMillis: Long,
    ): M3uCatchupResolution {
        if (intent.startEpochMillis < windowStart || intent.endEpochMillis > now) {
            return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)
        }
        return M3uCatchupResolution.Ready(
            timeline = ResolvedPlaybackTimeline(
                windowStartEpochMillis = windowStart,
                windowEndEpochMillis = now,
                programmeStartEpochMillis = intent.startEpochMillis,
                programmeEndEpochMillis = intent.endEpochMillis,
                initialPositionEpochMillis = intent.startEpochMillis,
                correctionMillis = correctionMillis,
                granularityMillis = SECOND_MILLIS,
                playAsLive = false,
            ),
        )
    }

    private fun resolvePosition(
        intent: PlaybackIntent.CatchupPosition,
        windowStart: Long,
        now: Long,
        correctionMillis: Long,
    ): M3uCatchupResolution {
        if (intent.positionEpochMillis < windowStart || intent.positionEpochMillis >= now) {
            return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)
        }
        return M3uCatchupResolution.Ready(
            timeline = ResolvedPlaybackTimeline(
                windowStartEpochMillis = windowStart,
                windowEndEpochMillis = now,
                programmeStartEpochMillis = null,
                programmeEndEpochMillis = null,
                initialPositionEpochMillis = intent.positionEpochMillis,
                correctionMillis = correctionMillis,
                granularityMillis = SECOND_MILLIS,
                playAsLive = false,
            ),
        )
    }

    private fun unavailable(reason: M3uCatchupUnavailableReason) =
        M3uCatchupResolution.Unavailable(reason)
}

private fun String?.toCorrectionMillisOrNull(): Long? {
    if (this.isNullOrBlank()) return 0L
    return runCatching {
        BigDecimal(trim())
            .multiply(BigDecimal.valueOf(HOUR_MILLIS))
            .longValueExact()
    }.getOrNull()
}

private const val MODE_APPEND = "append"
private const val UTC_TOKEN = "{utc}"
private const val SECOND_MILLIS = 1_000L
private const val HOUR_MILLIS = 60 * 60 * SECOND_MILLIS
private const val DAY_MILLIS = 24 * HOUR_MILLIS
