package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query

internal data class CatalogShadowActiveRow(
    val ordinal: Long,
    val variantId: String,
    val logicalChannelId: String,
    val payloadId: String,
    val contentHash: String,
    val canonicalChannelId: String,
    val providerKey: String,
    val rawName: String,
    val groupTitle: String?,
    val channelNumber: String?,
    val locator: String,
)

@Dao
internal interface CatalogShadowDao {
    @Query("SELECT COUNT(*) FROM catalog_payloads")
    suspend fun payloadCount(): Int

    @Query("SELECT COUNT(*) FROM catalog_search_payloads")
    suspend fun searchPayloadCount(): Int

    @Query("SELECT COUNT(*) FROM source_revision_memberships")
    suspend fun membershipCount(): Int

    @Query(
        """
        SELECT m.ordinal AS ordinal,
               m.variantId AS variantId,
               m.logicalChannelId AS logicalChannelId,
               p.payloadId AS payloadId,
               p.contentHash AS contentHash,
               p.canonicalChannelId AS canonicalChannelId,
               p.providerKey AS providerKey,
               p.rawName AS rawName,
               p.groupTitle AS groupTitle,
               p.channelNumber AS channelNumber,
               p.locator AS locator
        FROM sources AS s
        INNER JOIN source_revision_memberships AS m
            ON m.sourceId = s.id
           AND m.revisionNumber = s.activeRevision
        INNER JOIN catalog_payloads AS p
            ON p.payloadId = m.payloadId
        WHERE s.id = :sourceId
        ORDER BY m.ordinal
        """,
    )
    suspend fun activeRows(sourceId: String): List<CatalogShadowActiveRow>


    @Query(
        """
        SELECT canonical_channels.id AS channelId,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS displayName,
               MIN(p.logoUrl) AS logoUrl,
               MIN(p.groupTitle) AS groupTitle,
               COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(p.channelNumber)) AS channelNumber,
               COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite,
               COUNT(DISTINCT m.variantId) AS variantCount
        FROM canonical_channels
        INNER JOIN catalog_payloads AS p
            ON p.canonicalChannelId = canonical_channels.id
        INNER JOIN source_revision_memberships AS m
            ON m.payloadId = p.payloadId
        INNER JOIN sources AS s
            ON s.id = m.sourceId
           AND s.activeRevision = m.revisionNumber
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = canonical_channels.id
        WHERE COALESCE(user_channel_overlays.isHidden, 0) = 0
          AND (:favoritesOnly = 0 OR COALESCE(user_channel_overlays.isFavorite, 0) = 1)
          AND (
              :searchPattern IS NULL
              OR COALESCE(user_channel_overlays.customName, canonical_channels.displayName)
                    LIKE :searchPattern ESCAPE '\\'
              OR p.rawName LIKE :searchPattern ESCAPE '\\'
              OR p.groupTitle LIKE :searchPattern ESCAPE '\\'
          )
        GROUP BY canonical_channels.id,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        ORDER BY COALESCE(user_channel_overlays.channelNumber, 2147483647),
                 displayName COLLATE NOCASE,
                 canonical_channels.id
        LIMIT :limit
        """,
    )
    suspend fun activeChannelSummaries(
        profileId: String,
        searchPattern: String?,
        favoritesOnly: Boolean,
        limit: Int,
    ): List<ActiveChannelSummaryRow>

    @Query(
        """
        SELECT m.variantId AS variantId,
               s.id AS sourceId,
               s.name AS sourceName,
               s.credentialRef AS credentialRef,
               p.locator AS locator,
               p.userAgent AS userAgent,
               p.referrer AS referrer
        FROM source_revision_memberships AS m
        INNER JOIN catalog_payloads AS p
            ON p.payloadId = m.payloadId
        INNER JOIN sources AS s
            ON s.id = m.sourceId
           AND s.activeRevision = m.revisionNumber
        WHERE p.canonicalChannelId = :channelId
        ORDER BY s.name COLLATE NOCASE,
                 p.rawName COLLATE NOCASE,
                 m.variantId
        """,
    )
    suspend fun activeVariants(channelId: String): List<ActiveVariantRow>

