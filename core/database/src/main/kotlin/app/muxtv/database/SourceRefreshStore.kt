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
        require(credentialRef == null || credentialRef.isNotBlank())
    }

    override fun toString(): String =
        "SourceRefreshTarget(credentialRefPresent=${credentialRef != null})"
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

data class SourceRefreshOverview(
    val sourceId: String,
    val sourceName: String,
    val hasCredentialReference: Boolean,
    val activeRevision: Long,
    val policy: SourceRefreshPolicy?,
    val status: SourceRefreshStatus?,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(activeRevision >= 0)
        require(policy == null || policy.sourceId == sourceId)
        require(status == null || status.sourceId == sourceId)
    }
}

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
        require(resultCode == null || resultCode.isNotBlank())
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

    companion object {
        const val RESULT_FAMILY = "SOURCE_REFRESH"
        const val RESULT_SUPERSEDED = "SUPERSEDED"
    }
}

interface SourceRefreshStore {
    suspend fun getTarget(sourceId: String): SourceRefreshTarget?

    fun observeOverviews(): Flow<List<SourceRefreshOverview>>

    suspend fun getPolicies(): List<SourceRefreshPolicy>

    suspend fun upsertPolicy(policy: SourceRefreshPolicy)

    suspend fun removePolicy(sourceId: String)

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
        expectedCredentialRef: String?,
    )

    suspend fun completeWithDisposition(
        sourceId: String,
        runToken: String,
        trigger: SourceRefreshTrigger,
        completion: SourceRefreshCompletion,
        expectedCredentialRef: String?,
    ): RefreshCompletionDisposition {
        complete(
            sourceId = sourceId,
            runToken = runToken,
            trigger = trigger,
            completion = completion,
            expectedCredentialRef = expectedCredentialRef,
        )
        return RefreshCompletionDisposition.APPLIED
    }
}
