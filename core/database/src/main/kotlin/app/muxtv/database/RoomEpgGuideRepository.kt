package app.muxtv.database

import app.muxtv.catalog.ChannelNowNext
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProgramme
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.NowNextQuery
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomEpgGuideRepository(
    private val dao: EpgGuideDao,
) : EpgGuideRepository {
    override suspend fun getNowNext(query: NowNextQuery): List<ChannelNowNext> {
        if (query.canonicalChannelIds.isEmpty()) return emptyList()

        val snapshot = dao.projectionSnapshot(
            profileId = query.profileId,
            canonicalChannelIds = query.canonicalChannelIds,
            nowEpochMillis = query.nowEpochMillis,
        )
        val matchCountByChannel = snapshot.matchCounts.associate { row ->
            row.canonicalChannelId to row.matchCount
        }
        val programmesByChannel = snapshot.programmeCandidates.groupBy { row ->
            row.canonicalChannelId
        }

        return query.canonicalChannelIds.map { canonicalChannelId ->
            when (val matchCount = matchCountByChannel[canonicalChannelId] ?: 0L) {
                0L -> noGuide(canonicalChannelId)
                1L -> readyProjection(
                    canonicalChannelId = canonicalChannelId,
                    rows = programmesByChannel[canonicalChannelId].orEmpty(),
                    nowEpochMillis = query.nowEpochMillis,
                )
                else -> sourceConflict(canonicalChannelId, matchCount)
            }
        }
    }

    override fun observeDataChanges(): Flow<Unit> =
        dao.observeDataVersion(CURRENT_EPG_MATCH_POLICY_VERSION)
            .map { }

    private fun readyProjection(
        canonicalChannelId: String,
        rows: List<EpgGuideProgrammeCandidateRow>,
        nowEpochMillis: Long,
    ): ChannelNowNext {
        var previous: EpgGuideProgrammeCandidateRow? = null
        var next: EpgGuideProgrammeCandidateRow? = null
        rows.forEach { row ->
            if (row.startEpochMillis <= nowEpochMillis) {
                if (previous == null || row.startEpochMillis > previous!!.startEpochMillis) {
                    previous = row
                }
            } else if (next == null || row.startEpochMillis < next!!.startEpochMillis) {
                next = row
            }
        }

        val current = previous?.let { candidate ->
            val effectiveEnd = when {
                candidate.stopEpochMillis != null && candidate.stopEpochMillis > nowEpochMillis ->
                    candidate.stopEpochMillis
                candidate.stopEpochMillis == null && next != null && next!!.startEpochMillis > nowEpochMillis ->
                    next!!.startEpochMillis
                else -> null
            }
            effectiveEnd
                ?.takeIf { it > candidate.startEpochMillis }
                ?.let { endEpochMillis -> candidate.toGuideProgramme(endEpochMillis) }
        }
        val nextProgramme = next?.toGuideProgramme(
            endEpochMillis = next!!.stopEpochMillis?.takeIf { it > next!!.startEpochMillis },
        )

        var nextBoundary = current?.endEpochMillis?.takeIf { it > nowEpochMillis }
        val nextStart = nextProgramme?.startEpochMillis?.takeIf { it > nowEpochMillis }
        if (nextStart != null && (nextBoundary == null || nextStart < nextBoundary)) {
            nextBoundary = nextStart
        }

        return ChannelNowNext(
            canonicalChannelId = canonicalChannelId,
            state = GuideProjectionState.READY,
            current = current,
            next = nextProgramme,
            nextBoundaryEpochMillis = nextBoundary,
        )
    }

    private fun EpgGuideProgrammeCandidateRow.toGuideProgramme(
        endEpochMillis: Long?,
    ): GuideProgramme = GuideProgramme(
        startEpochMillis = startEpochMillis,
        endEpochMillis = endEpochMillis,
        title = primaryTitle,
    )

    private fun noGuide(canonicalChannelId: String): ChannelNowNext =
        ChannelNowNext(
            canonicalChannelId = canonicalChannelId,
            state = GuideProjectionState.NO_GUIDE,
            current = null,
            next = null,
            nextBoundaryEpochMillis = null,
        )

    private fun sourceConflict(
        canonicalChannelId: String,
        matchCount: Long,
    ): ChannelNowNext {
        require(matchCount >= 2)
        return ChannelNowNext(
            canonicalChannelId = canonicalChannelId,
            state = GuideProjectionState.SOURCE_CONFLICT,
            current = null,
            next = null,
            nextBoundaryEpochMillis = null,
        )
    }
}
