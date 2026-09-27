package app.muxtv.database

import androidx.paging.PagingSource
import androidx.room3.Dao
import androidx.room3.DaoReturnTypeConverters
import androidx.room3.Query
import androidx.room3.paging.PagingSourceDaoReturnTypeConverter

internal data class ActiveChannelBrowseRow(
    val channelId: String,
    val displayName: String,
    val groupTitle: String?,
    val channelNumber: String?,
    val isFavorite: Boolean,
    val variantCount: Int,
)

internal data class ActiveChannelManagementRow(
    val channelId: String,
    val canonicalDisplayName: String,
    val effectiveDisplayName: String,
    val defaultChannelNumber: String?,
    val customChannelNumber: Int?,
    val effectiveChannelNumber: String?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val variantCount: Int,
)

@Dao
@DaoReturnTypeConverters(PagingSourceDaoReturnTypeConverter::class)
internal interface ChannelBrowseDao {
    @Query(
        """
        SELECT canonical_channels.id AS channelId,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS displayName,
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
        GROUP BY canonical_channels.id,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        ORDER BY CASE
                     WHEN COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) <> ''
                      AND COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) NOT GLOB '*[^0-9]*'
                     THEN CAST(COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) AS INTEGER)
                     ELSE 2147483647
                 END,
                 displayName COLLATE NOCASE,
                 canonical_channels.id COLLATE BINARY
        """,
    )
    fun pageActiveChannels(
        profileId: String,
        favoritesOnly: Boolean,
    ): PagingSource<Int, ActiveChannelBrowseRow>

    @Query(
        """
        SELECT canonical_channels.id AS channelId,
               canonical_channels.displayName AS canonicalDisplayName,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS effectiveDisplayName,
               MIN(provider_channels.channelNumber) AS defaultChannelNumber,
               user_channel_overlays.channelNumber AS customChannelNumber,
               COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) AS effectiveChannelNumber,
               COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite,
               COALESCE(user_channel_overlays.isHidden, 0) AS isHidden,
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
          AND (:hiddenState IS NULL OR COALESCE(user_channel_overlays.isHidden, 0) = :hiddenState)
        GROUP BY canonical_channels.id,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite,
                 user_channel_overlays.isHidden
        ORDER BY CASE
                     WHEN COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) <> ''
                      AND COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) NOT GLOB '*[^0-9]*'
                     THEN CAST(COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) AS INTEGER)
                     ELSE 2147483647
                 END,
                 effectiveDisplayName COLLATE NOCASE,
                 canonical_channels.id COLLATE BINARY
        """,
    )
    fun pageManagedChannels(
        profileId: String,
        hiddenState: Int?,
    ): PagingSource<Int, ActiveChannelManagementRow>

    @Query(
        """
        SELECT recent_channels.canonicalChannelId AS channelId,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS displayName,
               MIN(provider_channels.groupTitle) AS groupTitle,
               COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) AS channelNumber,
               COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite,
               COUNT(DISTINCT stream_variants.id) AS variantCount
        FROM recent_channels
        INNER JOIN canonical_channels
            ON canonical_channels.id = recent_channels.canonicalChannelId
        INNER JOIN stream_variants
            ON stream_variants.canonicalChannelId = canonical_channels.id
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources
            ON sources.id = provider_channels.sourceId
        LEFT JOIN user_channel_overlays
            ON user_channel_overlays.profileId = recent_channels.profileId
           AND user_channel_overlays.canonicalChannelId = canonical_channels.id
        WHERE recent_channels.profileId = :profileId
          AND provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        GROUP BY recent_channels.profileId,
                 recent_channels.canonicalChannelId,
                 recent_channels.lastSuccessfulPlaybackAtEpochMillis,
                 canonical_channels.displayName,
                 user_channel_overlays.customName,
                 user_channel_overlays.channelNumber,
                 user_channel_overlays.isFavorite
        ORDER BY recent_channels.lastSuccessfulPlaybackAtEpochMillis DESC,
                 recent_channels.canonicalChannelId COLLATE BINARY
        """,
    )
    fun pageRecentChannels(profileId: String): PagingSource<Int, ActiveChannelBrowseRow>
}
