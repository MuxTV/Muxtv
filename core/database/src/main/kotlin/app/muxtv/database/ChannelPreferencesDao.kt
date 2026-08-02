package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

internal data class ActiveChannelFavoriteRow(
    val isFavorite: Boolean,
)

internal enum class FavoriteWriteResult {
    Applied,
    Unchanged,
    NotFound,
}

@Dao
internal abstract class ChannelPreferencesDao {
    @Query(
        """
        SELECT COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite
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
        WHERE canonical_channels.id = :channelId
          AND provider_channels.revisionNumber = sources.activeRevision
          AND COALESCE(user_channel_overlays.isHidden, 0) = 0
        LIMIT 1
        """,
    )
    protected abstract suspend fun findActiveFavorite(
        profileId: String,
        channelId: String,
    ): ActiveChannelFavoriteRow?

    @Query(
        """
        UPDATE user_channel_overlays
        SET isFavorite = :isFavorite
        WHERE profileId = :profileId
          AND canonicalChannelId = :channelId
        """,
    )
    protected abstract suspend fun updateFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOverlay(overlay: UserChannelOverlayEntity)

    @Transaction
    open suspend fun setFavorite(
        profileId: String,
        channelId: String,
        isFavorite: Boolean,
    ): FavoriteWriteResult {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())

        val current = findActiveFavorite(profileId, channelId)
            ?: return FavoriteWriteResult.NotFound
        if (current.isFavorite == isFavorite) {
            return FavoriteWriteResult.Unchanged
        }

        if (updateFavorite(profileId, channelId, isFavorite) == 0) {
            insertOverlay(
                UserChannelOverlayEntity(
                    profileId = profileId,
                    canonicalChannelId = channelId,
                    isFavorite = isFavorite,
                ),
            )
        }
        return FavoriteWriteResult.Applied
    }
}
