package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState

internal data class ChannelRowProjection(
    val channel: PlayableChannelSummary,
    val guideState: GuideProjectionState,
    val currentTitle: String?,
    val nextTitle: String?,
    val nextBoundaryEpochMillis: Long?,
    val isCurrentPlayback: Boolean,
    val isPlaying: Boolean,
) {
    val channelId: String
        get() = channel.channelId
}

internal fun projectChannelRows(
    channels: List<PlayableChannelSummary>,
    guide: List<ChannelNowNext>,
    playbackSessionState: PlaybackSessionState = PlaybackSessionState.Idle,
): List<ChannelRowProjection> {
    val guideByChannelId = guide.associateBy(ChannelNowNext::canonicalChannelId)
    return channels.map { channel ->
        val projection = guideByChannelId[channel.channelId]
        val isCurrentPlayback =
            playbackSessionState.phase != PlaybackSessionPhase.IDLE &&
                playbackSessionState.channelId == channel.channelId
        val isPlaying = isCurrentPlayback && playbackSessionState.isPlaying
        if (projection == null) {
            ChannelRowProjection(
                channel = channel,
                guideState = GuideProjectionState.NO_GUIDE,
                currentTitle = null,
                nextTitle = null,
                nextBoundaryEpochMillis = null,
                isCurrentPlayback = isCurrentPlayback,
                isPlaying = isPlaying,
            )
        } else {
            projection.toRow(
                channel = channel,
                isCurrentPlayback = isCurrentPlayback,
                isPlaying = isPlaying,
            )
        }
    }
}

internal fun earliestFutureGuideBoundary(
    rows: List<ChannelRowProjection>,
    nowEpochMillis: Long,
): Long? {
    require(nowEpochMillis >= 0)
    return rows.asSequence()
        .mapNotNull(ChannelRowProjection::nextBoundaryEpochMillis)
        .filter { boundary -> boundary > nowEpochMillis }
        .minOrNull()
}

private fun ChannelNowNext.toRow(
    channel: PlayableChannelSummary,
    isCurrentPlayback: Boolean,
    isPlaying: Boolean,
): ChannelRowProjection =
    if (state == GuideProjectionState.READY) {
        ChannelRowProjection(
            channel = channel,
            guideState = state,
            currentTitle = current?.title,
            nextTitle = next?.title,
            nextBoundaryEpochMillis = nextBoundaryEpochMillis,
            isCurrentPlayback = isCurrentPlayback,
            isPlaying = isPlaying,
        )
    } else {
        ChannelRowProjection(
            channel = channel,
            guideState = state,
            currentTitle = null,
            nextTitle = null,
            nextBoundaryEpochMillis = null,
            isCurrentPlayback = isCurrentPlayback,
            isPlaying = isPlaying,
        )
    }
