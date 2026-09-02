package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class GuideCellProjection(
    val programmeKey: GuideProgrammeKey?,
    val state: GuideProjectionState,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val originalStartEpochMillis: Long?,
    val originalEndEpochMillis: Long?,
    val title: String,
    val timeLabel: String?,
    val detailLabel: String,
) {
    init {
        require(title.isNotBlank())
        require(startEpochMillis >= 0L)
        require(endEpochMillis > startEpochMillis)
        require((originalStartEpochMillis == null) == (originalEndEpochMillis == null))
        require((programmeKey == null) == (originalStartEpochMillis == null))
        if (originalStartEpochMillis != null && originalEndEpochMillis != null) {
            require(originalStartEpochMillis >= 0L)
            require(originalEndEpochMillis > originalStartEpochMillis)
            require(startEpochMillis >= originalStartEpochMillis)
            require(endEpochMillis <= originalEndEpochMillis)
        }
        require(detailLabel.isNotBlank())
    }

    fun composeKey(): Any = programmeKey ?: "status:${state.name}"

    override fun toString(): String =
        "GuideCellProjection(state=$state, programmePresent=${programmeKey != null}, " +
            "startEpochMillis=$startEpochMillis, endEpochMillis=$endEpochMillis, " +
            "originalBoundsPresent=${originalStartEpochMillis != null})"
}

internal data class GuideRowProjection(
    val channel: PlayableChannelSummary,
    val state: GuideProjectionState,
    val primaryLabel: String,
    val groupLabel: String?,
    val cells: List<GuideCellProjection>,
    val focusChannel: GuideFocusChannel,
) {
    init {
        require(primaryLabel.isNotBlank())
        require(cells.isNotEmpty())
        require(state == GuideProjectionState.READY || cells.size == 1)
        require(focusChannel.channelId == channel.channelId)
    }

    override fun toString(): String =
        "GuideRowProjection(state=$state, cellCount=${cells.size}, " +
            "favorite=${channel.isFavorite}, channelNumberPresent=${channel.channelNumber != null})"
}

internal data class GuideTickProjection(
    val epochMillis: Long,
    val label: String,
) {
    init {
        require(epochMillis >= 0L)
        require(label.isNotBlank())
    }
}

internal data class GuideWindowPresentation(
    val spanMillis: Long,
    val isNarrowed: Boolean,
    val label: String,
    val ticks: List<GuideTickProjection>,
) {
    init {
        require(spanMillis > 0L)
        require(label.isNotBlank())
    }
}

internal fun GuideViewport.toPresentation(zoneId: ZoneId): GuideWindowPresentation {
    val spanMillis = toEpochMillis - fromEpochMillis
    val isNarrowed = spanMillis < GuideViewportPolicy.DEFAULT_TIME_SPAN_MILLIS
    val label = if (isNarrowed) {
        "Безопасное окно: ${formatGuideDuration(spanMillis)}"
    } else {
        "Окно: ${formatGuideDuration(spanMillis)}"
    }
    val ticks = timelineTicks(fromEpochMillis, toEpochMillis, zoneId).map { epochMillis ->
        GuideTickProjection(
            epochMillis = epochMillis,
            label = formatGuideTime(epochMillis, zoneId),
        )
    }
    return GuideWindowPresentation(
        spanMillis = spanMillis,
        isNarrowed = isNarrowed,
        label = label,
        ticks = ticks,
    )
}

internal fun projectGuideRow(
    channel: PlayableChannelSummary,
    state: GuideProjectionState,
    programmes: List<GuideProgrammeCell>,
    viewportFromEpochMillis: Long,
    viewportToEpochMillis: Long,
    zoneId: ZoneId,
): GuideRowProjection {
    require(viewportFromEpochMillis >= 0L)
    require(viewportToEpochMillis > viewportFromEpochMillis)

    val cells = when (state) {
        GuideProjectionState.NO_GUIDE -> listOf(
            statusCell(
                viewportFromEpochMillis = viewportFromEpochMillis,
                viewportToEpochMillis = viewportToEpochMillis,
                state = GuideProjectionState.NO_GUIDE,
                title = "Нет программы",
                detailLabel = "${channel.displayName} · программа не найдена",
            ),
        )
        GuideProjectionState.SOURCE_CONFLICT -> listOf(
            statusCell(
                viewportFromEpochMillis = viewportFromEpochMillis,
                viewportToEpochMillis = viewportToEpochMillis,
                state = GuideProjectionState.SOURCE_CONFLICT,
                title = "Конфликт источников",
                detailLabel = "${channel.displayName} · конфликт источников программы",
            ),
        )
        GuideProjectionState.READY -> readyCells(
            channelDisplayName = channel.displayName,
            programmes = programmes,
            viewportFromEpochMillis = viewportFromEpochMillis,
            viewportToEpochMillis = viewportToEpochMillis,
            zoneId = zoneId,
        )
    }

    return GuideRowProjection(
        channel = channel,
        state = state,
        primaryLabel = buildString {
            channel.channelNumber?.takeIf(String::isNotBlank)?.let { number ->
                append(number).append("  ")
            }
            if (channel.isFavorite) append("★ ")
            append(channel.displayName)
        },
        groupLabel = channel.groupTitle?.takeIf(String::isNotBlank),
        cells = cells,
        focusChannel = GuideFocusChannel(
            channelId = channel.channelId,
            programmeKeys = cells.mapNotNull(GuideCellProjection::programmeKey),
        ),
    )
}

