package app.muxtv.feature.channels

import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary

internal data class ChannelRowProjection(
    val channel: PlayableChannelSummary,
    val guideState: GuideProjectionState,
    val currentTitle: String?,
    val nextTitle: String?,
    val nextBoundaryEpochMillis: Long?,
) {
    val channelId: String
        get() = channel.channelId
}

internal fun projectChannelRows(
    channels: List<PlayableChannelSummary>,
    guide: List<ChannelNowNext>,
): List<ChannelRowProjection> {
    val guideByChannelId = guide.associateBy(ChannelNowNext::canonicalChannelId)
    return channels.map { channel ->
        val projection = guideByChannelId[channel.channelId]
        if (projection == null) {
            ChannelRowProjection(
                channel = channel,
                guideState = GuideProjectionState.NO_GUIDE,
                currentTitle = null,
                nextTitle = null,
                nextBoundaryEpochMillis = null,
            )
        } else {
            projection.toRow(channel)
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

private fun ChannelNowNext.toRow(channel: PlayableChannelSummary): ChannelRowProjection =
    if (state == GuideProjectionState.READY) {
        ChannelRowProjection(
            channel = channel,
            guideState = state,
            currentTitle = current?.title,
            nextTitle = next?.title,
            nextBoundaryEpochMillis = nextBoundaryEpochMillis,
        )
    } else {
        ChannelRowProjection(
            channel = channel,
            guideState = state,
            currentTitle = null,
            nextTitle = null,
            nextBoundaryEpochMillis = null,
        )
    }
