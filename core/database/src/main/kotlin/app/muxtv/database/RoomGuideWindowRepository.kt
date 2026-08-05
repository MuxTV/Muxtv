package app.muxtv.database

import app.muxtv.catalog.GuideChannelCursor
import app.muxtv.catalog.GuideChannelWindow
import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeWindow
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.PlayableChannelSummary
import kotlinx.coroutines.flow.Flow

internal class RoomGuideWindowRepository(
    private val dao: GuideWindowDao,
    private val invalidationSource: EpgGuideRepository,
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
    ): GuideProgrammeWindow = error("Bounded Guide programme window is not implemented yet.")

    override fun observeDataChanges(): Flow<Unit> = invalidationSource.observeDataChanges()
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
