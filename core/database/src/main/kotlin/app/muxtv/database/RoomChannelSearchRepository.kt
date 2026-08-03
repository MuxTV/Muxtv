package app.muxtv.database

import app.muxtv.catalog.ChannelSearchQuery
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.ChannelSearchResult
import app.muxtv.catalog.ChannelSearchSnapshot
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import app.muxtv.catalog.PlayableChannelSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest

internal class RoomChannelSearchRepository(
    private val dataSource: ChannelSearchDataSource,
    private val guideRepository: EpgGuideRepository,
) : ChannelSearchRepository {
    override fun observe(query: ChannelSearchQuery): Flow<ChannelSearchSnapshot> {
        if (query.normalizedText.isEmpty()) return flowOf(ChannelSearchSnapshot.EMPTY)

        val tokens = SearchQueryEncoder.encode(query.normalizedText)
        if (tokens.isEmpty()) return flowOf(ChannelSearchSnapshot.EMPTY)

        return dataSource.observeChanges().mapLatest {
            buildSnapshot(query = query, tokens = tokens)
        }
    }

    private suspend fun buildSnapshot(
        query: ChannelSearchQuery,
        tokens: List<SearchQueryToken>,
    ): ChannelSearchSnapshot {
        var truncated = false
        var intersection: MutableMap<String, Int>? = null

        tokens.forEach { token ->
            val fetched = dataSource.searchCandidates(
                profileId = query.profileId,
                ftsExpression = token.ftsExpression,
                nowEpochMillis = query.nowEpochMillis,
                fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
            )
            if (fetched.size > ChannelSearchLimits.MAX_CANDIDATES_PER_TOKEN) {
                truncated = true
            }
            val bounded = fetched.take(ChannelSearchLimits.MAX_CANDIDATES_PER_TOKEN)
            if (bounded.isEmpty()) {
                return ChannelSearchSnapshot(
                    results = emptyList(),
                    isTruncated = truncated,
                    nextBoundaryEpochMillis = dataSource.nextProgrammeBoundary(
                        profileId = query.profileId,
                        nowEpochMillis = query.nowEpochMillis,
                    ),
                )
            }

            val tokenRanks = bounded.associate { row ->
                row.canonicalChannelId to row.bestMatchRank
            }
            val current = intersection
            if (current == null) {
                intersection = tokenRanks.toMutableMap()
            } else {
                val iterator = current.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val tokenRank = tokenRanks[entry.key]
                    if (tokenRank == null) {
                        iterator.remove()
                    } else {
                        // Every token is required. Rank the combined match by its weakest
                        // required origin so name+programme cannot masquerade as a pure name hit.
                        entry.setValue(maxOf(entry.value, tokenRank))
                    }
                }
                if (current.isEmpty()) {
                    return ChannelSearchSnapshot(
                        results = emptyList(),
                        isTruncated = truncated,
                        nextBoundaryEpochMillis = dataSource.nextProgrammeBoundary(
                            profileId = query.profileId,
                            nowEpochMillis = query.nowEpochMillis,
                        ),
                    )
                }
            }
        }

        val requiredRanks = intersection.orEmpty()
        if (requiredRanks.isEmpty()) {
            return ChannelSearchSnapshot(
                results = emptyList(),
                isTruncated = truncated,
                nextBoundaryEpochMillis = dataSource.nextProgrammeBoundary(
                    profileId = query.profileId,
                    nowEpochMillis = query.nowEpochMillis,
                ),
            )
        }

        val summaries = dataSource.activeChannelSummaries(
            profileId = query.profileId,
            canonicalChannelIds = requiredRanks.keys.sorted(),
        )
        val sorted = summaries.sortedWith(
            compareBy<PlayableChannelSummary> { summary ->
                structuredRank(
                    summary = summary,
                    normalizedQuery = query.normalizedText,
                    requiredOriginRank = requiredRanks.getValue(summary.channelId),
                )
            }
                .thenBy { summary -> summary.channelNumber?.toLongOrNull() ?: Long.MAX_VALUE }
                .thenComparator { left, right ->
                    left.displayName.compareTo(right.displayName, ignoreCase = true)
                }
                .thenBy(PlayableChannelSummary::channelId),
        )
        if (sorted.size > query.limit) truncated = true
        val published = sorted.take(query.limit)

        val guideByChannel = if (published.isEmpty()) {
            emptyMap()
        } else {
            guideRepository.getNowNext(
                NowNextQuery(
                    profileId = query.profileId,
                    canonicalChannelIds = published.map(PlayableChannelSummary::channelId),
                    nowEpochMillis = query.nowEpochMillis,
                ),
            ).associateBy { projection -> projection.canonicalChannelId }
        }
        val results = published.map { summary ->
            val projection = guideByChannel[summary.channelId]
            ChannelSearchResult(
                channel = summary,
                currentProgrammeTitle = projection
                    ?.takeIf { it.state == GuideProjectionState.READY }
                    ?.current
                    ?.title,
            )
        }

        return ChannelSearchSnapshot(
            results = results,
            isTruncated = truncated,
            nextBoundaryEpochMillis = dataSource.nextProgrammeBoundary(
                profileId = query.profileId,
                nowEpochMillis = query.nowEpochMillis,
            ),
        )
    }

    private fun structuredRank(
        summary: PlayableChannelSummary,
        normalizedQuery: String,
        requiredOriginRank: Int,
    ): Int = when {
        summary.channelNumber.equals(normalizedQuery, ignoreCase = true) -> RANK_EXACT_NUMBER
        summary.displayName.equals(normalizedQuery, ignoreCase = true) -> RANK_EXACT_NAME
        summary.displayName.startsWith(normalizedQuery, ignoreCase = true) -> RANK_NAME_PREFIX
        requiredOriginRank == ChannelSearchMatchRank.NAME -> RANK_PROVIDER_OR_NAME_TOKEN
        else -> requiredOriginRank
    }

    private companion object {
        const val RANK_EXACT_NUMBER = 1
        const val RANK_EXACT_NAME = 2
        const val RANK_NAME_PREFIX = 3
        const val RANK_PROVIDER_OR_NAME_TOKEN = ChannelSearchMatchRank.PROVIDER
    }
}
