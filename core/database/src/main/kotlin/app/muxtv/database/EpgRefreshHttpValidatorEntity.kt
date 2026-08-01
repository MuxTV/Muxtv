package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "epg_refresh_http_validators",
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
data class EpgRefreshHttpValidatorEntity(
    val sourceId: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(sourceId.isNotBlank())
        EpgRefreshHttpValidators(etag = etag, lastModified = lastModified)
        require(updatedAtEpochMillis >= 0)
    }

    override fun toString(): String =
        "EpgRefreshHttpValidatorEntity(sourceId=<redacted>, etagPresent=${etag != null}, " +
            "lastModifiedPresent=${lastModified != null}, updatedAtEpochMillis=$updatedAtEpochMillis)"
}
