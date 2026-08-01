package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

@Entity(
    tableName = "epg_refresh_attempts",
    foreignKeys = [
        ForeignKey(
            entity = EpgSourceEntity::class,
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
data class EpgRefreshAttemptEntity(
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
    val channelCount: Int? = null,
    val programmeCount: Int? = null,
    val skippedProgrammeCount: Int? = null,
    val warningCount: Int? = null,
    val unresolvedTimeCount: Int? = null,
    val httpStatus: Int? = null,
) {
    init {
        require(sourceId.isNotBlank())
        require(runToken.isNotBlank())
        require(trigger in EpgRefreshTrigger.entries.map { it.name })
        require(startedAtEpochMillis >= 0)
        require(completedAtEpochMillis >= startedAtEpochMillis)
        require(resultState in EpgRefreshRunState.entries.map { it.name })
        require(resultState !in setOf(EpgRefreshRunState.IDLE.name, EpgRefreshRunState.RUNNING.name))
        require(resultFamily.isNotBlank())
        require(resultCode == null || resultCode.isNotBlank())
        require(revisionNumber == null || revisionNumber > 0)
        require(channelCount == null || channelCount >= 0)
        require(programmeCount == null || programmeCount >= 0)
        require(skippedProgrammeCount == null || skippedProgrammeCount >= 0)
        require(warningCount == null || warningCount >= 0)
        require(unresolvedTimeCount == null || unresolvedTimeCount >= 0)
        require(httpStatus == null || httpStatus in 100..599)
    }

    override fun toString(): String =
        "EpgRefreshAttemptEntity(id=$id, runTokenPresent=true, trigger=$trigger, " +
            "startedAtEpochMillis=$startedAtEpochMillis, completedAtEpochMillis=$completedAtEpochMillis, " +
            "resultState=$resultState, resultFamily=$resultFamily, resultCode=$resultCode, " +
            "revisionNumber=$revisionNumber, channelCount=$channelCount, programmeCount=$programmeCount, " +
            "skippedProgrammeCount=$skippedProgrammeCount, warningCount=$warningCount, " +
            "unresolvedTimeCount=$unresolvedTimeCount, httpStatus=$httpStatus)"
}
