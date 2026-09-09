package app.muxtv.catalog.refresh

import app.muxtv.catalog.ingest.M3uParseLimits
import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal sealed interface M3uCatchupTransportResolution {
    data object NotApplicable : M3uCatchupTransportResolution

    data class Ready(
        val locator: String,
        val timeline: ResolvedPlaybackTimeline,
        val initialMediaPositionMillis: Long,
    ) : M3uCatchupTransportResolution {
        init {
            require(initialMediaPositionMillis >= 0L)
        }

        override fun toString(): String =
            "M3uCatchupTransportResolution.Ready(locator=<redacted>, timeline=$timeline, " +
                "initialMediaPositionMillis=$initialMediaPositionMillis)"
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
                intent = intent,
                liveLocator = liveLocator,
                dialect = timelineResolution.dialect,
                timeline = timelineResolution.timeline,
            )
        }
    }

    private fun materialize(
        intent: PlaybackIntent,
        liveLocator: String,
        dialect: M3uCatchupDialect,
        timeline: ResolvedPlaybackTimeline,
    ): M3uCatchupTransportResolution {
        val liveUrl = liveLocator.toHttpUrlOrNull()
            ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        val granularityMillis = timeline.granularityMillis
            ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        if (granularityMillis <= 0L || granularityMillis != dialect.granularityMillis) {
            return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        }

        val correctedPositionMillis = runCatching {
            Math.subtractExact(
                timeline.initialPositionEpochMillis,
                timeline.correctionMillis,
            )
        }.getOrNull() ?: return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)

        val transportStartMillis = floorToGranularity(
            value = correctedPositionMillis,
            granularityMillis = granularityMillis,
        ) ?: return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)
        val initialMediaPositionMillis = runCatching {
            Math.subtractExact(correctedPositionMillis, transportStartMillis)
        }.getOrNull() ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        if (initialMediaPositionMillis < 0L || initialMediaPositionMillis >= granularityMillis) {
            return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        }

        if (
            transportStartMillis < timeline.windowStartEpochMillis ||
            transportStartMillis >= timeline.windowEndEpochMillis
        ) {
            return unavailable(M3uCatchupUnavailableReason.OUTSIDE_RETENTION)
        }

        val correctedProgrammeEndMillis = timeline.programmeEndEpochMillis?.let { programmeEnd ->
            runCatching {
                Math.subtractExact(programmeEnd, timeline.correctionMillis)
            }.getOrNull() ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        }
        val programmeDurationMillis = programmeDurationMillis(timeline)
            ?: if (intent is PlaybackIntent.CatchupProgram) {
                return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
            } else {
                null
            }

        val locator = when (dialect) {
            is M3uCatchupDialect.Append -> materializeAppend(
                liveUrl = liveUrl,
                template = dialect.template,
                transportStartMillis = transportStartMillis,
                correctedPositionMillis = correctedPositionMillis,
                correctedProgrammeEndMillis = correctedProgrammeEndMillis,
                programmeDurationMillis = programmeDurationMillis,
                nowMillis = timeline.windowEndEpochMillis,
            )

            M3uCatchupDialect.Shift -> materializeShift(
                liveUrl = liveUrl,
                transportStartMillis = transportStartMillis,
                nowMillis = timeline.windowEndEpochMillis,
            )

            M3uCatchupDialect.FlussonicHls -> materializeFlussonicHls(
                liveUrl = liveUrl,
                transportStartMillis = transportStartMillis,
            )

            M3uCatchupDialect.FlussonicTs -> materializeFlussonicTs(
                liveUrl = liveUrl,
                transportStartMillis = transportStartMillis,
                programmeDurationMillis = programmeDurationMillis,
            )

            M3uCatchupDialect.XtreamPhp -> materializeXtreamPhp(
                liveUrl = liveUrl,
                transportStartMillis = transportStartMillis,
                correctedProgrammeEndMillis = correctedProgrammeEndMillis,
            )
        } ?: return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)

        if (!locator.isBoundedLocator()) {
            return unavailable(M3uCatchupUnavailableReason.INVALID_METADATA)
        }

        return M3uCatchupTransportResolution.Ready(
            locator = locator,
            timeline = timeline,
            initialMediaPositionMillis = initialMediaPositionMillis,
        )
    }

    private fun materializeAppend(
        liveUrl: HttpUrl,
        template: M3uCatchupTemplate,
        transportStartMillis: Long,
        correctedPositionMillis: Long,
        correctedProgrammeEndMillis: Long?,
        programmeDurationMillis: Long?,
        nowMillis: Long,
    ): String? {
        val suffix = template.materialize(
            M3uCatchupTemplateContext(
                transportStartEpochMillis = transportStartMillis,
                requestedPositionEpochMillis = correctedPositionMillis,
                transportEndEpochMillis = correctedProgrammeEndMillis,
                nowEpochMillis = nowMillis,
                programmeDurationMillis = programmeDurationMillis,
            ),
        ) ?: return null

        val suffixQuery = suffix.drop(1)
        if (suffixQuery.isBlank()) return null
        val combinedQuery = when (val existing = liveUrl.encodedQuery) {
            null, "" -> suffixQuery
            else -> "$existing&$suffixQuery"
        }
        return liveUrl.newBuilder()
            .encodedQuery(combinedQuery)
            .build()
            .toString()
    }

    private fun materializeShift(
        liveUrl: HttpUrl,
        transportStartMillis: Long,
        nowMillis: Long,
    ): String {
        val utcSeconds = Math.floorDiv(transportStartMillis, SECOND_MILLIS)
        val nowSeconds = Math.floorDiv(nowMillis, SECOND_MILLIS)
        return liveUrl.newBuilder()
            .addQueryParameter("utc", utcSeconds.toString())
            .addQueryParameter("lutc", nowSeconds.toString())
            .build()
            .toString()
    }

    private fun materializeFlussonicHls(
        liveUrl: HttpUrl,
        transportStartMillis: Long,
    ): String {
        val utcSeconds = Math.floorDiv(transportStartMillis, SECOND_MILLIS)
        return liveUrl.withSiblingEncodedPath("timeshift_abs-$utcSeconds.m3u8").toString()
    }

    private fun materializeFlussonicTs(
        liveUrl: HttpUrl,
        transportStartMillis: Long,
        programmeDurationMillis: Long?,
    ): String? {
        val durationSeconds = programmeDurationMillis
            ?.takeIf { it > 0L }
            ?.let(::ceilSeconds)
            ?: return null
        val utcSeconds = Math.floorDiv(transportStartMillis, SECOND_MILLIS)
        return liveUrl.withSiblingEncodedPath(
            "archive-$utcSeconds-$durationSeconds.ts",
        ).toString()
    }

    private fun materializeXtreamPhp(
        liveUrl: HttpUrl,
        transportStartMillis: Long,
        correctedProgrammeEndMillis: Long?,
    ): String? {
        val segments = liveUrl.encodedPathSegments
        val credentialOffset = when {
            segments.size == XTREAM_LIVE_SEGMENT_COUNT && segments[0] == XTREAM_LIVE_PREFIX -> 1
            segments.size == XTREAM_ROOT_SEGMENT_COUNT -> 0
            else -> return null
        }

        val username = segments[credentialOffset].takeIf(String::isNotBlank) ?: return null
        val password = segments[credentialOffset + 1].takeIf(String::isNotBlank) ?: return null
        val streamMatch = XTREAM_STREAM_SEGMENT.matchEntire(segments[credentialOffset + 2]) ?: return null
        val streamId = streamMatch.groupValues[1]

        val endMillis = correctedProgrammeEndMillis ?: return null
        val coverageMillis = runCatching {
            Math.subtractExact(endMillis, transportStartMillis)
        }.getOrNull()?.takeIf { it > 0L } ?: return null
        val durationMinutes = ceilUnits(coverageMillis, MINUTE_MILLIS)
            ?.takeIf { it in 1..Int.MAX_VALUE.toLong() }
            ?: return null

        val start = XTREAM_START_FORMATTER.format(
            Instant.ofEpochMilli(transportStartMillis).atOffset(ZoneOffset.UTC),
        )
        return liveUrl.newBuilder()
            .encodedPath(XTREAM_TIMESHIFT_PATH)
            .query(null)
            .fragment(null)
            .addEncodedQueryParameter("username", username)
            .addEncodedQueryParameter("password", password)
            .addQueryParameter("stream", streamId)
            .addQueryParameter("start", start)
            .addQueryParameter("duration", durationMinutes.toString())
            .build()
            .toString()
    }

    private fun unavailable(reason: M3uCatchupUnavailableReason) =
        M3uCatchupTransportResolution.Unavailable(reason)

    private companion object {
        val XTREAM_START_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd:HH-mm", Locale.ROOT)
        val XTREAM_STREAM_SEGMENT = Regex("^([1-9][0-9]{0,18})\\.(?:ts|m3u8)$")

        const val XTREAM_ROOT_SEGMENT_COUNT = 3
        const val XTREAM_LIVE_SEGMENT_COUNT = 4
        const val XTREAM_LIVE_PREFIX = "live"
        const val XTREAM_TIMESHIFT_PATH = "/streaming/timeshift.php"
    }
}

