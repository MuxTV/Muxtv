package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "recent_channels",
    primaryKeys = ["profileId", "canonicalChannelId"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class RecentChannelEntity(
    val profileId: String,
    val canonicalChannelId: String,
    val lastSuccessfulPlaybackAtEpochMillis: Long,
) {
    init {
        require(profileId.isNotBlank())
        require(canonicalChannelId.isNotBlank())
        require(lastSuccessfulPlaybackAtEpochMillis >= 0L)
    }
}
