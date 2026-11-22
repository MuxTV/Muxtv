package app.muxtv.navigation

import app.muxtv.feature.guide.GuidePlaybackSelection
import app.muxtv.player.PlaybackIntent

internal fun AppDestination.Player.toPlaybackIntent(): PlaybackIntent {
    val resolvedProgrammeId = programmeId
    val resolvedStart = programmeStartEpochMillis
    val resolvedEnd = programmeEndEpochMillis
    return if (resolvedProgrammeId == null || resolvedStart == null || resolvedEnd == null) {
        PlaybackIntent.Live(channelId)
    } else {
        PlaybackIntent.CatchupProgram(
            channelId = channelId,
            programmeId = resolvedProgrammeId,
            startEpochMillis = resolvedStart,
            endEpochMillis = resolvedEnd,
        )
    }
}

internal fun GuidePlaybackSelection.toPlayerDestination(): AppDestination.Player = when (this) {
    is GuidePlaybackSelection.Live -> AppDestination.Player(channelId)
    is GuidePlaybackSelection.CatchupProgram -> AppDestination.Player(
        channelId = channelId,
        programmeId = programmeId,
        programmeStartEpochMillis = startEpochMillis,
        programmeEndEpochMillis = endEpochMillis,
    )
}
