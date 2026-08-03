package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query

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
internal abstract class ChannelSearchDao {
    suspend fun searchCandidates(
        profileId: String,
        ftsExpression: String,
        nowEpochMillis: Long,
        fetchLimit: Int,
        matchPolicyVersion: Int = CURRENT_EPG_MATCH_POLICY_VERSION,
    ): List<ChannelSearchCandidateRow> {
        require(profileId.isNotBlank())
        require(ftsExpression.isNotBlank())
        require(nowEpochMillis >= 0)
        require(fetchLimit in 1..MAX_CANDIDATE_FETCH_LIMIT)
        require(matchPolicyVersion >= LEGACY_UNVERSIONED_MATCH_POLICY_VERSION)
        return selectCandidates(
            profileId = profileId,
            ftsExpression = ftsExpression,
            nowEpochMillis = nowEpochMillis,
            fetchLimit = fetchLimit,
            matchPolicyVersion = matchPolicyVersion,
        )
    }

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
    ): List<ChannelSearchCandidateRow>

    private companion object {
        const val MAX_CANDIDATE_FETCH_LIMIT = 801
    }
}
