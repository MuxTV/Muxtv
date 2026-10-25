package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import app.muxtv.catalog.ChannelSearchQuery

/**
 * Research-only Search candidate comparator. The FTS hit set and EPG publication remain
 * authoritative v10 surfaces, but active provider eligibility, channel existence and source
 * diversity come from immutable v11 payloads and active revision memberships.
 *
 * The providerChannelId stored in the current FTS document has no direct key in the v11
 * membership schema: a legacy stream_variants lookup is still required to map the indexed
 * document to its occurrence variantId. This is therefore an intermediate parity bridge,
 * not an independent v11 FTS index, and must never be wired to public Search in PROD-A.
 */
@Dao
internal abstract class CatalogShadowSearchDao {
    suspend fun searchCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
        restrictToCanonicalIds: List<String>? = null,
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
        val restrictionEnabled = if (restrictedIds == null) 0 else 1
        val diversityLimit = if (restrictionEnabled == 0) {
            minOf(
                ChannelSearchQuery.MAX_LIMIT,
                (fetchLimit - ChannelSearchQuery.MAX_LIMIT).coerceAtLeast(0),
            )
        } else {
            0
        }
        return selectCandidates(
            profileId = profileId,
            ftsExpression = ftsExpression,
            nowEpochMillis = nowEpochMillis,
            fetchLimit = fetchLimit,
            matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
            restrictionEnabled = restrictionEnabled,
            restrictedCanonicalIds = restrictedIds ?: listOf(RESTRICTION_SENTINEL),
            diversityLimit = diversityLimit,
        )
    }

    @Query(
        """
        WITH hit_documents AS (
            SELECT d.kind,
                   d.canonicalChannelId,
                   d.profileId,
                   d.providerChannelId,
                   d.text
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
                          FROM stream_variants AS occurrence
                          INNER JOIN source_revision_memberships AS membership
                              ON membership.variantId = occurrence.id
                          INNER JOIN sources AS active_source
                              ON active_source.id = membership.sourceId
                             AND active_source.activeRevision = membership.revisionNumber
                          INNER JOIN catalog_payloads AS payload
                              ON payload.payloadId = membership.payloadId
                          WHERE occurrence.providerChannelId = h.providerChannelId
                            AND payload.canonicalChannelId = h.canonicalChannelId
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
        active_epg_matches AS (
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
        active_epg_match_counts AS (
            SELECT canonicalChannelId, COUNT(*) AS matchCount
            FROM active_epg_matches
            GROUP BY canonicalChannelId
        ),
        unambiguous_active_epg_matches AS (
            SELECT active_epg_matches.*
            FROM active_epg_matches
            INNER JOIN active_epg_match_counts
                ON active_epg_match_counts.canonicalChannelId = active_epg_matches.canonicalChannelId
               AND active_epg_match_counts.matchCount = 1
        ),
        current_epg_programmes AS (
            SELECT unambiguous.canonicalChannelId AS canonicalChannelId,
                   programme.primaryTitle AS primaryTitle
            FROM unambiguous_active_epg_matches AS unambiguous
            INNER JOIN epg_programmes AS programme
                ON programme.sourceId = unambiguous.epgSourceId
               AND programme.revisionNumber = unambiguous.epgRevisionNumber
               AND programme.externalChannelId = unambiguous.epgExternalChannelId
               AND programme.sequenceNumber = (
                   SELECT previous_programme.sequenceNumber
                   FROM epg_programmes AS previous_programme
                   WHERE previous_programme.sourceId = unambiguous.epgSourceId
                     AND previous_programme.revisionNumber = unambiguous.epgRevisionNumber
                     AND previous_programme.externalChannelId = unambiguous.epgExternalChannelId
                     AND previous_programme.startEpochMillis <= :nowEpochMillis
                   ORDER BY previous_programme.startEpochMillis DESC,
                            previous_programme.sequenceNumber DESC
                   LIMIT 1
               )
            WHERE (
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
        ),
        epg_candidates AS (
            SELECT current_epg_programmes.canonicalChannelId AS canonicalChannelId,
                   ${ChannelSearchMatchRank.PROGRAMME} AS matchRank
            FROM current_epg_programmes
            WHERE current_epg_programmes.primaryTitle IS NOT NULL
              AND EXISTS (
                  SELECT 1
                  FROM hit_documents AS h
                  WHERE h.kind = '${SearchDocumentKind.EPG_PROGRAMME_TITLE}'
                    AND h.text = current_epg_programmes.primaryTitle
              )
        ),
        combined_candidates AS (
            SELECT canonicalChannelId, matchRank FROM direct_candidates
            UNION ALL
            SELECT canonicalChannelId, matchRank FROM epg_candidates
        ),
        eligible_candidates AS (
            SELECT candidates.canonicalChannelId AS canonicalChannelId,
                   MIN(candidates.matchRank) AS bestMatchRank
            FROM combined_candidates AS candidates
            WHERE EXISTS (
                SELECT 1
                FROM source_revision_memberships AS membership
                INNER JOIN catalog_payloads AS payload
                    ON payload.payloadId = membership.payloadId
                INNER JOIN sources AS active_source
                    ON active_source.id = membership.sourceId
                   AND active_source.activeRevision = membership.revisionNumber
                WHERE payload.canonicalChannelId = candidates.canonicalChannelId
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
        ),
        candidate_sources AS (
            SELECT DISTINCT eligible.canonicalChannelId AS canonicalChannelId,
                            eligible.bestMatchRank AS bestMatchRank,
                            membership.sourceId AS sourceId
            FROM eligible_candidates AS eligible
            INNER JOIN catalog_payloads AS payload
                ON payload.canonicalChannelId = eligible.canonicalChannelId
            INNER JOIN source_revision_memberships AS membership
                ON membership.payloadId = payload.payloadId
            INNER JOIN sources AS active_source
                ON active_source.id = membership.sourceId
               AND active_source.activeRevision = membership.revisionNumber
        ),
        source_best_ranks AS (
            SELECT sourceId,
                   MIN(bestMatchRank) AS bestMatchRank
            FROM candidate_sources
            GROUP BY sourceId
        ),
        source_representatives AS (
            SELECT candidate_sources.sourceId AS sourceId,
                   MIN(candidate_sources.canonicalChannelId) AS canonicalChannelId,
                   candidate_sources.bestMatchRank AS bestMatchRank
            FROM candidate_sources
            INNER JOIN source_best_ranks
                ON source_best_ranks.sourceId = candidate_sources.sourceId
               AND source_best_ranks.bestMatchRank = candidate_sources.bestMatchRank
            WHERE :restrictionEnabled = 0
              AND :diversityLimit > 0
            GROUP BY candidate_sources.sourceId,
                     candidate_sources.bestMatchRank
            ORDER BY candidate_sources.bestMatchRank,
                     candidate_sources.sourceId COLLATE BINARY
            LIMIT :diversityLimit
        ),
        candidate_pool AS (
            SELECT source_representatives.canonicalChannelId AS canonicalChannelId,
                   source_representatives.bestMatchRank AS bestMatchRank,
                   0 AS acquisitionPriority
            FROM source_representatives
            UNION ALL
            SELECT eligible_candidates.canonicalChannelId AS canonicalChannelId,
                   eligible_candidates.bestMatchRank AS bestMatchRank,
                   1 AS acquisitionPriority
            FROM eligible_candidates
        )
        SELECT candidate_pool.canonicalChannelId AS canonicalChannelId,
               MIN(candidate_pool.bestMatchRank) AS bestMatchRank
        FROM candidate_pool
        GROUP BY candidate_pool.canonicalChannelId
        ORDER BY MIN(candidate_pool.acquisitionPriority),
                 MIN(candidate_pool.bestMatchRank),
                 candidate_pool.canonicalChannelId COLLATE BINARY
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
        diversityLimit: Int,
    ): List<ChannelSearchCandidateRow>

    private companion object {
        const val RESTRICTION_SENTINEL = "__mux_search_no_restriction__"
    }
}