private fun HttpUrl.withSiblingEncodedPath(fileName: String): HttpUrl {
    val path = encodedPath
    val lastSlash = path.lastIndexOf('/')
    val parent = if (lastSlash >= 0) path.substring(0, lastSlash + 1) else "/"
    return newBuilder()
        .encodedPath(parent + fileName)
        .build()
}

private fun floorToGranularity(
    value: Long,
    granularityMillis: Long,
): Long? = runCatching {
    Math.multiplyExact(Math.floorDiv(value, granularityMillis), granularityMillis)
}.getOrNull()

private fun programmeDurationMillis(timeline: ResolvedPlaybackTimeline): Long? {
    val start = timeline.programmeStartEpochMillis ?: return null
    val end = timeline.programmeEndEpochMillis ?: return null
    return runCatching {
        Math.subtractExact(end, start)
    }.getOrNull()?.takeIf { it > 0L }
}

private fun ceilSeconds(milliseconds: Long): Long? =
    ceilUnits(milliseconds, SECOND_MILLIS)

private fun ceilUnits(value: Long, unit: Long): Long? {
    if (value <= 0L || unit <= 0L) return null
    val whole = Math.floorDiv(value, unit)
    return if (value % unit == 0L) {
        whole
    } else {
        runCatching { Math.addExact(whole, 1L) }.getOrNull()
    }
}

private val TRANSPORT_PARSE_LIMITS = M3uParseLimits()

private fun String.isBoundedLocator(): Boolean {
    val bytes = toByteArray(StandardCharsets.UTF_8)
    return bytes.size <= TRANSPORT_PARSE_LIMITS.maxLineBytes
}
