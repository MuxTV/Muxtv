package app.muxtv.database

import androidx.room3.ColumnInfo
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
    indices = [
        Index(value = ["sourceId"]),
        Index(value = ["sourceId", "revisionNumber"]),
        Index(value = ["sourceId", "providerKey", "revisionNumber"], unique = true),
    ],
)
data class ProviderChannelEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    @ColumnInfo(defaultValue = "0") val revisionNumber: Long = 0,
    val providerKey: String,
    val rawName: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val channelNumber: String? = null,
    val catchupMode: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    val catchupCorrection: String? = null,
)
