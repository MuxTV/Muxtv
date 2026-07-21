package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "source_revisions",
    primaryKeys = ["sourceId", "revisionNumber"],
    foreignKeys = [
        ForeignKey(
            entity = SourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId"]), Index(value = ["status"])],
)
data class SourceRevisionEntity(
    val sourceId: String,
    val revisionNumber: Long,
    val status: String,
    val startedAtEpochMillis: Long,
    val activatedAtEpochMillis: Long? = null,
    val parsedEntries: Int = 0,
    val skippedEntries: Int = 0,
    val warningCount: Int = 0,
) {
    init {
        require(revisionNumber > 0)
        require(status in VALID_STATUSES)
        require(parsedEntries >= 0)
        require(skippedEntries >= 0)
        require(warningCount >= 0)
    }

    companion object {
        const val STATUS_STAGING = "STAGING"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_RETAINED = "RETAINED"

        private val VALID_STATUSES = setOf(STATUS_STAGING, STATUS_ACTIVE, STATUS_RETAINED)
    }
}
