package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "provider_channels",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId"])],
)
data class ProviderChannelEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val providerKey: String,
    val rawName: String,
)
