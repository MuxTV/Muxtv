package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "stream_variants",
    foreignKeys = [
        ForeignKey(
            entity = ProviderChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["providerChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CanonicalChannelEntity::class,
            parentColumns = ["id"],
            childColumns = ["canonicalChannelId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["providerChannelId"]), Index(value = ["canonicalChannelId"])],
)
data class StreamVariantEntity(
    @PrimaryKey val id: String,
    val providerChannelId: String,
    val canonicalChannelId: String,
    val locator: String,
)
