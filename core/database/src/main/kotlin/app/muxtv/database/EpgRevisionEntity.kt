package app.muxtv.database

import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index

@Entity(
    tableName = "epg_revisions",
    primaryKeys = ["sourceId", "revisionNumber"],
    foreignKeys = [
        ForeignKey(
            entity = EpgSourceEntity::class,
            parentColumns = ["id"],
            childColumns = ["sourceId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sourceId", "status"])],
)
data class EpgRevisionEntity(
    val sourceId: String,
    val revisionNumber: Long,
    val status: String,
    val startedAtEpochMillis: Long,
    val activatedAtEpochMillis: Long? = null,
    val acceptedChannels: Int = 0,
    val acceptedProgrammes: Int = 0,
    val skippedProgrammes: Int = 0,
    val warningCount: Int = 0,
    val unresolvedTimeCount: Int = 0,
) {
    init {
        require(sourceId.isNotBlank())
        require(revisionNumber > 0)
        require(status in VALID_STATUSES)
        require(startedAtEpochMillis >= 0)
        require(activatedAtEpochMillis == null || activatedAtEpochMillis >= startedAtEpochMillis)
        require(acceptedChannels >= 0)
        require(acceptedProgrammes >= 0)
        require(skippedProgrammes >= 0)
        require(warningCount >= 0)
        require(unresolvedTimeCount >= 0)
    }

    override fun toString(): String =
        "EpgRevisionEntity(revisionNumber=$revisionNumber, status=$status, " +
            "acceptedChannels=$acceptedChannels, acceptedProgrammes=$acceptedProgrammes, " +
            "skippedProgrammes=$skippedProgrammes, warningCount=$warningCount, " +
            "unresolvedTimeCount=$unresolvedTimeCount)"

    companion object {
        const val STATUS_STAGING = "STAGING"
        const val STATUS_ACTIVE = "ACTIVE"
        const val STATUS_RETAINED = "RETAINED"
        private val VALID_STATUSES = setOf(STATUS_STAGING, STATUS_ACTIVE, STATUS_RETAINED)
    }
}

data class EpgRevisionStatistics(
    val acceptedChannels: Int,
    val acceptedProgrammes: Int,
    val skippedProgrammes: Int,
    val warningCount: Int,
    val unresolvedTimeCount: Int,
) {
    init {
        require(acceptedChannels >= 0)
        require(acceptedProgrammes >= 0)
        require(skippedProgrammes >= 0)
        require(warningCount >= 0)
        require(unresolvedTimeCount >= 0)
    }
}

sealed interface EpgRevisionActivationResult {
    data class Activated(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val programmeCount: Int,
    ) : EpgRevisionActivationResult

    data object EmptyRevisionRejected : EpgRevisionActivationResult
    data object NotStaging : EpgRevisionActivationResult
    data object Superseded : EpgRevisionActivationResult
}
