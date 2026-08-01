package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "epg_refresh_policies",
    foreignKeys = [
        ForeignKey(
            entity = EpgSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    primaryKeys = ["sourceId"],
)
data class EpgRefreshPolicyEntity(
    val sourceId: String,
    val enabled: Boolean,
    val intervalMinutes: Long,
    val unmeteredOnly: Boolean,
    val requiresCharging: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(sourceId.isNotBlank())
        require(intervalMinutes >= MIN_EPG_REFRESH_INTERVAL_MINUTES)
        require(updatedAtEpochMillis >= 0)
    }
}
