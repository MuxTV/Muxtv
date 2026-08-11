package app.muxtv.database.measurement

import app.muxtv.database.CURRENT_EPG_MATCH_POLICY_VERSION
import app.muxtv.database.ChannelSearchLimits
import app.muxtv.database.ChannelSearchMatchRank
import app.muxtv.database.SearchDocumentKind

internal object CatalogSearchQueryPlans {
    fun queries(
        profileId: String,
        nowEpochMillis: Long,
        candidateProbes: List<CatalogSearchCandidatePlanProbe>,
        summaryCanonicalIdSets: List<List<String>>,
        nowNextCanonicalIdSets: List<List<String>>,
    ): List<Pair<String, List<String>>> {
        require(profileId == "measurement-profile")
        require(nowEpochMillis >= 0)
        require(candidateProbes.isNotEmpty())
        require(summaryCanonicalIdSets.isNotEmpty())
        require(nowNextCanonicalIdSets.isNotEmpty())
        return listOf(
            "search-candidate-resolution" to candidateProbes.map { probe ->
                candidateQuery(profileId, nowEpochMillis, probe)
            },
            "search-summary-materialization-ranking" to summaryCanonicalIdSets.map { ids ->
                summaryQuery(profileId, ids.toSqlIds())
            },
            "search-published-now-next" to nowNextCanonicalIdSets.flatMap { ids ->
                val sqlIds = ids.toSqlIds()
                listOf(
                    nowNextMatchCountQuery(profileId, sqlIds),
                    nowNextProgrammeQuery(profileId, nowEpochMillis, sqlIds),
                )
            },
            "search-global-boundary-scan" to listOf(globalBoundaryQuery(profileId, nowEpochMillis)),
        )
    }

