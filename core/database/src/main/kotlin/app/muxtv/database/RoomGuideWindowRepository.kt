package app.muxtv.database

import app.muxtv.catalog.ChannelGuideProgrammeWindow
import app.muxtv.catalog.GuideChannelCursor
import app.muxtv.catalog.GuideChannelWindow
import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProgrammeWindow
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlayableChannelSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomGuideWindowRepository(
    private val dao: GuideWindowDao,
    private val invalidationDao: GuideWindowInvalidationDao,
) : GuideWindowRepository {
    override suspend fun getChannelWindow(
        query: GuideChannelWindowQuery,
    ): GuideChannelWindow {
        val after = query.after
        val rows = dao.channelWindow(
            profileId = query.profileId,
            afterHasChannelNumber = after?.channelNumber != null,
            afterChannelNumber = after?.channelNumber,
            afterDisplayName = after?.displayName,
            afterCanonicalChannelId = after?.canonicalChannelId,
            limit = query.limit + 1,
        )
        val isTruncated = rows.size > query.limit
        val visibleRows = if (isTruncated) rows.take(query.limit) else rows
        val channels = visibleRows.map(GuideChannelWindowRow::toSummary)
        val nextCursor = if (isTruncated) {
            visibleRows.last().toCursor()
        } else {
            null
        }
        return GuideChannelWindow(
            channels = channels,
            nextCursor = nextCursor,
            isTruncated = isTruncated,
        )
    }

    override suspend fun getProgrammeWindow(
        query: GuideProgrammeWindowQuery,
    ): GuideProgrammeWindow {
        if (query.canonicalChannelIds.isEmpty()) {
            return GuideProgrammeWindow(emptyList(), isTruncated = false)
        }
        val snapshot = dao.programmeWindowSnapshot(
            profileId = query.profileId,
            canonicalChannelIds = query.canonicalChannelIds,
            fromEpochMillis = query.fromEpochMillis,
            toEpochMillis = query.toEpochMillis,
            limit = query.limit + 1,
        )
        val isTruncated = snapshot.programmeRows.size > query.limit
        val visibleRows = if (isTruncated) {
            snapshot.programmeRows.take(query.limit)
        } else {
            snapshot.programmeRows
        }
        val countsByChannel = snapshot.matchCounts.associate { row ->
            row.canonicalChannelId to row.matchCount
        }
        val rowsByChannel = visibleRows.groupBy(GuideProgrammeWindowRow::canonicalChannelId)
        val channels = query.canonicalChannelIds.map { canonicalChannelId ->
            val matchCount = countsByChannel[canonicalChannelId] ?: 0L
            val state = when {
                matchCount == 0L -> GuideProjectionState.NO_GUIDE
                matchCount == 1L -> GuideProjectionState.READY
                else -> GuideProjectionState.SOURCE_CONFLICT
            }
            ChannelGuideProgrammeWindow(
                canonicalChannelId = canonicalChannelId,
                state = state,
                programmes = if (state == GuideProjectionState.READY) {
                    rowsByChannel[canonicalChannelId]
                        .orEmpty()
                        .map(GuideProgrammeWindowRow::toCell)
                } else {
                    emptyList()
                },
            )
        }
        return GuideProgrammeWindow(
            channels = channels,
            isTruncated = isTruncated,
        )
    }

    override fun observeDataChanges(): Flow<Unit> =
        invalidationDao.observeDataVersion().map { Unit }
}

private fun GuideChannelWindowRow.toSummary(): PlayableChannelSummary = PlayableChannelSummary(
    channelId = channelId,
    displayName = displayName,
    logoUrl = logoUrl,
    groupTitle = groupTitle,
    channelNumber = channelNumber,
    isFavorite = isFavorite,
    variantCount = variantCount,
)

private fun GuideChannelWindowRow.toCursor(): GuideChannelCursor = GuideChannelCursor(
    channelNumber = cursorChannelNumber,
    displayName = displayName,
    canonicalChannelId = channelId,
)

private fun GuideProgrammeWindowRow.toCell(): GuideProgrammeCell = GuideProgrammeCell(
    key = GuideProgrammeKey(
        epgSourceId = epgSourceId,
        epgRevisionNumber = epgRevisionNumber,
        sequenceNumber = sequenceNumber,
    ),
    startEpochMillis = startEpochMillis,
    endEpochMillis = endEpochMillis,
    title = primaryTitle,
)
