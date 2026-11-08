package app.muxtv.database

import app.muxtv.catalog.PlayableChannelSummary
import kotlinx.coroutines.flow.Flow

/**
 * Instrumentation-only search adapter. It deliberately uses the production publication
 * invalidation token and EPG/FTS document surface, but resolves candidate eligibility,
 * source diversity and final active summaries against the independent v11 shadow storage.
 * No production Search implementation or read path imports this class.
 */
internal class ShadowSearchParityDataSource(
    private val database: MuxTvDatabase,
) : ChannelSearchDataSource {
    override fun observeChanges(): Flow<Unit> = database.channelSearchDao().observeChanges()

    override suspend fun searchCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
        restrictToCanonicalIds: List<String>?,
    ): List<ChannelSearchCandidateRow> =
        database.catalogShadowSearchDao().searchCandidates(
            profileId = profileId,
            ftsExpression = ftsExpression,
            nowEpochMillis = nowEpochMillis,
            fetchLimit = fetchLimit,
            restrictToCanonicalIds = restrictToCanonicalIds,
        )

    override suspend fun activeChannelSummaries(
        profileId: String,
        canonicalChannelIds: List<String>,
    ): List<PlayableChannelSummary> {
        if (canonicalChannelIds.isEmpty()) return emptyList()
        return database.catalogShadowDao().searchActiveSummaries(
            profileId = profileId,
            canonicalChannelIds = canonicalChannelIds.distinct(),
        )
    }
}