    private fun candidateQuery(
        profileId: String,
        now: Long,
        probe: CatalogSearchCandidatePlanProbe,
    ): String {
        val restrictionEnabled = if (probe.restrictedCanonicalIds == null) 0 else 1
        val restrictedIds = (probe.restrictedCanonicalIds ?: listOf(RESTRICTION_SENTINEL)).toSqlIds()
        return """
        WITH hit_documents AS (
            SELECT d.kind, d.canonicalChannelId, d.profileId, d.providerChannelId, d.text
            FROM search_documents_fts
            INNER JOIN search_documents AS d ON d.rowid = search_documents_fts.rowid
            WHERE search_documents_fts MATCH ${probe.ftsExpression.toSqlLiteral()}
        ),
        direct_candidates AS (
            SELECT h.canonicalChannelId,
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
                          SELECT 1 FROM provider_channels
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
                      AND h.profileId = '$profileId'
                  )
              )
        )
        , active_epg_matches AS (
            SELECT matches.epgSourceId, matches.epgRevisionNumber,
                   matches.epgExternalChannelId, matches.canonicalChannelId
            FROM epg_channel_matches AS matches
            INNER JOIN epg_sources
                ON epg_sources.id = matches.epgSourceId
               AND epg_sources.activeRevision = matches.epgRevisionNumber
               AND epg_sources.providerSourceId = matches.providerSourceId
            INNER JOIN sources
                ON sources.id = matches.providerSourceId
               AND sources.activeRevision = matches.catalogRevisionNumber
            WHERE matches.matchPolicyVersion = $CURRENT_EPG_MATCH_POLICY_VERSION
              AND matches.decision = 'MATCHED'
              AND matches.canonicalChannelId IS NOT NULL
        ),
        active_epg_match_counts AS (
            SELECT canonicalChannelId, COUNT(*) AS matchCount
            FROM active_epg_matches GROUP BY canonicalChannelId
        ),
        unambiguous_active_epg_matches AS (
            SELECT active_epg_matches.* FROM active_epg_matches
            INNER JOIN active_epg_match_counts
                ON active_epg_match_counts.canonicalChannelId = active_epg_matches.canonicalChannelId
               AND active_epg_match_counts.matchCount = 1
        ),
        current_epg_programmes AS (
            SELECT unambiguous.canonicalChannelId, programme.primaryTitle
            FROM unambiguous_active_epg_matches AS unambiguous
            INNER JOIN epg_programmes AS programme
                ON programme.sourceId = unambiguous.epgSourceId
               AND programme.revisionNumber = unambiguous.epgRevisionNumber
               AND programme.externalChannelId = unambiguous.epgExternalChannelId
               AND programme.sequenceNumber = (
                   SELECT previous.sequenceNumber FROM epg_programmes AS previous
                   WHERE previous.sourceId = unambiguous.epgSourceId
                     AND previous.revisionNumber = unambiguous.epgRevisionNumber
                     AND previous.externalChannelId = unambiguous.epgExternalChannelId
                     AND previous.startEpochMillis <= $now
                   ORDER BY previous.startEpochMillis DESC, previous.sequenceNumber DESC
                   LIMIT 1
               )
            WHERE (
                (programme.stopEpochMillis IS NOT NULL AND programme.stopEpochMillis > $now)
                OR (
                    programme.stopEpochMillis IS NULL
                    AND EXISTS (
                        SELECT 1 FROM epg_programmes AS following
                        WHERE following.sourceId = programme.sourceId
                          AND following.revisionNumber = programme.revisionNumber
                          AND following.externalChannelId = programme.externalChannelId
                          AND following.startEpochMillis > $now
                        LIMIT 1
                    )
                )
            )
        ),
        epg_candidates AS (
            SELECT current_epg_programmes.canonicalChannelId,
                   ${ChannelSearchMatchRank.PROGRAMME} AS matchRank
            FROM current_epg_programmes
            WHERE current_epg_programmes.primaryTitle IS NOT NULL
              AND EXISTS (
                  SELECT 1 FROM hit_documents AS h
                  WHERE h.kind = '${SearchDocumentKind.EPG_PROGRAMME_TITLE}'
                    AND h.text = current_epg_programmes.primaryTitle
              )
        ),
        combined_candidates AS (
            SELECT canonicalChannelId, matchRank FROM direct_candidates
            UNION ALL
            SELECT canonicalChannelId, matchRank FROM epg_candidates
        )
        SELECT candidates.canonicalChannelId, MIN(candidates.matchRank)
        FROM combined_candidates AS candidates
        WHERE EXISTS (
            SELECT 1 FROM stream_variants
            INNER JOIN provider_channels ON provider_channels.id = stream_variants.providerChannelId
            INNER JOIN sources
                ON sources.id = provider_channels.sourceId
               AND sources.activeRevision = provider_channels.revisionNumber
            WHERE stream_variants.canonicalChannelId = candidates.canonicalChannelId
        )
          AND COALESCE((
              SELECT isHidden FROM user_channel_overlays
              WHERE profileId = '$profileId'
                AND canonicalChannelId = candidates.canonicalChannelId
              LIMIT 1
          ), 0) = 0
          AND (
              $restrictionEnabled = 0
              OR candidates.canonicalChannelId IN ($restrictedIds)
          )
        GROUP BY candidates.canonicalChannelId
        ORDER BY candidates.canonicalChannelId COLLATE BINARY
        LIMIT ${probe.fetchLimit}
        """.trimIndent()
    }

    private fun List<String>.toSqlIds(): String {
        require(isNotEmpty())
        return joinToString(",") { id ->
            require(id.isNotBlank())
            id.toSqlLiteral()
        }
    }

    private fun String.toSqlLiteral(): String = "'${replace("'", "''")}'"

    private const val RESTRICTION_SENTINEL = "__mux_search_no_restriction__"

    private fun summaryQuery(profileId: String, ids: String): String =
        """
        SELECT canonical_channels.id,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName),
               MIN(provider_channels.logoUrl), MIN(provider_channels.groupTitle),
               COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT),
                        MIN(provider_channels.channelNumber)),
               COALESCE(user_channel_overlays.isFavorite, 0),
               COUNT(DISTINCT stream_variants.id)
        FROM canonical_channels
        INNER JOIN stream_variants ON stream_variants.canonicalChannelId = canonical_channels.id
        INNER JOIN provider_channels ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources ON sources.id = provider_channels.sourceId
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = '$profileId'
           AND user_channel_overlays.canonicalChannelId = canonical_channels.id
        WHERE canonical_channels.id IN ($ids)
          AND provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        GROUP BY canonical_channels.id, canonical_channels.displayName,
                 user_channel_overlays.customName, user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        ORDER BY canonical_channels.id COLLATE BINARY
        """.trimIndent()

