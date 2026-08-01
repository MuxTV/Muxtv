package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import app.muxtv.catalog.NowNextQuery

internal data class EpgGuideMatchCountRow(
    val canonicalChannelId: String,
    val matchCount: Long,
) {
    init {
        require(canonicalChannelId.isNotBlank())
        require(matchCount >= 0)
    }

    override fun toString(): String =
        "EpgGuideMatchCountRow(matchCount=$matchCount)"
}

internal data class EpgGuideProgrammeCandidateRow(
    val canonicalChannelId: String,
    val startEpochMillis: Long,
    val stopEpochMillis: Long?,
    val primaryTitle: String?,
) {
    init {
        require(canonicalChannelId.isNotBlank())
        require(startEpochMillis >= 0)
        require(stopEpochMillis == null || stopEpochMillis >= startEpochMillis)
    }

    override fun toString(): String =
        "EpgGuideProgrammeCandidateRow(startEpochMillis=$startEpochMillis, " +
            "stopPresent=${stopEpochMillis != null}, titlePresent=${primaryTitle != null})"
}

internal data class EpgGuideProjectionSnapshot(
    val matchCounts: List<EpgGuideMatchCountRow>,
    val programmeCandidates: List<EpgGuideProgrammeCandidateRow>,
)

@Dao
internal abstract class EpgGuideDao {
    @Query(
        """
        SELECT epg_channel_matches.canonicalChannelId AS canonicalChannelId,
               COUNT(*) AS matchCount
        FROM epg_channel_matches
        INNER JOIN epg_sources
            ON epg_sources.id = epg_channel_matches.epgSourceId
           AND epg_sources.activeRevision = epg_channel_matches.epgRevisionNumber
           AND epg_sources.providerSourceId = epg_channel_matches.providerSourceId
        INNER JOIN sources
            ON sources.id = epg_channel_matches.providerSourceId
           AND sources.activeRevision = epg_channel_matches.catalogRevisionNumber
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = epg_channel_matches.canonicalChannelId
        WHERE epg_channel_matches.decision = 'MATCHED'
          AND epg_channel_matches.canonicalChannelId IS NOT NULL
          AND epg_channel_matches.canonicalChannelId IN (:canonicalChannelIds)
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        GROUP BY epg_channel_matches.canonicalChannelId
        """,
    )
    protected abstract suspend fun activeMatchCounts(
        profileId: String,
        canonicalChannelIds: List<String>,
    ): List<EpgGuideMatchCountRow>

    @Query(
        """
        SELECT epg_channel_matches.canonicalChannelId AS canonicalChannelId,
               epg_programmes.startEpochMillis AS startEpochMillis,
               epg_programmes.stopEpochMillis AS stopEpochMillis,
               epg_programmes.primaryTitle AS primaryTitle
        FROM epg_channel_matches
        INNER JOIN epg_sources
            ON epg_sources.id = epg_channel_matches.epgSourceId
           AND epg_sources.activeRevision = epg_channel_matches.epgRevisionNumber
           AND epg_sources.providerSourceId = epg_channel_matches.providerSourceId
        INNER JOIN sources
            ON sources.id = epg_channel_matches.providerSourceId
           AND sources.activeRevision = epg_channel_matches.catalogRevisionNumber
        INNER JOIN epg_programmes
            ON epg_programmes.sourceId = epg_channel_matches.epgSourceId
           AND epg_programmes.revisionNumber = epg_channel_matches.epgRevisionNumber
           AND epg_programmes.externalChannelId = epg_channel_matches.epgExternalChannelId
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = epg_channel_matches.canonicalChannelId
        WHERE epg_channel_matches.decision = 'MATCHED'
          AND epg_channel_matches.canonicalChannelId IS NOT NULL
          AND epg_channel_matches.canonicalChannelId IN (:canonicalChannelIds)
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
          AND (
              epg_programmes.sequenceNumber = (
                  SELECT previous_programme.sequenceNumber
                  FROM epg_programmes AS previous_programme
                  WHERE previous_programme.sourceId = epg_channel_matches.epgSourceId
                    AND previous_programme.revisionNumber = epg_channel_matches.epgRevisionNumber
                    AND previous_programme.externalChannelId = epg_channel_matches.epgExternalChannelId
                    AND previous_programme.startEpochMillis <= :nowEpochMillis
                  ORDER BY previous_programme.startEpochMillis DESC,
                           previous_programme.sequenceNumber DESC
                  LIMIT 1
              )
              OR epg_programmes.sequenceNumber = (
                  SELECT next_programme.sequenceNumber
                  FROM epg_programmes AS next_programme
                  WHERE next_programme.sourceId = epg_channel_matches.epgSourceId
                    AND next_programme.revisionNumber = epg_channel_matches.epgRevisionNumber
                    AND next_programme.externalChannelId = epg_channel_matches.epgExternalChannelId
                    AND next_programme.startEpochMillis > :nowEpochMillis
                  ORDER BY next_programme.startEpochMillis ASC,
                           next_programme.sequenceNumber ASC
                  LIMIT 1
              )
          )
        ORDER BY epg_channel_matches.canonicalChannelId COLLATE BINARY ASC,
                 epg_programmes.startEpochMillis ASC
        """,
    )
    protected abstract suspend fun programmeCandidates(
        profileId: String,
        canonicalChannelIds: List<String>,
        nowEpochMillis: Long,
    ): List<EpgGuideProgrammeCandidateRow>

    @Transaction
    open suspend fun projectionSnapshot(
        profileId: String,
        canonicalChannelIds: List<String>,
        nowEpochMillis: Long,
    ): EpgGuideProjectionSnapshot {
        require(profileId.isNotBlank())
        require(nowEpochMillis >= 0)
        require(canonicalChannelIds.size <= NowNextQuery.MAX_CHANNEL_IDS)
        require(canonicalChannelIds.none(String::isBlank))
        require(canonicalChannelIds.distinct().size == canonicalChannelIds.size)
        if (canonicalChannelIds.isEmpty()) {
            return EpgGuideProjectionSnapshot(emptyList(), emptyList())
        }

        val counts = activeMatchCounts(profileId, canonicalChannelIds)
        val singleMatchIds = counts
            .asSequence()
            .filter { it.matchCount == 1L }
            .mapTo(mutableListOf()) { it.canonicalChannelId }
        val candidates = if (singleMatchIds.isEmpty()) {
            emptyList()
        } else {
            programmeCandidates(
                profileId = profileId,
                canonicalChannelIds = singleMatchIds,
                nowEpochMillis = nowEpochMillis,
            )
        }
        return EpgGuideProjectionSnapshot(
            matchCounts = counts,
            programmeCandidates = candidates,
        )
    }
}
