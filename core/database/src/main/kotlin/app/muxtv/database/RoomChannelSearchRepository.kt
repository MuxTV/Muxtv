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
        val probes = tokens.map { token ->
            val fetched = dataSource.searchCandidates(
                profileId = query.profileId,
                ftsExpression = token.ftsExpression,
                nowEpochMillis = query.nowEpochMillis,
                fetchLimit = ChannelSearchLimits.CANDIDATE_FETCH_LIMIT,
            )
            TokenProbe(
                token = token,
                rows = fetched.take(ChannelSearchLimits.MAX_CANDIDATES_PER_TOKEN),
                overflow = fetched.size > ChannelSearchLimits.MAX_CANDIDATES_PER_TOKEN,
            )
        }

        // An unrestricted empty token proves the complete AND-query is empty, even if another
        // broad token overflowed its probe. Do not report a false truncation in that case.
        if (probes.any { probe -> probe.rows.isEmpty() }) {
            return emptySnapshot(query = query, truncated = false)
        }

        val seed = probes.minWithOrNull(
            compareBy<TokenProbe> { probe -> probe.rows.size }
                .thenBy { probe -> if (probe.overflow) 1 else 0 },
        ) ?: return emptySnapshot(query = query, truncated = false)

        var truncated = seed.overflow
        val intersection = seed.rows.associateTo(mutableMapOf()) { row ->
            row.canonicalChannelId to row.bestMatchRank
        }

        probes.forEach { probe ->
            if (probe === seed || intersection.isEmpty()) return@forEach

            val rows = if (probe.overflow) {
                // The broad unrestricted probe is intentionally incomplete. Re-check that token
                // only inside the already bounded seed set, so a precise query such as
                // `канал 999` cannot lose channel 999 merely because `канал*` has >800 hits.
                dataSource.searchCandidates(
                    profileId = query.profileId,
                    ftsExpression = probe.token.ftsExpression,
                    nowEpochMillis = query.nowEpochMillis,
                    fetchLimit = intersection.size,
                    restrictToCanonicalIds = intersection.keys.sorted(),
                )
            } else {
                probe.rows
            }
            val tokenRanks = rows.associate { row ->
                row.canonicalChannelId to row.bestMatchRank
            }
            val iterator = intersection.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val tokenRank = tokenRanks[entry.key]
                if (tokenRank == null) {
                    iterator.remove()
                } else {
                    // Every token is required. Rank by the weakest required origin so
                    // name+programme cannot masquerade as a pure name hit.
                    entry.setValue(maxOf(entry.value, tokenRank))
                }
            }
        }

        if (intersection.isEmpty()) {
            return emptySnapshot(query = query, truncated = truncated)
        }

        val summaries = dataSource.activeChannelSummaries(
            profileId = query.profileId,
            canonicalChannelIds = intersection.keys.sorted(),
        )
        val sorted = summaries.sortedWith(
            compareBy<PlayableChannelSummary> { summary ->
                structuredRank(
                    summary = summary,
                    normalizedQuery = query.normalizedText,
                    requiredOriginRank = intersection.getValue(summary.channelId),
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

    private suspend fun emptySnapshot(
        query: ChannelSearchQuery,
        truncated: Boolean,
    ): ChannelSearchSnapshot = ChannelSearchSnapshot(
        results = emptyList(),
        isTruncated = truncated,
        nextBoundaryEpochMillis = dataSource.nextProgrammeBoundary(
            profileId = query.profileId,
            nowEpochMillis = query.nowEpochMillis,
        ),
    )

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

    private data class TokenProbe(
        val token: SearchQueryToken,
        val rows: List<ChannelSearchCandidateRow>,
        val overflow: Boolean,
    )

    private companion object {
        const val RANK_EXACT_NUMBER = 1
        const val RANK_EXACT_NAME = 2
        const val RANK_NAME_PREFIX = 3
        const val RANK_PROVIDER_OR_NAME_TOKEN = ChannelSearchMatchRank.PROVIDER
    }
}
