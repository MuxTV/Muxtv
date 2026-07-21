package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCanonicalChannel(channel: CanonicalChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverlay(overlay: UserChannelOverlayEntity)

    @Query(
        """
        SELECT DISTINCT canonical_channels.*
        FROM canonical_channels
        INNER JOIN stream_variants
            ON stream_variants.canonicalChannelId = canonical_channels.id
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources
            ON sources.id = provider_channels.sourceId
        WHERE provider_channels.revisionNumber = sources.activeRevision
        ORDER BY canonical_channels.displayName COLLATE NOCASE
        """,
    )
    fun observeActiveCanonicalChannels(): Flow<List<CanonicalChannelEntity>>

    @Query(
        """
        SELECT DISTINCT canonical_channels.*
        FROM canonical_channels
        INNER JOIN stream_variants
            ON stream_variants.canonicalChannelId = canonical_channels.id
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        INNER JOIN sources
            ON sources.id = provider_channels.sourceId
        WHERE provider_channels.revisionNumber = sources.activeRevision
          AND canonical_channels.id = :channelId
        LIMIT 1
        """,
    )
    suspend fun findActiveCanonicalChannel(channelId: String): CanonicalChannelEntity?

    @Query("SELECT COUNT(*) FROM canonical_channels")
    suspend fun countCanonicalChannels(): Int

    @Query("SELECT COUNT(*) FROM user_channel_overlays WHERE profileId = :profileId")
    suspend fun countOverlays(profileId: String): Int
}
