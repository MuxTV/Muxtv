package app.muxtv.database

import kotlinx.coroutines.flow.Flow

const val MIN_SOURCE_REFRESH_INTERVAL_MINUTES = 15L
const val MAX_SOURCE_REFRESH_ATTEMPTS = 25

enum class SourceRefreshRunState {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    NEEDS_AUTH,
    CANCELLED,
}

enum class SourceRefreshTrigger {
    MANUAL,
    PERIODIC,
    STARTUP,
}

data class SourceRefreshTarget(
    val sourceId: String,
    val sourceName: String,
    val credentialRef: String?,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
    }
}

data class SourceRefreshPolicy(
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

data class SourceRefreshStatus(
    val sourceId: String,
    val state: SourceRefreshRunState,
    val startedAtEpochMillis: Long?,
    val completedAtEpochMillis: Long?,
    val lastSuccessRevision: Long?,
    val lastSuccessAtEpochMillis: Long?,
    val failureFamily: String?,
    val failureCode: String?,
    val httpStatus: Int?,
    val skippedEntries: Int,
    val warningCount: Int,
)

data class SourceRefreshAttempt(
    val id: Long,
    val sourceId: String,
    val trigger: SourceRefreshTrigger,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val state: SourceRefreshRunState,
    val resultFamily: String,
    val resultCode: String?,
    val revisionNumber: Long?,
    val parsedEntries: Int?,
    val skippedEntries: Int,
    val warningCount: Int,
    val httpStatus: Int?,
)

data class SourceRefreshCompletion(
    val state: SourceRefreshRunState,
    val resultFamily: String,
    val resultCode: String?,
    val completedAtEpochMillis: Long,
    val revisionNumber: Long? = null,
    val parsedEntries: Int? = null,
    val skippedEntries: Int = 0,
    val warningCount: Int = 0,
    val httpStatus: Int? = null,
) {
    init {
        require(state !in setOf(SourceRefreshRunState.IDLE, SourceRefreshRunState.RUNNING))
        require(resultFamily.isNotBlank())
        require(completedAtEpochMillis >= 0)
        require(revisionNumber == null || revisionNumber > 0)
        require(parsedEntries == null || parsedEntries >= 0)
        require(skippedEntries >= 0)
        require(warningCount >= 0)
        require(httpStatus == null || httpStatus in 100..599)
        if (state == SourceRefreshRunState.SUCCEEDED) {
            requireNotNull(revisionNumber)
            requireNotNull(parsedEntries)
        }
    }
}

interface SourceRefreshStore {
    suspend fun getTarget(sourceId: String): SourceRefreshTarget?

    suspend fun getPolicies(): List<SourceRefreshPolicy>

    suspend fun upsertPolicy(policy: SourceRefreshPolicy)

    fun observeStatus(sourceId: String): Flow<SourceRefreshStatus?>

    suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int = MAX_SOURCE_REFRESH_ATTEMPTS,
    ): List<SourceRefreshAttempt>

    suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean

    suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: SourceRefreshTrigger,
        completion: SourceRefreshCompletion,
    )
}