    @Query(
        """
        SELECT p.canonicalChannelId AS channelId,
               m.variantId AS variantId
        FROM source_revision_memberships AS m
        INNER JOIN catalog_payloads AS p
            ON p.payloadId = m.payloadId
        INNER JOIN sources AS s
            ON s.id = m.sourceId
           AND s.activeRevision = m.revisionNumber
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = p.canonicalChannelId
        WHERE p.canonicalChannelId = :channelId
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        ORDER BY CASE WHEN m.variantId = :preferredVariantId THEN 0 ELSE 1 END,
                 s.name COLLATE NOCASE,
                 p.rawName COLLATE NOCASE,
                 m.variantId
        LIMIT :limit
        """,
    )
    suspend fun activeVariantIdentities(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
        limit: Int,
    ): List<ActiveVariantIdentityRow>

    @Query(
        """
        SELECT p.canonicalChannelId AS channelId,
               m.variantId AS variantId,
               s.credentialRef AS credentialRef,
               p.locator AS locator,
               p.userAgent AS userAgent,
               p.referrer AS referrer,
               p.catchupMode AS catchupMode,
               p.catchupSource AS catchupSource,
               p.catchupDays AS catchupDays,
               p.catchupCorrection AS catchupCorrection
        FROM source_revision_memberships AS m
        INNER JOIN catalog_payloads AS p
            ON p.payloadId = m.payloadId
        INNER JOIN sources AS s
            ON s.id = m.sourceId
           AND s.activeRevision = m.revisionNumber
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = p.canonicalChannelId
        WHERE p.canonicalChannelId = :channelId
          AND m.variantId = :variantId
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        LIMIT 1
        """,
    )
    suspend fun activeVariantAccess(
        profileId: String,
        channelId: String,
        variantId: String,
    ): ActiveVariantAccessRow?

    @Query(
        """
        SELECT recent_channels.canonicalChannelId AS channelId,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS displayName,
               MIN(p.logoUrl) AS logoUrl,
               MIN(p.groupTitle) AS groupTitle,
               COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(p.channelNumber)) AS channelNumber,
               COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite,
               COUNT(DISTINCT m.variantId) AS variantCount,
               recent_channels.lastSuccessfulPlaybackAtEpochMillis AS lastSuccessfulPlaybackAtEpochMillis
        FROM recent_channels
        INNER JOIN canonical_channels
            ON canonical_channels.id = recent_channels.canonicalChannelId
        INNER JOIN catalog_payloads AS p
            ON p.canonicalChannelId = canonical_channels.id
        INNER JOIN source_revision_memberships AS m
            ON m.payloadId = p.payloadId
        INNER JOIN sources AS s
            ON s.id = m.sourceId
           AND s.activeRevision = m.revisionNumber
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = recent_channels.profileId
           AND user_channel_overlays.canonicalChannelId = canonical_channels.id
        WHERE recent_channels.profileId = :profileId
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        GROUP BY recent_channels.profileId,
                 recent_channels.canonicalChannelId,
                 recent_channels.lastSuccessfulPlaybackAtEpochMillis,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        ORDER BY recent_channels.lastSuccessfulPlaybackAtEpochMillis DESC,
                 recent_channels.canonicalChannelId COLLATE BINARY ASC
        LIMIT :limit
        """,
    )
    suspend fun recentRows(
        profileId: String,
        limit: Int,
    ): List<RecentChannelRow>

    @Query(
        """
        SELECT COUNT(*)
        FROM source_revision_memberships
        WHERE sourceId = :sourceId
          AND revisionNumber = 0
        """,
    )
    suspend fun legacyRevisionZeroMembershipCount(sourceId: String): Int
}
