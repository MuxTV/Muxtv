package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.common.programmeProgressFraction
import app.muxtv.player.PlaybackSessionState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class ChannelRowUiModel(
    val channelId: String,
    val displayName: String,
    val channelNumber: String?,
    val groupTitle: String?,
    val isFavorite: Boolean,
    val isCurrentPlayback: Boolean,
    val variantCount: Int,
    val guideState: GuideProjectionState,
    val currentTitle: String?,
    val currentEndEpochMillis: Long?,
    val nextTitle: String?,
    val nextStartEpochMillis: Long?,
    val progressFraction: Float?,
) {
    val metadataLabel: String = buildString {
        groupTitle?.takeIf(String::isNotBlank)?.let(::append)
        if (variantCount > 1) {
            if (isNotEmpty()) append("  ·  ")
            append(variantCount).append(" источника")
        }
    }

    val currentProgrammeLabel: String = when (guideState) {
        GuideProjectionState.READY -> currentTitle?.let { "Сейчас: $it" } ?: " "
        GuideProjectionState.SOURCE_CONFLICT -> "Программа недоступна"
        GuideProjectionState.NO_GUIDE -> " "
    }

    val nextProgrammeLabel: String =
        if (guideState == GuideProjectionState.READY) {
            nextTitle?.let { "Далее: $it" } ?: " "
        } else {
            " "
        }

    val currentEndLabel: String? = when (guideState) {
        GuideProjectionState.READY -> currentEndEpochMillis?.let(::formatHm)
        else -> null
    }

    val nextStartLabel: String? = when (guideState) {
        GuideProjectionState.READY -> nextStartEpochMillis?.let(::formatHm)
        else -> null
    }
}

fun buildChannelRow(
    item: ChannelBrowseItem,
    nowNext: ChannelNowNext?,
    playback: PlaybackSessionState,
    nowEpochMillis: Long,
): ChannelRowUiModel {
    val ready = nowNext?.takeIf { it.state == GuideProjectionState.READY }
    val current = ready?.current
    val next = ready?.next
    val fraction = current?.let { programme ->
        val end = programme.endEpochMillis ?: return@let null
        programmeProgressFraction(nowEpochMillis, programme.startEpochMillis, end)
    }
    return ChannelRowUiModel(
        channelId = item.channelId,
        displayName = item.displayName,
        channelNumber = item.channelNumber,
        groupTitle = item.groupTitle,
        isFavorite = item.isFavorite,
        isCurrentPlayback = playback.channelId == item.channelId,
        variantCount = item.variantCount,
        guideState = item.guideState,
        currentTitle = current?.title ?: item.currentProgrammeTitle,
        currentEndEpochMillis = current?.endEpochMillis ?: item.currentProgrammeEndEpochMillis,
        nextTitle = next?.title ?: item.nextProgrammeTitle,
        nextStartEpochMillis = next?.startEpochMillis ?: item.nextProgrammeStartEpochMillis,
        progressFraction = fraction,
    )
}

fun formatHm(
    epochMillis: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    format.timeZone = timeZone
    return format.format(Date(epochMillis))
}