    private fun nowNextMatchCountQuery(profileId: String, ids: String): String =
        """
        SELECT matches.canonicalChannelId, COUNT(*)
        FROM epg_channel_matches AS matches
        INNER JOIN epg_sources
            ON epg_sources.id = matches.epgSourceId
           AND epg_sources.activeRevision = matches.epgRevisionNumber
           AND epg_sources.providerSourceId = matches.providerSourceId
        INNER JOIN sources
            ON sources.id = matches.providerSourceId
           AND sources.activeRevision = matches.catalogRevisionNumber
        LEFT JOIN user_channel_overlays AS overlay
            ON overlay.profileId = '$profileId'
           AND overlay.canonicalChannelId = matches.canonicalChannelId
        WHERE matches.matchPolicyVersion = $CURRENT_EPG_MATCH_POLICY_VERSION
          AND matches.decision = 'MATCHED'
          AND matches.canonicalChannelId IN ($ids)
          AND COALESCE(overlay.isHidden, 0) = 0
          AND EXISTS (
              SELECT 1 FROM stream_variants AS active_stream_variants
              INNER JOIN provider_channels AS active_provider_channels
                  ON active_provider_channels.id = active_stream_variants.providerChannelId
              INNER JOIN sources AS active_sources
                  ON active_sources.id = active_provider_channels.sourceId
              WHERE active_stream_variants.canonicalChannelId = matches.canonicalChannelId
                AND active_provider_channels.revisionNumber = active_sources.activeRevision
          )
        GROUP BY matches.canonicalChannelId
        """.trimIndent()

    private fun nowNextProgrammeQuery(profileId: String, now: Long, ids: String): String =
        """
        SELECT matches.canonicalChannelId, programme.startEpochMillis,
               programme.stopEpochMillis, programme.primaryTitle
        FROM epg_channel_matches AS matches
        INNER JOIN epg_sources
            ON epg_sources.id = matches.epgSourceId
           AND epg_sources.activeRevision = matches.epgRevisionNumber
           AND epg_sources.providerSourceId = matches.providerSourceId
        INNER JOIN sources
            ON sources.id = matches.providerSourceId
           AND sources.activeRevision = matches.catalogRevisionNumber
        INNER JOIN epg_programmes AS programme
            ON programme.sourceId = matches.epgSourceId
           AND programme.revisionNumber = matches.epgRevisionNumber
           AND programme.externalChannelId = matches.epgExternalChannelId
        LEFT JOIN user_channel_overlays AS overlay
            ON overlay.profileId = '$profileId'
           AND overlay.canonicalChannelId = matches.canonicalChannelId
        WHERE matches.matchPolicyVersion = $CURRENT_EPG_MATCH_POLICY_VERSION
          AND matches.decision = 'MATCHED'
          AND matches.canonicalChannelId IN ($ids)
          AND COALESCE(overlay.isHidden, 0) = 0
          AND EXISTS (
              SELECT 1 FROM stream_variants AS active_stream_variants
              INNER JOIN provider_channels AS active_provider_channels
                  ON active_provider_channels.id = active_stream_variants.providerChannelId
              INNER JOIN sources AS active_sources
                  ON active_sources.id = active_provider_channels.sourceId
              WHERE active_stream_variants.canonicalChannelId = matches.canonicalChannelId
                AND active_provider_channels.revisionNumber = active_sources.activeRevision
          )
          AND (
              programme.sequenceNumber = (
                  SELECT previous.sequenceNumber FROM epg_programmes AS previous
                  WHERE previous.sourceId = matches.epgSourceId
                    AND previous.revisionNumber = matches.epgRevisionNumber
                    AND previous.externalChannelId = matches.epgExternalChannelId
                    AND previous.startEpochMillis <= $now
                  ORDER BY previous.startEpochMillis DESC, previous.sequenceNumber DESC LIMIT 1
              )
              OR programme.sequenceNumber = (
                  SELECT following.sequenceNumber FROM epg_programmes AS following
                  WHERE following.sourceId = matches.epgSourceId
                    AND following.revisionNumber = matches.epgRevisionNumber
                    AND following.externalChannelId = matches.epgExternalChannelId
                    AND following.startEpochMillis > $now
                  ORDER BY following.startEpochMillis ASC, following.sequenceNumber ASC LIMIT 1
              )
          )
        ORDER BY matches.canonicalChannelId COLLATE BINARY, programme.startEpochMillis
        """.trimIndent()

