package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "source_refresh_policies",
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["sourceId"],
)
data class SourceRefreshPolicyEntity(
    val sourceId: String,
    val enabled: Boolean,
    val intervalMinutes: Long,
    val unmeteredOnly: Boolean,
    val requiresCharging: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(sourceId.isNotBlank())
        require(intervalMinutes >= MIN_SOURCE_REFRESH_INTERVAL_MINUTES)
        require(updatedAtEpochMillis >= 0)
    }
}
