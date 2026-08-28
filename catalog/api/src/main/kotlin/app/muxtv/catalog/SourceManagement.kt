package app.muxtv.catalog

import kotlinx.coroutines.flow.Flow

const val MIN_SOURCE_REFRESH_INTERVAL_MINUTES = 15L

enum class SourceRefreshRunState {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    NEEDS_AUTH,
    CANCELLED,
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
        require(updatedAtEpochMillis >= 0L)
    }
}

data class SourceRefreshStatus(
    val state: SourceRefreshRunState,
)

data class SourceRefreshOverview(
    val sourceId: String,
    val sourceName: String,
    val hasStoredAccess: Boolean,
    val activeRevision: Long,
    val policy: SourceRefreshPolicy?,
    val status: SourceRefreshStatus?,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(activeRevision >= 0L)
        require(policy == null || policy.sourceId == sourceId)
    }
}

enum class SourcePlaybackApprovalResetResult {
    Reset,
    Unchanged,
    SourceNotFound,
    AccessUnavailable,
}

/**
 * Stable application-facing control surface for Sources UI.
 *
 * Scheduling, retry, persistence and credential ownership remain implementation details behind
 * this port. In particular, no WorkRequest identity, Room entity or credential reference crosses
 * this boundary.
 */
interface SourceManagement {
    fun observeOverviews(): Flow<List<SourceRefreshOverview>>

    fun refreshNow(sourceId: String)

    suspend fun updatePolicy(policy: SourceRefreshPolicy)

    suspend fun removePolicy(sourceId: String)

    suspend fun revokePlaybackApprovals(sourceId: String): SourcePlaybackApprovalResetResult
}
