package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "user_channel_overlays",
    primaryKeys = ["profileId", "canonicalChannelId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CanonicalChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonicalChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["canonicalChannelId"])],
)
data class UserChannelOverlayEntity(
    val profileId: String,
    val canonicalChannelId: String,
    val isFavorite: Boolean = false,
    val customName: String? = null,
    val channelNumber: Int? = null,
    val isHidden: Boolean = false,
)
