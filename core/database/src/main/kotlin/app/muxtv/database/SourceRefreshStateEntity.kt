package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey

@Entity(
    tableName = "source_refresh_states",
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
data class SourceRefreshStateEntity(
    val sourceId: String,
    val state: String = SourceRefreshRunState.IDLE.name,
    val runToken: String? = null,
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    val lastSuccessRevision: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
    val failureFamily: String? = null,
    val failureCode: String? = null,
    val httpStatus: Int? = null,
    val skippedEntries: Int = 0,
    val warningCount: Int = 0,
) {
    init {
        require(sourceId.isNotBlank())
        require(state in VALID_STATES)
        require(skippedEntries >= 0)
        require(warningCount >= 0)
    }

    override fun toString(): String =
        "SourceRefreshStateEntity(state=$state, runTokenPresent=${runToken != null}, " +
            "startedAtEpochMillis=$startedAtEpochMillis, completedAtEpochMillis=$completedAtEpochMillis, " +
            "lastSuccessRevision=$lastSuccessRevision, lastSuccessAtEpochMillis=$lastSuccessAtEpochMillis, " +
            "failureFamily=$failureFamily, failureCode=$failureCode, httpStatus=$httpStatus, " +
            "skippedEntries=$skippedEntries, warningCount=$warningCount)"

    private companion object {
        val VALID_STATES = SourceRefreshRunState.entries.mapTo(mutableSetOf(), SourceRefreshRunState::name)
    }
}
