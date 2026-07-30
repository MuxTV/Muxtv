package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "epg_channels",
    primaryKeys = ["sourceId", "revisionNumber", "externalId"],
    foreignKeys = [
        ForeignKey(
            entity = EpgRevisionEntity::class,
            parentColumns = ["sourceId", "revisionNumber"],
            childColumns = ["sourceId", "revisionNumber"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId", "revisionNumber"])],
)
data class EpgChannelEntity(
    val sourceId: String,
    val revisionNumber: Long,
    val externalId: String,
    val primaryDisplayName: String?,
    val primaryLanguage: String?,
    val iconRef: String?,
) {
    init {
        require(sourceId.isNotBlank())
        require(revisionNumber > 0)
        require(externalId.isNotBlank())
    }

    override fun toString(): String =
        "EpgChannelEntity(revisionNumber=$revisionNumber, displayNamePresent=${primaryDisplayName != null}, " +
            "languagePresent=${primaryLanguage != null}, iconPresent=${iconRef != null})"
}
