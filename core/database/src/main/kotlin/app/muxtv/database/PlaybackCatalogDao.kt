package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

internal data class ActiveChannelSummaryRow(
    val channelId: String,
    val displayName: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val isFavorite: Boolean,
    val variantCount: Int,
)

internal data class ActiveVariantRow(
    val variantId: String,
    val sourceId: String,
    val sourceName: String,
    val credentialRef: String?,
    val locator: String,
    val userAgent: String?,
    val referrer: String?,
)

internal data class ActiveVariantIdentityRow(
    val channelId: String,
    val variantId: String,
)

internal data class ActiveVariantAccessRow(
    val channelId: String,
    val variantId: String,
    val credentialRef: String?,
    val locator: String,
    val userAgent: String?,
    val referrer: String?,
    val catchupMode: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val catchupCorrection: String?,
) {
    override fun toString(): String =
        "ActiveVariantAccessRow(channelId=<redacted>, variantId=<redacted>, " +
            "credentialRefPresent=${credentialRef != null}, locator=<redacted>, " +
            "hasUserAgent=${userAgent != null}, hasReferrer=${referrer != null}, " +
            "catchupMetadataPresent=${
                catchupMode != null || catchupSource != null ||
                    catchupDays != null || catchupCorrection != null
            })"
}

@Dao
internal interface PlaybackCatalogDao {
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
        WHERE provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
          AND (:favoritesOnly = 0 OR COALESCE(user_channel_overlays.isFavorite, 0) = 1)
          AND (
              :searchPattern IS NULL
              OR COALESCE(user_channel_overlays.customName, canonical_channels.displayName) LIKE :searchPattern ESCAPE '\'
              OR provider_channels.rawName LIKE :searchPattern ESCAPE '\'
              OR provider_channels.groupTitle LIKE :searchPattern ESCAPE '\'
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
    fun observeActiveChannels(
        profileId: String,
        searchPattern: String?,
        favoritesOnly: Boolean,
        limit: Int,
    ): Flow<List<ActiveChannelSummaryRow>>

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
        WHERE provider_channels.revisionNumber = sources.activeRevision
          AND canonical_channels.id = :channelId
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        GROUP BY canonical_channels.id,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        LIMIT 1
        """,
    )
    suspend fun findActiveChannel(
        profileId: String,
        channelId: String,
    ): ActiveChannelSummaryRow?

    @Query(
        """
        SELECT stream_variants.id AS variantId,
               sources.id AS sourceId,
               sources.name AS sourceName,
               sources.credentialRef AS credentialRef,
               stream_variants.locator AS locator,
               stream_variants.userAgent AS userAgent,
               stream_variants.referrer AS referrer
        FROM stream_variants
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources
            ON sources.id = provider_channels.sourceId
        WHERE stream_variants.canonicalChannelId = :channelId
          AND provider_channels.revisionNumber = sources.activeRevision
        ORDER BY sources.name COLLATE NOCASE,
                 provider_channels.rawName COLLATE NOCASE,
                 stream_variants.id
        """,
    )
    suspend fun getActiveVariants(channelId: String): List<ActiveVariantRow>

    @Query(
        """
        SELECT stream_variants.canonicalChannelId AS channelId,
               stream_variants.id AS variantId
        FROM stream_variants
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources
            ON sources.id = provider_channels.sourceId
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = stream_variants.canonicalChannelId
        WHERE stream_variants.canonicalChannelId = :channelId
          AND provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        ORDER BY CASE WHEN stream_variants.id = :preferredVariantId THEN 0 ELSE 1 END,
                 sources.name COLLATE NOCASE,
                 provider_channels.rawName COLLATE NOCASE,
                 stream_variants.id
        LIMIT :limit
        """,
    )
    suspend fun getActiveVariantIdentities(
        profileId: String,
        channelId: String,
        preferredVariantId: String?,
        limit: Int,
    ): List<ActiveVariantIdentityRow>

    @Query(
        """
        SELECT stream_variants.canonicalChannelId AS channelId,
               stream_variants.id AS variantId,
               sources.credentialRef AS credentialRef,
               stream_variants.locator AS locator,
               stream_variants.userAgent AS userAgent,
               stream_variants.referrer AS referrer,
               provider_channels.catchupMode AS catchupMode,
               provider_channels.catchupSource AS catchupSource,
               provider_channels.catchupDays AS catchupDays,
               provider_channels.catchupCorrection AS catchupCorrection
        FROM stream_variants
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources
            ON sources.id = provider_channels.sourceId
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = :profileId
           AND user_channel_overlays.canonicalChannelId = stream_variants.canonicalChannelId
        WHERE stream_variants.canonicalChannelId = :channelId
          AND stream_variants.id = :variantId
          AND provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        LIMIT 1
        """,
    )
    suspend fun findActiveVariantAccess(
        profileId: String,
        channelId: String,
        variantId: String,
    ): ActiveVariantAccessRow?
}
