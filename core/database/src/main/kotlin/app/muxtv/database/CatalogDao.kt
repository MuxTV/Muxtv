package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCanonicalChannel(channel: CanonicalChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertOverlayRow(overlay: UserChannelOverlayEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSearchDocuments(documents: List<SearchDocumentEntity>)

    @Query(
        """
        DELETE FROM search_documents
        WHERE profileId = :profileId
          AND canonicalChannelId = :canonicalChannelId
          AND kind IN ('${SearchDocumentKind.OVERLAY_CUSTOM_NAME}', '${SearchDocumentKind.OVERLAY_NUMBER}')
        """,
    )
    protected abstract suspend fun deleteOverlaySearchDocuments(
        profileId: String,
        canonicalChannelId: String,
    ): Int

    @Transaction
    open suspend fun insertOverlay(overlay: UserChannelOverlayEntity) {
        insertOverlayRow(overlay)
        deleteOverlaySearchDocuments(
            profileId = overlay.profileId,
            canonicalChannelId = overlay.canonicalChannelId,
        )
        val searchDocuments = overlaySearchDocuments(overlay)
        if (searchDocuments.isNotEmpty()) insertSearchDocuments(searchDocuments)
    }

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
    abstract fun observeActiveCanonicalChannels(): Flow<List<CanonicalChannelEntity>>

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
    abstract suspend fun findActiveCanonicalChannel(channelId: String): CanonicalChannelEntity?

    @Query("SELECT COUNT(*) FROM canonical_channels")
    abstract suspend fun countCanonicalChannels(): Int

    @Query("SELECT COUNT(*) FROM user_channel_overlays WHERE profileId = :profileId")
    abstract suspend fun countOverlays(profileId: String): Int
}
