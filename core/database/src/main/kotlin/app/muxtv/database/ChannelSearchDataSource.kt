package app.muxtv.database

import app.muxtv.catalog.PlayableChannelSummary
import kotlinx.coroutines.flow.Flow

internal interface ChannelSearchDataSource {
    fun observeChanges(): Flow<Unit>

    suspend fun searchCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
    ): List<ChannelSearchCandidateRow>

    suspend fun activeChannelSummaries(
        profileId: String,
        canonicalChannelIds: List<String>,
    ): List<PlayableChannelSummary>

    suspend fun nextProgrammeBoundary(
        profileId: String,
        nowEpochMillis: Long,
    ): Long?
}
