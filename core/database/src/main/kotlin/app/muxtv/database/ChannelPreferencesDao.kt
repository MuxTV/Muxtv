package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

internal data class ActiveChannelPreferenceRow(
    val hasOverlay: Boolean,
    val isFavorite: Boolean,
    val customName: String?,
    val channelNumber: Int?,
    val isHidden: Boolean,
)

internal enum class FavoriteWriteResult {
    Applied,
    Unchanged,
    NotFound,
}

internal enum class PreferenceWriteResult {
    Applied,
    Unchanged,
    NotFound,
    InvalidInput,
}

@Dao
internal abstract class ChannelPreferencesDao {
    @Query(
        """
        SELECT
            CASE WHEN user_channel_overlays.canonicalChannelId IS NULL THEN 0 ELSE 1 END AS hasOverlay,
            COALESCE(user_channel_overlays.isFavorite, 0) AS isFavorite,
            user_channel_overlays.customName AS customName,
            user_channel_overlays.channelNumber AS channelNumber,
            COALESCE(user_channel_overlays.isHidden, 0) AS isHidden
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
        LIMIT 1
        """,
    )
    protected abstract suspend fun findActivePreferences(
        profileId: String,
        channelId: String,
    ): ActiveChannelPreferenceRow?

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

    @Query(
        """
        UPDATE user_channel_overlays
        SET isHidden = :isHidden
        WHERE profileId = :profileId
          AND canonicalChannelId = :channelId
        """,
    )
    protected abstract suspend fun updateHidden(
        profileId: String,
        channelId: String,
        isHidden: Boolean,
    ): Int

    @Query(
        """
        UPDATE user_channel_overlays
        SET customName = :customName
        WHERE profileId = :profileId
          AND canonicalChannelId = :channelId
        """,
    )
    protected abstract suspend fun updateCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): Int

    @Query(
        """
        UPDATE user_channel_overlays
        SET channelNumber = :channelNumber
        WHERE profileId = :profileId
          AND canonicalChannelId = :channelId
        """,
    )
    protected abstract suspend fun updateChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): Int

    @Query(
        """
        UPDATE user_channel_overlays
        SET customName = NULL,
            channelNumber = NULL,
            isHidden = 0
        WHERE profileId = :profileId
          AND canonicalChannelId = :channelId
        """,
    )
    protected abstract suspend fun resetPresentation(
        profileId: String,
        channelId: String,
    ): Int

    @Query(
        """
        DELETE FROM user_channel_overlays
        WHERE profileId = :profileId
          AND canonicalChannelId = :channelId
          AND isFavorite = 0
          AND customName IS NULL
          AND channelNumber IS NULL
          AND isHidden = 0
        """,
    )
    protected abstract suspend fun deleteDefaultOverlay(
        profileId: String,
        channelId: String,
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

        val current = findActivePreferences(profileId, channelId)
            ?: return FavoriteWriteResult.NotFound
        if (current.isHidden) {
            return FavoriteWriteResult.NotFound
        }
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

    @Transaction
    open suspend fun setHidden(
        profileId: String,
        channelId: String,
        isHidden: Boolean,
    ): PreferenceWriteResult {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())

        val current = findActivePreferences(profileId, channelId)
            ?: return PreferenceWriteResult.NotFound
        if (current.isHidden == isHidden) {
            return PreferenceWriteResult.Unchanged
        }

        if (updateHidden(profileId, channelId, isHidden) == 0) {
            insertOverlay(
                UserChannelOverlayEntity(
                    profileId = profileId,
                    canonicalChannelId = channelId,
                    isHidden = isHidden,
                ),
            )
        } else if (!isHidden) {
            deleteDefaultOverlay(profileId, channelId)
        }
        return PreferenceWriteResult.Applied
    }

    @Transaction
    open suspend fun setCustomName(
        profileId: String,
        channelId: String,
        customName: String?,
    ): PreferenceWriteResult {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())

        val normalizedName = normalizeCustomChannelName(customName)
            ?: if (customName == null) null else return PreferenceWriteResult.InvalidInput
        val current = findActivePreferences(profileId, channelId)
            ?: return PreferenceWriteResult.NotFound
        if (current.customName == normalizedName) {
            return PreferenceWriteResult.Unchanged
        }

        if (updateCustomName(profileId, channelId, normalizedName) == 0) {
            insertOverlay(
                UserChannelOverlayEntity(
                    profileId = profileId,
                    canonicalChannelId = channelId,
                    customName = normalizedName,
                ),
            )
        } else if (normalizedName == null) {
            deleteDefaultOverlay(profileId, channelId)
        }
        return PreferenceWriteResult.Applied
    }

    @Transaction
    open suspend fun setChannelNumber(
        profileId: String,
        channelId: String,
        channelNumber: Int?,
    ): PreferenceWriteResult {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())

        if (channelNumber != null && channelNumber !in MIN_CUSTOM_CHANNEL_NUMBER..MAX_CUSTOM_CHANNEL_NUMBER) {
            return PreferenceWriteResult.InvalidInput
        }
        val current = findActivePreferences(profileId, channelId)
            ?: return PreferenceWriteResult.NotFound
        if (current.channelNumber == channelNumber) {
            return PreferenceWriteResult.Unchanged
        }

        if (updateChannelNumber(profileId, channelId, channelNumber) == 0) {
            insertOverlay(
                UserChannelOverlayEntity(
                    profileId = profileId,
                    canonicalChannelId = channelId,
                    channelNumber = channelNumber,
                ),
            )
        } else if (channelNumber == null) {
            deleteDefaultOverlay(profileId, channelId)
        }
        return PreferenceWriteResult.Applied
    }

    @Transaction
    open suspend fun resetCustomization(
        profileId: String,
        channelId: String,
    ): PreferenceWriteResult {
        require(profileId.isNotBlank())
        require(channelId.isNotBlank())

        val current = findActivePreferences(profileId, channelId)
            ?: return PreferenceWriteResult.NotFound
        if (current.customName == null && current.channelNumber == null && !current.isHidden) {
            return PreferenceWriteResult.Unchanged
        }

        resetPresentation(profileId, channelId)
        if (!current.isFavorite) {
            deleteDefaultOverlay(profileId, channelId)
        }
        return PreferenceWriteResult.Applied
    }
}

private fun normalizeCustomChannelName(value: String?): String? {
    if (value == null) return null
    val normalized = value.trim()
    if (normalized.isEmpty()) return null
    if (normalized.codePointCount(0, normalized.length) > MAX_CUSTOM_CHANNEL_NAME_CODE_POINTS) return null
    if (normalized.any(Char::isISOControl)) return null
    return normalized
}

private const val MAX_CUSTOM_CHANNEL_NAME_CODE_POINTS = 128
private const val MIN_CUSTOM_CHANNEL_NUMBER = 1
private const val MAX_CUSTOM_CHANNEL_NUMBER = 9_999