    private fun globalBoundaryQuery(profileId: String, now: Long): String =
        """
        WITH active_matches AS (
            SELECT matches.epgSourceId, matches.epgRevisionNumber,
                   matches.epgExternalChannelId, matches.canonicalChannelId
            FROM epg_channel_matches AS matches
            INNER JOIN epg_sources
                ON epg_sources.id = matches.epgSourceId
               AND epg_sources.activeRevision = matches.epgRevisionNumber
               AND epg_sources.providerSourceId = matches.providerSourceId
            INNER JOIN sources
                ON sources.id = matches.providerSourceId
               AND sources.activeRevision = matches.catalogRevisionNumber
            WHERE matches.matchPolicyVersion = $CURRENT_EPG_MATCH_POLICY_VERSION
              AND matches.decision = 'MATCHED'
              AND matches.canonicalChannelId IS NOT NULL
        ),
        match_counts AS (
            SELECT canonicalChannelId, COUNT(*) AS matchCount
            FROM active_matches GROUP BY canonicalChannelId
        ),
        unambiguous AS (
            SELECT active_matches.* FROM active_matches
            INNER JOIN match_counts
                ON match_counts.canonicalChannelId = active_matches.canonicalChannelId
               AND match_counts.matchCount = 1
            LEFT JOIN user_channel_overlays AS overlay
                ON overlay.profileId = '$profileId'
               AND overlay.canonicalChannelId = active_matches.canonicalChannelId
            WHERE COALESCE(overlay.isHidden, 0) = 0
        ),
        boundaries AS (
            SELECT CASE
                WHEN previous.stopEpochMillis > $now THEN previous.stopEpochMillis
                WHEN previous.stopEpochMillis IS NULL THEN (
                    SELECT following.startEpochMillis FROM epg_programmes AS following
                    WHERE following.sourceId = unambiguous.epgSourceId
                      AND following.revisionNumber = unambiguous.epgRevisionNumber
                      AND following.externalChannelId = unambiguous.epgExternalChannelId
                      AND following.startEpochMillis > $now
                    ORDER BY following.startEpochMillis, following.sequenceNumber LIMIT 1
                )
            END AS boundaryEpochMillis
            FROM unambiguous
            LEFT JOIN epg_programmes AS previous
                ON previous.sourceId = unambiguous.epgSourceId
               AND previous.revisionNumber = unambiguous.epgRevisionNumber
               AND previous.externalChannelId = unambiguous.epgExternalChannelId
               AND previous.sequenceNumber = (
                   SELECT candidate.sequenceNumber FROM epg_programmes AS candidate
                   WHERE candidate.sourceId = unambiguous.epgSourceId
                     AND candidate.revisionNumber = unambiguous.epgRevisionNumber
                     AND candidate.externalChannelId = unambiguous.epgExternalChannelId
                     AND candidate.startEpochMillis <= $now
                   ORDER BY candidate.startEpochMillis DESC, candidate.sequenceNumber DESC LIMIT 1
               )
            UNION ALL
            SELECT (
                SELECT following.startEpochMillis FROM epg_programmes AS following
                WHERE following.sourceId = unambiguous.epgSourceId
                  AND following.revisionNumber = unambiguous.epgRevisionNumber
                  AND following.externalChannelId = unambiguous.epgExternalChannelId
                  AND following.startEpochMillis > $now
                ORDER BY following.startEpochMillis ASC, following.sequenceNumber ASC
                LIMIT 1
            ) AS boundaryEpochMillis
            FROM unambiguous
        )
        SELECT MIN(boundaryEpochMillis) FROM boundaries WHERE boundaryEpochMillis > $now
        """.trimIndent()
}

internal data class CatalogSearchCandidatePlanProbe(
    val ftsExpression: String,
    val fetchLimit: Int,
    val restrictedCanonicalIds: List<String>? = null,
) {
    init {
        require(ftsExpression.isNotBlank())
        require(fetchLimit in 1..ChannelSearchLimits.CANDIDATE_FETCH_LIMIT)
        require(
            restrictedCanonicalIds == null ||
                restrictedCanonicalIds.size in 1..ChannelSearchLimits.MAX_CANDIDATES_PER_TOKEN,
        )
        require(restrictedCanonicalIds?.none(String::isBlank) != false)
    }
}
