package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

internal data class RecentChannelRow(
    val channelId: String,
    val displayName: String,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val isFavorite: Boolean,
    val variantCount: Int,
    val lastSuccessfulPlaybackAtEpochMillis: Long,
)

internal enum class RecentWriteResult {
    Applied,
    IgnoredOlderOrDuplicate,
    TargetUnavailable,
}

@Dao
internal abstract class RecentChannelsDao {
    @Query(
        """
        SELECT recent_channels.canonicalChannelId AS channelId,
               COALESCE(user_channel_overlays.customName, canonical_channels.displayName) AS displayName,
               MIN(provider_channels.logoUrl) AS logoUrl,
               MIN(provider_channels.groupTitle) AS groupTitle,
               COALESCE(CAST(user_channel_overlays.channelNumber AS TEXT), MIN(provider_channels.channelNumber)) AS channelNumber,
               COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite,
               COUNT(DISTINCT stream_variants.id) AS variantCount,
               recent_channels.lastSuccessfulPlaybackAtEpochMillis AS lastSuccessfulPlaybackAtEpochMillis
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
                 recent_channels.canonicalChannelId COLLATE BINARY ASC
        LIMIT :limit
        """,
    )
    abstract fun observeRecent(
        profileId: String,
        limit: Int,
    ): Flow<List<RecentChannelRow>>

    @Query(
        """
        SELECT CASE WHEN EXISTS(
            SELECT 1 FROM profiles WHERE id = :profileId
        ) AND EXISTS(
            SELECT 1 FROM canonical_channels WHERE id = :channelId
        ) THEN 1 ELSE 0 END
        """,
    )
    protected abstract suspend fun targetExists(
        profileId: String,
        channelId: String,
    ): Boolean

    @Query(
        """
        UPDATE recent_channels
        SET lastSuccessfulPlaybackAtEpochMillis = :successfulAtEpochMillis
        WHERE profileId = :profileId
          AND canonicalChannelId = :channelId
          AND lastSuccessfulPlaybackAtEpochMillis < :successfulAtEpochMillis
        """,
    )
    protected abstract suspend fun updateIfNewer(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertIfAbsent(entity: RecentChannelEntity): Long

    @Query(
        """
        DELETE FROM recent_channels
        WHERE profileId = :profileId
          AND canonicalChannelId NOT IN (
              SELECT canonicalChannelId
              FROM recent_channels
              WHERE profileId = :profileId
              ORDER BY lastSuccessfulPlaybackAtEpochMillis DESC,
                       canonicalChannelId COLLATE BINARY ASC
              LIMIT :retentionLimit
          )
        """,
    )
    protected abstract suspend fun trimProfile(
        profileId: String,
        retentionLimit: Int,
    ): Int

    @Query("SELECT COUNT(*) FROM recent_channels WHERE profileId = :profileId")
    abstract suspend fun countForProfile(profileId: String): Int

    @Transaction
    open suspend fun recordSuccessfulPlayback(
        profileId: String,
        channelId: String,
        successfulAtEpochMillis: Long,
        retentionLimit: Int,
    ): RecentWriteResult {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())
        require(successfulAtEpochMillis >= 0L)
        require(retentionLimit > 0)

        if (!targetExists(profileId, channelId)) {
            return RecentWriteResult.TargetUnavailable
        }

        val applied = if (
            updateIfNewer(profileId, channelId, successfulAtEpochMillis) == 1
        ) {
            true
        } else {
            insertIfAbsent(
                RecentChannelEntity(
                    profileId = profileId,
                    canonicalChannelId = channelId,
                    lastSuccessfulPlaybackAtEpochMillis = successfulAtEpochMillis,
                ),
            ) != -1L
        }

        if (!applied) {
            return RecentWriteResult.IgnoredOlderOrDuplicate
        }

        trimProfile(profileId, retentionLimit)
        return RecentWriteResult.Applied
    }
}
