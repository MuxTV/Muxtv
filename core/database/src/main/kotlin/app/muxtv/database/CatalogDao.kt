package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCanonicalChannel(channel: CanonicalChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverlay(overlay: UserChannelOverlayEntity)

    @Query("SELECT COUNT(*) FROM canonical_channels")
    suspend fun countCanonicalChannels(): Int

    @Query("SELECT COUNT(*) FROM user_channel_overlays WHERE profileId = :profileId")
    suspend fun countOverlays(profileId: String): Int
}
