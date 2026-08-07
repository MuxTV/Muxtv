package app.muxtv

import app.muxtv.catalog.GuideChannelWindow
import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeWindow
import app.muxtv.catalog.GuideProgrammeWindowQuery
import app.muxtv.catalog.GuideWindowRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal object TestGuideWindowRepository : GuideWindowRepository {
    override suspend fun getChannelWindow(query: GuideChannelWindowQuery): GuideChannelWindow =
        GuideChannelWindow(
            channels = emptyList(),
            nextCursor = null,
            isTruncated = false,
        )

    override suspend fun getProgrammeWindow(query: GuideProgrammeWindowQuery): GuideProgrammeWindow =
        GuideProgrammeWindow(
            channels = emptyList(),
            isTruncated = false,
        )

    override fun observeDataChanges(): Flow<Unit> = emptyFlow()
}
