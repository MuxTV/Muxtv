package app.muxtv.feature.home

import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.RecentChannel
import app.muxtv.common.programmeProgressFraction
import app.muxtv.player.PlaybackSessionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

const val HOME_RAIL_LIMIT = 10

private val HOME_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm")

internal fun formatHomeTime(
    epochMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String = HOME_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

data class HomeHeroModel(
    val channelId: String?,
    val displayName: String?,
    val channelNumber: String?,
    val isFavorite: Boolean,
    val isCurrentPlayback: Boolean,
    val currentTitle: String?,
    val currentStart: Long?,
    val currentEnd: Long?,
    val nextTitle: String?,
    val nextStart: Long?,
    val nextEnd: Long?,
    val progressFraction: Float?,
    val positionEpochMillis: Long?,
) {
    val hasChannel: Boolean
        get() = channelId != null

    val primaryActionLabel: String
        get() = if (isCurrentPlayback) "Смотреть" else "Продолжить просмотр"
}

data class HomeChannelCard(
    val channelId: String,
    val displayName: String,
    val channelNumber: String?,
    val isFavorite: Boolean,
    val isPlaying: Boolean,
    val currentTitle: String?,
    val currentStart: Long?,
    val currentEnd: Long?,
    val progressFraction: Float?,
)

fun buildHomeHero(
    sessionState: PlaybackSessionState,
    recent: List<RecentChannel>,
    nowNext: Map<String, ChannelNowNext>,
    nowEpochMillis: Long,
): HomeHeroModel {
    val candidate = recent.firstOrNull() ?: return HomeHeroModel(
        channelId = null,
        displayName = null,
        channelNumber = null,
        isFavorite = false,
        isCurrentPlayback = false,
        currentTitle = null,
        currentStart = null,
        currentEnd = null,
        nextTitle = null,
        nextStart = null,
        nextEnd = null,
        progressFraction = null,
        positionEpochMillis = null,
    )
    val summary = candidate.channel
    val nowNextItem = nowNext[summary.channelId]
    val currentProgramme = nowNextItem?.takeIf { it.state == GuideProjectionState.READY }?.current
    val nextProgramme = nowNextItem?.takeIf { it.state == GuideProjectionState.READY }?.next
    val fraction = currentProgramme?.let { programme ->
        val end = programme.endEpochMillis ?: return@let null
        programmeProgressFraction(nowEpochMillis, programme.startEpochMillis, end)
    }
    return HomeHeroModel(
        channelId = summary.channelId,
        displayName = summary.displayName,
        channelNumber = summary.channelNumber,
        isFavorite = summary.isFavorite,
        isCurrentPlayback = sessionState.hasActiveChannel && sessionState.channelId == summary.channelId,
        currentTitle = currentProgramme?.title,
        currentStart = currentProgramme?.startEpochMillis,
        currentEnd = currentProgramme?.endEpochMillis,
        nextTitle = nextProgramme?.title,
        nextStart = nextProgramme?.startEpochMillis,
        nextEnd = nextProgramme?.endEpochMillis,
        progressFraction = fraction,
        positionEpochMillis = currentProgramme?.let { nowEpochMillis },
    )
}

fun buildRecentRail(
    recent: List<RecentChannel>,
    nowNext: Map<String, ChannelNowNext>,
    sessionState: PlaybackSessionState,
    nowEpochMillis: Long,
): List<HomeChannelCard> = recent.take(HOME_RAIL_LIMIT).map { item ->
    val summary = item.channel
    val nowNextItem = nowNext[summary.channelId]
    val currentProgramme = nowNextItem?.takeIf { it.state == GuideProjectionState.READY }?.current
    val fraction = currentProgramme?.let { programme ->
        val end = programme.endEpochMillis ?: return@let null
        programmeProgressFraction(nowEpochMillis, programme.startEpochMillis, end)
    }
    HomeChannelCard(
        channelId = summary.channelId,
        displayName = summary.displayName,
        channelNumber = summary.channelNumber,
        isFavorite = summary.isFavorite,
        isPlaying = sessionState.hasActiveChannel && sessionState.channelId == summary.channelId,
        currentTitle = currentProgramme?.title,
        currentStart = currentProgramme?.startEpochMillis,
        currentEnd = currentProgramme?.endEpochMillis,
        progressFraction = fraction,
    )
}

fun buildFavoritesRail(
    items: List<ChannelBrowseItem>,
    nowNext: Map<String, ChannelNowNext>,
    nowEpochMillis: Long,
): List<HomeChannelCard> = items.take(HOME_RAIL_LIMIT).map { item ->
    val nowNextItem = nowNext[item.channelId]
    val currentProgramme = nowNextItem?.takeIf { it.state == GuideProjectionState.READY }?.current
    val fraction = currentProgramme?.let { programme ->
        val end = programme.endEpochMillis ?: return@let null
        programmeProgressFraction(nowEpochMillis, programme.startEpochMillis, end)
    }
    HomeChannelCard(
        channelId = item.channelId,
        displayName = item.displayName,
        channelNumber = item.channelNumber,
        isFavorite = item.isFavorite,
        isPlaying = item.isCurrentPlayback,
        currentTitle = currentProgramme?.title ?: item.currentProgrammeTitle,
        currentStart = currentProgramme?.startEpochMillis,
        currentEnd = currentProgramme?.endEpochMillis,
        progressFraction = fraction,
    )
}
