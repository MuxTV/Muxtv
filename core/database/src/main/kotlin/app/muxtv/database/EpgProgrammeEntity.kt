package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "epg_programmes",
    primaryKeys = ["sourceId", "revisionNumber", "sequenceNumber"],
    foreignKeys = [
        ForeignKey(
            entity = EpgRevisionEntity::class,
            parentColumns = ["sourceId", "revisionNumber"],
            childColumns = ["sourceId", "revisionNumber"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["sourceId", "revisionNumber", "externalChannelId", "startEpochMillis"]),
        Index(value = ["sourceId", "revisionNumber", "startEpochMillis", "stopEpochMillis"]),
    ],
)
data class EpgProgrammeEntity(
    val sourceId: String,
    val revisionNumber: Long,
    val sequenceNumber: Long,
    val externalChannelId: String,
    val startEpochMillis: Long,
    val stopEpochMillis: Long?,
    val primaryTitle: String?,
    val primaryLanguage: String?,
    val subtitle: String?,
    val description: String?,
    val category: String?,
    val iconRef: String?,
    val episodeNumber: String?,
    val isNew: Boolean,
) {
    init {
        require(sourceId.isNotBlank())
        require(revisionNumber > 0)
        require(sequenceNumber > 0)
        require(externalChannelId.isNotBlank())
        require(startEpochMillis >= 0)
        require(stopEpochMillis == null || stopEpochMillis >= startEpochMillis)
    }

    override fun toString(): String =
        "EpgProgrammeEntity(revisionNumber=$revisionNumber, sequenceNumber=$sequenceNumber, " +
            "stopPresent=${stopEpochMillis != null}, titlePresent=${primaryTitle != null}, " +
            "subtitlePresent=${subtitle != null}, descriptionPresent=${description != null}, " +
            "categoryPresent=${category != null}, iconPresent=${iconRef != null}, " +
            "episodePresent=${episodeNumber != null}, isNew=$isNew)"
}
