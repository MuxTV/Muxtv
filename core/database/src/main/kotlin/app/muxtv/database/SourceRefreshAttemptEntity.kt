package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "source_refresh_attempts",
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
        Index(value = ["sourceId", "startedAtEpochMillis"]),
        Index(value = ["runToken"], unique = true),
    ],
)
data class SourceRefreshAttemptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sourceId: String,
    val runToken: String,
    val trigger: String,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val resultState: String,
    val resultFamily: String,
    val resultCode: String? = null,
    val revisionNumber: Long? = null,
    val parsedEntries: Int? = null,
    val skippedEntries: Int = 0,
    val warningCount: Int = 0,
    val httpStatus: Int? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(runToken.isNotBlank())
        require(trigger in VALID_TRIGGERS)
        require(resultState in VALID_STATES)
        require(resultFamily.isNotBlank())
        require(completedAtEpochMillis >= startedAtEpochMillis)
        require(revisionNumber == null || revisionNumber > 0)
        require(parsedEntries == null || parsedEntries >= 0)
        require(skippedEntries >= 0)
        require(warningCount >= 0)
        require(httpStatus == null || httpStatus in 100..599)
    }

    override fun toString(): String =
        "SourceRefreshAttemptEntity(id=$id, runTokenPresent=true, trigger=$trigger, " +
            "startedAtEpochMillis=$startedAtEpochMillis, completedAtEpochMillis=$completedAtEpochMillis, " +
            "resultState=$resultState, resultFamily=$resultFamily, resultCode=$resultCode, " +
            "revisionNumber=$revisionNumber, parsedEntries=$parsedEntries, skippedEntries=$skippedEntries, " +
            "warningCount=$warningCount, httpStatus=$httpStatus)"

    private companion object {
        val VALID_TRIGGERS = SourceRefreshTrigger.entries.mapTo(mutableSetOf(), SourceRefreshTrigger::name)
        val VALID_STATES = SourceRefreshRunState.entries.mapTo(mutableSetOf(), SourceRefreshRunState::name)
    }
}
