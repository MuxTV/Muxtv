package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import app.muxtv.catalog.PlayableChannelSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal data class ChannelSearchCandidateRow(
    val canonicalChannelId: String,
    val bestMatchRank: Int,
) {
    init {
        require(canonicalChannelId.isNotBlank())
        require(bestMatchRank in ChannelSearchMatchRank.NAME..ChannelSearchMatchRank.PROGRAMME)
    }

    override fun toString(): String =
        "ChannelSearchCandidateRow(canonicalChannelId=<redacted>, bestMatchRank=$bestMatchRank)"
}

internal object ChannelSearchMatchRank {
    const val NAME = 3
    const val PROVIDER = 4
    const val GROUP = 5
    const val PROGRAMME = 6
}

@Dao
internal abstract class ChannelSearchDao : ChannelSearchDataSource {
    override fun observeChanges(): Flow<Unit> = observeChangeToken().map { }

    override suspend fun searchCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
        restrictToCanonicalIds: List<String>?,
    ): List<ChannelSearchCandidateRow> {
        require(profileId.isNotBlank())
        require(ftsExpression.isNotBlank())
        require(nowEpochMillis >= 0)
        require(fetchLimit in 1..ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)

        val restrictedIds = restrictToCanonicalIds?.distinct()
        if (restrictedIds != null) {
            require(restrictedIds.size <= ChannelSearchLimits.MAX_CANDIDATES_PER_TOKEN)
            require(restrictedIds.none(String::isBlank))
            if (restrictedIds.isEmpty()) return emptyList()
        }
        return selectCandidates(
            profileId = profileId,
            ftsExpression = ftsExpression,
            nowEpochMillis = nowEpochMillis,
            fetchLimit = fetchLimit,
            matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
            restrictionEnabled = if (restrictedIds == null) 0 else 1,
            restrictedCanonicalIds = restrictedIds ?: listOf(RESTRICTION_SENTINEL),
        )
    }

    override suspend fun activeChannelSummaries(
        profileId: String,
        canonicalChannelIds: List<String>,
    ): List<PlayableChannelSummary> {
        require(profileId.isNotBlank())
        require(canonicalChannelIds.size <= ChannelSearchLimits.MAX_CANDIDATES_PER_TOKEN)
        require(canonicalChannelIds.none(String::isBlank))
        if (canonicalChannelIds.isEmpty()) return emptyList()
        return selectActiveChannelSummaries(
            profileId = profileId,
            canonicalChannelIds = canonicalChannelIds.distinct(),
        )
    }

    override suspend fun nextProgrammeBoundary(
        profileId: String,
        nowEpochMillis: Long,
    ): Long? {
        require(profileId.isNotBlank())
        require(nowEpochMillis >= 0)
        return selectNextProgrammeBoundary(
            profileId = profileId,
            nowEpochMillis = nowEpochMillis,
            matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
        )
    }

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM search_documents)
          + (SELECT COUNT(*) FROM canonical_channels)
          + (SELECT COUNT(*) FROM provider_channels)
          + (SELECT COUNT(*) FROM stream_variants)
          + (SELECT COUNT(*) FROM user_channel_overlays)
          + (SELECT COUNT(*) FROM sources)
          + (SELECT COUNT(*) FROM epg_sources)
          + (SELECT COUNT(*) FROM epg_channel_matches)
          + (SELECT COUNT(*) FROM epg_programmes)
        """,
    )
    protected abstract fun observeChangeToken(): Flow<Long>

    @Query(
        """
        WITH hit_documents AS (
            SELECT d.kind,
                   d.canonicalChannelId,
                   d.profileId,
                   d.providerChannelId,
                   d.epgSourceId,
                   d.epgRevisionNumber,
                   d.epgExternalChannelId,
                   d.epgProgrammeSequence
            FROM search_documents_fts
            INNER JOIN search_documents AS d
                ON d.rowid = search_documents_fts.rowid
            WHERE search_documents_fts MATCH :ftsExpression
        ),
        direct_candidates AS (
            SELECT h.canonicalChannelId AS canonicalChannelId,
                   CASE
                       WHEN h.kind = '${SearchDocumentKind.CANONICAL_NAME}'
                         OR h.kind = '${SearchDocumentKind.OVERLAY_CUSTOM_NAME}'
                           THEN ${ChannelSearchMatchRank.NAME}
                       WHEN h.kind = '${SearchDocumentKind.PROVIDER_GROUP}'
                           THEN ${ChannelSearchMatchRank.GROUP}
                       ELSE ${ChannelSearchMatchRank.PROVIDER}
                   END AS matchRank
            FROM hit_documents AS h
            WHERE h.canonicalChannelId IS NOT NULL
              AND (
                  h.kind = '${SearchDocumentKind.CANONICAL_NAME}'
                  OR (
                      h.kind IN (
                          '${SearchDocumentKind.PROVIDER_RAW_NAME}',
                          '${SearchDocumentKind.PROVIDER_GROUP}',
                          '${SearchDocumentKind.PROVIDER_NUMBER}'
                      )
                      AND EXISTS (
                          SELECT 1
                          FROM provider_channels
                          INNER JOIN sources
                              ON sources.id = provider_channels.sourceId
                             AND sources.activeRevision = provider_channels.revisionNumber
                          INNER JOIN stream_variants
                              ON stream_variants.providerChannelId = provider_channels.id
                          WHERE provider_channels.id = h.providerChannelId
                            AND stream_variants.canonicalChannelId = h.canonicalChannelId
                      )
                  )
                  OR (
                      h.kind IN (
                          '${SearchDocumentKind.OVERLAY_CUSTOM_NAME}',
                          '${SearchDocumentKind.OVERLAY_NUMBER}'
                      )
                      AND h.profileId = :profileId
                  )
              )
        ),
        epg_candidates AS (
            SELECT matches.canonicalChannelId AS canonicalChannelId,
                   ${ChannelSearchMatchRank.PROGRAMME} AS matchRank
            FROM hit_documents AS h
            INNER JOIN epg_programmes AS programme
                ON programme.sourceId = h.epgSourceId
               AND programme.revisionNumber = h.epgRevisionNumber
               AND programme.externalChannelId = h.epgExternalChannelId
               AND programme.sequenceNumber = h.epgProgrammeSequence
            INNER JOIN epg_channel_matches AS matches
                ON matches.epgSourceId = h.epgSourceId
               AND matches.epgRevisionNumber = h.epgRevisionNumber
               AND matches.epgExternalChannelId = h.epgExternalChannelId
            INNER JOIN epg_sources
                ON epg_sources.id = matches.epgSourceId
               AND epg_sources.activeRevision = matches.epgRevisionNumber
               AND epg_sources.providerSourceId = matches.providerSourceId
            INNER JOIN sources
                ON sources.id = matches.providerSourceId
               AND sources.activeRevision = matches.catalogRevisionNumber
            WHERE h.kind = '${SearchDocumentKind.EPG_PROGRAMME_TITLE}'
              AND matches.matchPolicyVersion = :matchPolicyVersion
              AND matches.decision = 'MATCHED'
              AND matches.canonicalChannelId IS NOT NULL
              AND programme.sequenceNumber = (
                  SELECT previous_programme.sequenceNumber
                  FROM epg_programmes AS previous_programme
                  WHERE previous_programme.sourceId = programme.sourceId
                    AND previous_programme.revisionNumber = programme.revisionNumber
                    AND previous_programme.externalChannelId = programme.externalChannelId
                    AND previous_programme.startEpochMillis <= :nowEpochMillis
                  ORDER BY previous_programme.startEpochMillis DESC,
                           previous_programme.sequenceNumber DESC
                  LIMIT 1
              )
              AND (
                  (
                      programme.stopEpochMillis IS NOT NULL
                      AND programme.stopEpochMillis > :nowEpochMillis
                  )
                  OR (
                      programme.stopEpochMillis IS NULL
                      AND EXISTS (
                          SELECT 1
                          FROM epg_programmes AS next_programme
                          WHERE next_programme.sourceId = programme.sourceId
                            AND next_programme.revisionNumber = programme.revisionNumber
                            AND next_programme.externalChannelId = programme.externalChannelId
                            AND next_programme.startEpochMillis > :nowEpochMillis
                          LIMIT 1
                      )
                  )
              )
              AND (
                  SELECT COUNT(*)
                  FROM epg_channel_matches AS current_matches
                  INNER JOIN epg_sources AS current_epg_sources
                      ON current_epg_sources.id = current_matches.epgSourceId
                     AND current_epg_sources.activeRevision = current_matches.epgRevisionNumber
                     AND current_epg_sources.providerSourceId = current_matches.providerSourceId
                  INNER JOIN sources AS current_sources
                      ON current_sources.id = current_matches.providerSourceId
                     AND current_sources.activeRevision = current_matches.catalogRevisionNumber
                  WHERE current_matches.canonicalChannelId = matches.canonicalChannelId
                    AND current_matches.matchPolicyVersion = :matchPolicyVersion
                    AND current_matches.decision = 'MATCHED'
              ) = 1
        ),
        combined_candidates AS (
            SELECT canonicalChannelId, matchRank FROM direct_candidates
            UNION ALL
            SELECT canonicalChannelId, matchRank FROM epg_candidates
        )
        SELECT candidates.canonicalChannelId AS canonicalChannelId,
               MIN(candidates.matchRank) AS bestMatchRank
        FROM combined_candidates AS candidates
        WHERE EXISTS (
            SELECT 1
            FROM stream_variants
            INNER JOIN provider_channels
                ON provider_channels.id = stream_variants.providerChannelId
            INNER JOIN sources
                ON sources.id = provider_channels.sourceId
               AND sources.activeRevision = provider_channels.revisionNumber
            WHERE stream_variants.canonicalChannelId = candidates.canonicalChannelId
        )
          AND COALESCE(
              (
                  SELECT user_channel_overlays.isHidden
                  FROM user_channel_overlays
                  WHERE user_channel_overlays.profileId = :profileId
                    AND user_channel_overlays.canonicalChannelId = candidates.canonicalChannelId
                  LIMIT 1
              ),
              0
          ) = 0
          AND (
              :restrictionEnabled = 0
              OR candidates.canonicalChannelId IN (:restrictedCanonicalIds)
          )
        GROUP BY candidates.canonicalChannelId
        ORDER BY candidates.canonicalChannelId COLLATE BINARY
        LIMIT :fetchLimit
        """,
    )
    protected abstract suspend fun selectCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
        matchPolicyVersion: Int,
        restrictionEnabled: Int,
        restrictedCanonicalIds: List<String>,
    ): List<ChannelSearchCandidateRow>

    @Query(
        """
        SELECT canonical_channels.id AS channelId,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS displayName,
               MIN(provider_channels.logoUrl) AS logoUrl,
               MIN(provider_channels.groupTitle) AS groupTitle,
               COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) AS channelNumber,
               COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite,
               COUNT(DISTINCT stream_variants.id) AS variantCount
        FROM canonical_channels
        INNER JOIN stream_variants
            ON stream_variants.canonicalChannelId = canonical_channels.id
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources
            ON sources.id = provider_channels.sourceId
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = canonical_channels.id
        WHERE canonical_channels.id IN (:canonicalChannelIds)
          AND provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        GROUP BY canonical_channels.id,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        ORDER BY canonical_channels.id COLLATE BINARY
        """,
    )
    protected abstract suspend fun selectActiveChannelSummaries(
        profileId: String,
        canonicalChannelIds: List<String>,
    ): List<PlayableChannelSummary>

    @Query(
        """
        WITH active_matches AS (
            SELECT matches.epgSourceId,
                   matches.epgRevisionNumber,
                   matches.epgExternalChannelId,
                   matches.canonicalChannelId
            FROM epg_channel_matches AS matches
            INNER JOIN epg_sources
                ON epg_sources.id = matches.epgSourceId
               AND epg_sources.activeRevision = matches.epgRevisionNumber
               AND epg_sources.providerSourceId = matches.providerSourceId
            INNER JOIN sources
                ON sources.id = matches.providerSourceId
               AND sources.activeRevision = matches.catalogRevisionNumber
            WHERE matches.matchPolicyVersion = :matchPolicyVersion
              AND matches.decision = 'MATCHED'
              AND matches.canonicalChannelId IS NOT NULL
        ),
        match_counts AS (
            SELECT canonicalChannelId, COUNT(*) AS matchCount
            FROM active_matches
            GROUP BY canonicalChannelId
        ),
        unambiguous_matches AS (
            SELECT active_matches.*
            FROM active_matches
            INNER JOIN match_counts
                ON match_counts.canonicalChannelId = active_matches.canonicalChannelId
               AND match_counts.matchCount = 1
            LEFT JOIN user_channel_overlays
                ON user_channel_overlays.profileId = :profileId
               AND user_channel_overlays.canonicalChannelId = active_matches.canonicalChannelId
            WHERE COALESCE(user_channel_overlays.isHidden, 0) = 0
        ),
        boundary_candidates AS (
            SELECT CASE
                WHEN previous_programme.stopEpochMillis IS NOT NULL
                 AND previous_programme.stopEpochMillis > :nowEpochMillis
                    THEN previous_programme.stopEpochMillis
                WHEN previous_programme.stopEpochMillis IS NULL
                    THEN (
                        SELECT next_programme.startEpochMillis
                        FROM epg_programmes AS next_programme
                        WHERE next_programme.sourceId = unambiguous_matches.epgSourceId
                          AND next_programme.revisionNumber = unambiguous_matches.epgRevisionNumber
                          AND next_programme.externalChannelId = unambiguous_matches.epgExternalChannelId
                          AND next_programme.startEpochMillis > :nowEpochMillis
                        ORDER BY next_programme.startEpochMillis ASC,
                                 next_programme.sequenceNumber ASC
                        LIMIT 1
                    )
                ELSE NULL
            END AS boundaryEpochMillis
            FROM unambiguous_matches
            LEFT JOIN epg_programmes AS previous_programme
                ON previous_programme.sourceId = unambiguous_matches.epgSourceId
               AND previous_programme.revisionNumber = unambiguous_matches.epgRevisionNumber
               AND previous_programme.externalChannelId = unambiguous_matches.epgExternalChannelId
               AND previous_programme.sequenceNumber = (
                   SELECT previous_candidate.sequenceNumber
                   FROM epg_programmes AS previous_candidate
                   WHERE previous_candidate.sourceId = unambiguous_matches.epgSourceId
                     AND previous_candidate.revisionNumber = unambiguous_matches.epgRevisionNumber
                     AND previous_candidate.externalChannelId = unambiguous_matches.epgExternalChannelId
                     AND previous_candidate.startEpochMillis <= :nowEpochMillis
                   ORDER BY previous_candidate.startEpochMillis DESC,
                            previous_candidate.sequenceNumber DESC
                   LIMIT 1
               )
            UNION ALL
            SELECT (
                SELECT next_programme.startEpochMillis
                FROM epg_programmes AS next_programme
                WHERE next_programme.sourceId = unambiguous_matches.epgSourceId
                  AND next_programme.revisionNumber = unambiguous_matches.epgRevisionNumber
                  AND next_programme.externalChannelId = unambiguous_matches.epgExternalChannelId
                  AND next_programme.startEpochMillis > :nowEpochMillis
                ORDER BY next_programme.startEpochMillis ASC,
                         next_programme.sequenceNumber ASC
                LIMIT 1
            ) AS boundaryEpochMillis
            FROM unambiguous_matches
        )
        SELECT MIN(boundaryEpochMillis)
        FROM boundary_candidates
        WHERE boundaryEpochMillis > :nowEpochMillis
        """,
    )
    protected abstract suspend fun selectNextProgrammeBoundary(
        profileId: String,
        nowEpochMillis: Long,
        matchPolicyVersion: Int,
    ): Long?

    private companion object {
        const val RESTRICTION_SENTINEL = "__mux_search_no_restriction__"
    }
}
