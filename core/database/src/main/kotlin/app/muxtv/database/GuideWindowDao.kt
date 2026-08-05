package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction

internal data class GuideChannelWindowRow(
    val cursorChannelNumber: Int?,
    val channelId: String,
    val displayName: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val isFavorite: Boolean,
    val variantCount: Int,
)

internal data class GuideProgrammeMatchCountRow(
    val canonicalChannelId: String,
    val matchCount: Long,
)

internal data class GuideProgrammeWindowRow(
    val canonicalChannelId: String,
    val epgSourceId: String,
    val epgRevisionNumber: Long,
    val sequenceNumber: Long,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val primaryTitle: String?,
)

internal data class GuideProgrammeWindowSnapshot(
    val matchCounts: List<GuideProgrammeMatchCountRow>,
    val programmeRows: List<GuideProgrammeWindowRow>,
)

@Dao
internal abstract class GuideWindowDao {
    @Query(
        """
        SELECT user_channel_overlays.channelNumber AS cursorChannelNumber,
               canonical_channels.id AS channelId,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS displayName,
               MIN(provider_channels.logoUrl) AS logoUrl,
               MIN(provider_channels.groupTitle) AS groupTitle,
               COALESCE(
                   CAST(user_channel_overlays.channelNumber AS TEXT),
                   MIN(provider_channels.channelNumber)
               ) AS channelNumber,
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
        WHERE provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
          AND (
              :afterCanonicalChannelId IS NULL
              OR (
                  :afterHasChannelNumber = 1
                  AND (
                      user_channel_overlays.channelNumber IS NULL
                      OR user_channel_overlays.channelNumber > :afterChannelNumber
                      OR (
                          user_channel_overlays.channelNumber = :afterChannelNumber
                          AND (
                              COALESCE(
                                  user_channel_overlays.customName,
                                  canonical_channels.displayName
                              ) COLLATE NOCASE > :afterDisplayName COLLATE NOCASE
                              OR (
                                  COALESCE(
                                      user_channel_overlays.customName,
                                      canonical_channels.displayName
                                  ) COLLATE NOCASE = :afterDisplayName COLLATE NOCASE
                                  AND canonical_channels.id COLLATE BINARY >
                                      :afterCanonicalChannelId COLLATE BINARY
                              )
                          )
                      )
                  )
              )
              OR (
                  :afterHasChannelNumber = 0
                  AND user_channel_overlays.channelNumber IS NULL
                  AND (
                      COALESCE(
                          user_channel_overlays.customName,
                          canonical_channels.displayName
                      ) COLLATE NOCASE > :afterDisplayName COLLATE NOCASE
                      OR (
                          COALESCE(
                              user_channel_overlays.customName,
                              canonical_channels.displayName
                          ) COLLATE NOCASE = :afterDisplayName COLLATE NOCASE
                          AND canonical_channels.id COLLATE BINARY >
                              :afterCanonicalChannelId COLLATE BINARY
                      )
                  )
              )
          )
        GROUP BY canonical_channels.id,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        ORDER BY CASE
                     WHEN user_channel_overlays.channelNumber IS NULL THEN 1
                     ELSE 0
                 END ASC,
                 user_channel_overlays.channelNumber ASC,
                 displayName COLLATE NOCASE ASC,
                 canonical_channels.id COLLATE BINARY ASC
        LIMIT :limit
        """,
    )
    abstract suspend fun channelWindow(
        profileId: String,
        afterHasChannelNumber: Boolean,
        afterChannelNumber: Int?,
        afterDisplayName: String?,
        afterCanonicalChannelId: String?,
        limit: Int,
    ): List<GuideChannelWindowRow>

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
        WHERE epg_channel_matches.matchPolicyVersion = :matchPolicyVersion
          AND epg_channel_matches.decision = 'MATCHED'
          AND epg_channel_matches.canonicalChannelId IS NOT NULL
          AND epg_channel_matches.canonicalChannelId IN (:canonicalChannelIds)
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
          AND EXISTS (
              SELECT 1
              FROM stream_variants
              INNER JOIN provider_channels
                  ON provider_channels.id = stream_variants.providerChannelId
              INNER JOIN sources AS active_variant_sources
                  ON active_variant_sources.id = provider_channels.sourceId
              WHERE stream_variants.canonicalChannelId =
                        epg_channel_matches.canonicalChannelId
                AND provider_channels.revisionNumber = active_variant_sources.activeRevision
          )
        GROUP BY epg_channel_matches.canonicalChannelId
        """,
    )
    protected abstract suspend fun activeMatchCounts(
        profileId: String,
        canonicalChannelIds: List<String>,
        matchPolicyVersion: Int,
    ): List<GuideProgrammeMatchCountRow>

    @Query(
        """
        SELECT epg_channel_matches.canonicalChannelId AS canonicalChannelId,
               epg_programmes.sourceId AS epgSourceId,
               epg_programmes.revisionNumber AS epgRevisionNumber,
               epg_programmes.sequenceNumber AS sequenceNumber,
               epg_programmes.startEpochMillis AS startEpochMillis,
               CASE
                   WHEN epg_programmes.stopEpochMillis IS NOT NULL
                        AND epg_programmes.stopEpochMillis > epg_programmes.startEpochMillis
                       THEN epg_programmes.stopEpochMillis
                   WHEN epg_programmes.stopEpochMillis IS NULL
                       THEN (
                           SELECT MIN(next_programme.startEpochMillis)
                           FROM epg_programmes AS next_programme
                           WHERE next_programme.sourceId = epg_programmes.sourceId
                             AND next_programme.revisionNumber = epg_programmes.revisionNumber
                             AND next_programme.externalChannelId =
                                 epg_programmes.externalChannelId
                             AND next_programme.startEpochMillis >
                                 epg_programmes.startEpochMillis
                       )
                   ELSE NULL
               END AS endEpochMillis,
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
        WHERE epg_channel_matches.matchPolicyVersion = :matchPolicyVersion
          AND epg_channel_matches.decision = 'MATCHED'
          AND epg_channel_matches.canonicalChannelId IS NOT NULL
          AND epg_channel_matches.canonicalChannelId IN (:canonicalChannelIds)
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
          AND EXISTS (
              SELECT 1
              FROM stream_variants
              INNER JOIN provider_channels
                  ON provider_channels.id = stream_variants.providerChannelId
              INNER JOIN sources AS active_variant_sources
                  ON active_variant_sources.id = provider_channels.sourceId
              WHERE stream_variants.canonicalChannelId =
                        epg_channel_matches.canonicalChannelId
                AND provider_channels.revisionNumber = active_variant_sources.activeRevision
          )
          AND epg_programmes.startEpochMillis < :toEpochMillis
          AND CASE
                  WHEN epg_programmes.stopEpochMillis IS NOT NULL
                       AND epg_programmes.stopEpochMillis > epg_programmes.startEpochMillis
                      THEN epg_programmes.stopEpochMillis
                  WHEN epg_programmes.stopEpochMillis IS NULL
                      THEN (
                          SELECT MIN(next_programme.startEpochMillis)
                          FROM epg_programmes AS next_programme
                          WHERE next_programme.sourceId = epg_programmes.sourceId
                            AND next_programme.revisionNumber = epg_programmes.revisionNumber
                            AND next_programme.externalChannelId =
                                epg_programmes.externalChannelId
                            AND next_programme.startEpochMillis >
                                epg_programmes.startEpochMillis
                      )
                  ELSE NULL
              END > :fromEpochMillis
        ORDER BY epg_channel_matches.canonicalChannelId COLLATE BINARY ASC,
                 epg_programmes.startEpochMillis ASC,
                 epg_programmes.sourceId COLLATE BINARY ASC,
                 epg_programmes.revisionNumber ASC,
                 epg_programmes.sequenceNumber ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun programmeRows(
        profileId: String,
        canonicalChannelIds: List<String>,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        matchPolicyVersion: Int,
        limit: Int,
    ): List<GuideProgrammeWindowRow>

    @Transaction
    open suspend fun programmeWindowSnapshot(
        profileId: String,
        canonicalChannelIds: List<String>,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        limit: Int,
    ): GuideProgrammeWindowSnapshot {
        if (canonicalChannelIds.isEmpty()) {
            return GuideProgrammeWindowSnapshot(emptyList(), emptyList())
        }
        val counts = activeMatchCounts(
            profileId = profileId,
            canonicalChannelIds = canonicalChannelIds,
            matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
        )
        val singleMatchIds = counts
            .asSequence()
            .filter { count -> count.matchCount == 1L }
            .mapTo(mutableListOf()) { count -> count.canonicalChannelId }
        val rows = if (singleMatchIds.isEmpty()) {
            emptyList()
        } else {
            programmeRows(
                profileId = profileId,
                canonicalChannelIds = singleMatchIds,
                fromEpochMillis = fromEpochMillis,
                toEpochMillis = toEpochMillis,
                matchPolicyVersion = CURRENT_EPG_MATCH_POLICY_VERSION,
                limit = limit,
            )
        }
        return GuideProgrammeWindowSnapshot(
            matchCounts = counts,
            programmeRows = rows,
        )
    }
}
