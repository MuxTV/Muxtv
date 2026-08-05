package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query

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

@Dao
internal interface GuideWindowDao {
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
    suspend fun channelWindow(
        profileId: String,
        afterHasChannelNumber: Boolean,
        afterChannelNumber: Int?,
        afterDisplayName: String?,
        afterCanonicalChannelId: String?,
        limit: Int,
    ): List<GuideChannelWindowRow>
}