private fun readyCells(
    channelDisplayName: String,
    programmes: List<GuideProgrammeCell>,
    viewportFromEpochMillis: Long,
    viewportToEpochMillis: Long,
    zoneId: ZoneId,
): List<GuideCellProjection> {
    val visible = programmes
        .asSequence()
        .filter { programme ->
            programme.endEpochMillis > viewportFromEpochMillis &&
                programme.startEpochMillis < viewportToEpochMillis
        }
        .sortedBy(GuideProgrammeCell::startEpochMillis)
        .map { programme ->
            val startEpochMillis = maxOf(programme.startEpochMillis, viewportFromEpochMillis)
            val endEpochMillis = minOf(programme.endEpochMillis, viewportToEpochMillis)
            val title = programme.title?.takeIf(String::isNotBlank) ?: "Без названия"
            val timeLabel =
                "${formatGuideTime(startEpochMillis, zoneId)}–${formatGuideTime(endEpochMillis, zoneId)}"
            GuideCellProjection(
                programmeKey = programme.key,
                state = GuideProjectionState.READY,
                startEpochMillis = startEpochMillis,
                endEpochMillis = endEpochMillis,
                originalStartEpochMillis = programme.startEpochMillis,
                originalEndEpochMillis = programme.endEpochMillis,
                title = title,
                timeLabel = timeLabel,
                detailLabel = "$channelDisplayName · $title · $timeLabel",
            )
        }
        .toList()

    return visible.ifEmpty {
        listOf(
            statusCell(
                viewportFromEpochMillis = viewportFromEpochMillis,
                viewportToEpochMillis = viewportToEpochMillis,
                state = GuideProjectionState.READY,
                title = "Нет передач в этом окне",
                detailLabel = "$channelDisplayName · нет передач в этом окне",
            ),
        )
    }
}

private fun statusCell(
    viewportFromEpochMillis: Long,
    viewportToEpochMillis: Long,
    state: GuideProjectionState,
    title: String,
    detailLabel: String,
): GuideCellProjection = GuideCellProjection(
    programmeKey = null,
    state = state,
    startEpochMillis = viewportFromEpochMillis,
    endEpochMillis = minOf(
        viewportToEpochMillis,
        viewportFromEpochMillis + STATUS_CELL_SPAN_MILLIS,
    ),
    originalStartEpochMillis = null,
    originalEndEpochMillis = null,
    title = title,
    timeLabel = null,
    detailLabel = detailLabel,
)

private fun timelineTicks(
    viewportFromEpochMillis: Long,
    viewportToEpochMillis: Long,
    zoneId: ZoneId,
): List<Long> {
    val first = nextLocalHalfHourEpochMillis(
        epochMillis = viewportFromEpochMillis,
        zoneId = zoneId,
    )
    if (first > viewportToEpochMillis) return emptyList()
    val ticks = mutableListOf<Long>()
    var tick = first
    while (tick <= viewportToEpochMillis) {
        ticks += tick
        if (tick > Long.MAX_VALUE - HALF_HOUR_MILLIS) break
        tick += HALF_HOUR_MILLIS
    }
    return ticks
}

internal fun formatGuideTime(epochMillis: Long, zoneId: ZoneId): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

internal fun formatGuideDuration(durationMillis: Long): String {
    val minutes = durationMillis / MILLIS_PER_MINUTE
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return when {
        remainingMinutes == 0L -> "$hours ч"
        hours == 0L -> "$remainingMinutes мин"
        else -> "$hours ч $remainingMinutes мин"
    }
}

private const val STATUS_CELL_SPAN_MILLIS = 30L * 60_000L
private const val MILLIS_PER_MINUTE = 60_000L
private const val HALF_HOUR_MILLIS = 30L * MILLIS_PER_MINUTE
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
