package app.muxtv.database

import kotlinx.coroutines.flow.Flow

const val MIN_EPG_REFRESH_INTERVAL_MINUTES = 15L
const val MAX_EPG_REFRESH_ATTEMPTS = 25

private const val MAX_EPG_ETAG_CHARACTERS = 1_024
private const val MAX_EPG_LAST_MODIFIED_CHARACTERS = 256

enum class EpgRefreshRunState {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    NEEDS_AUTH,
    CANCELLED,
}

enum class EpgRefreshTrigger {
    MANUAL,
    PERIODIC,
    STARTUP,
}

data class EpgRefreshHttpValidators(
    val etag: String? = null,
    val lastModified: String? = null,
) {
    init {
        validateOptionalHttpValidator(etag, MAX_EPG_ETAG_CHARACTERS)
        validateOptionalHttpValidator(lastModified, MAX_EPG_LAST_MODIFIED_CHARACTERS)
    }

    val isEmpty: Boolean
        get() = etag == null && lastModified == null

    override fun toString(): String =
        "EpgRefreshHttpValidators(etagPresent=${etag != null}, lastModifiedPresent=${lastModified != null})"
}

data class EpgRefreshTarget(
    val sourceId: String,
    val sourceName: String,
    val providerSourceId: String?,
    val accessRef: String?,
    val defaultZoneId: String?,
    val activeRevision: Long,
    val validators: EpgRefreshHttpValidators,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(providerSourceId == null || providerSourceId.isNotBlank())
        require(accessRef == null || accessRef.isNotBlank())
        require(defaultZoneId == null || defaultZoneId.isNotBlank())
        require(activeRevision >= 0)
    }

    override fun toString(): String =
        "EpgRefreshTarget(providerLinked=${providerSourceId != null}, " +
            "accessRefPresent=${accessRef != null}, defaultZonePresent=${defaultZoneId != null}, " +
            "activeRevision=$activeRevision, validators=$validators)"
}

data class EpgRefreshPolicy(
    val sourceId: String,
    val enabled: Boolean,
    val intervalMinutes: Long,
    val unmeteredOnly: Boolean,
    val requiresCharging: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(sourceId.isNotBlank())
        require(intervalMinutes >= MIN_EPG_REFRESH_INTERVAL_MINUTES)
        require(updatedAtEpochMillis >= 0)
    }
}

data class EpgRefreshStatus(
    val sourceId: String,
    val state: EpgRefreshRunState,
    val startedAtEpochMillis: Long?,
    val completedAtEpochMillis: Long?,
    val lastSuccessRevision: Long?,
    val lastSuccessAtEpochMillis: Long?,
    val resultFamily: String?,
    val resultCode: String?,
    val httpStatus: Int?,
)

data class EpgRefreshAttempt(
    val id: Long,
    val sourceId: String,
    val trigger: EpgRefreshTrigger,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long,
    val state: EpgRefreshRunState,
    val resultFamily: String,
    val resultCode: String?,
    val revisionNumber: Long?,
    val channelCount: Int?,
    val programmeCount: Int?,
    val skippedProgrammeCount: Int?,
    val warningCount: Int?,
    val unresolvedTimeCount: Int?,
    val httpStatus: Int?,
)

sealed interface EpgRefreshCompletion {
    val completedAtEpochMillis: Long

    data class Refreshed(
        override val completedAtEpochMillis: Long,
        val accessRefBinding: String,
        val revisionNumber: Long,
        val channelCount: Int,
        val programmeCount: Int,
        val skippedProgrammeCount: Int,
        val warningCount: Int,
        val unresolvedTimeCount: Int,
        val validators: EpgRefreshHttpValidators,
    ) : EpgRefreshCompletion {
        init {
            require(completedAtEpochMillis >= 0)
            require(accessRefBinding.isNotBlank())
            require(revisionNumber > 0)
            require(channelCount >= 0)
            require(programmeCount >= 0)
            require(skippedProgrammeCount >= 0)
            require(warningCount >= 0)
            require(unresolvedTimeCount >= 0)
        }

        override fun toString(): String =
            "Refreshed(accessRefPresent=true, revisionNumber=$revisionNumber, " +
                "channelCount=$channelCount, programmeCount=$programmeCount, " +
                "skippedProgrammeCount=$skippedProgrammeCount, warningCount=$warningCount, " +
                "unresolvedTimeCount=$unresolvedTimeCount, validators=$validators)"
    }

    data class NotModified(
        override val completedAtEpochMillis: Long,
        val accessRefBinding: String,
        val validators: EpgRefreshHttpValidators,
    ) : EpgRefreshCompletion {
        init {
            require(completedAtEpochMillis >= 0)
            require(accessRefBinding.isNotBlank())
        }

        override fun toString(): String =
            "NotModified(accessRefPresent=true, validators=$validators)"
    }

    data class Terminal(
        val state: EpgRefreshRunState,
        override val completedAtEpochMillis: Long,
        val resultFamily: String,
        val resultCode: String? = null,
        val httpStatus: Int? = null,
    ) : EpgRefreshCompletion {
        init {
            require(state in TERMINAL_FAILURE_STATES)
            require(completedAtEpochMillis >= 0)
            require(resultFamily.isNotBlank())
            require(resultCode == null || resultCode.isNotBlank())
            require(httpStatus == null || httpStatus in 100..599)
        }
    }

    companion object {
        const val RESULT_FAMILY = "EPG_REFRESH"
        const val RESULT_REFRESHED = "REFRESHED"
        const val RESULT_NOT_MODIFIED = "NOT_MODIFIED"

        private val TERMINAL_FAILURE_STATES = setOf(
            EpgRefreshRunState.FAILED,
            EpgRefreshRunState.NEEDS_AUTH,
            EpgRefreshRunState.CANCELLED,
        )
    }
}

interface EpgRefreshStore {
    suspend fun getTarget(sourceId: String): EpgRefreshTarget?

    suspend fun getPolicies(): List<EpgRefreshPolicy>

    suspend fun getPolicy(sourceId: String): EpgRefreshPolicy?

    suspend fun upsertPolicy(policy: EpgRefreshPolicy)

    suspend fun removePolicy(sourceId: String)

    fun observeStatus(sourceId: String): Flow<EpgRefreshStatus?>

    suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int = MAX_EPG_REFRESH_ATTEMPTS,
    ): List<EpgRefreshAttempt>

    suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean

    suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: EpgRefreshTrigger,
        completion: EpgRefreshCompletion,
    )
}

private fun validateOptionalHttpValidator(
    value: String?,
    maxCharacters: Int,
) {
    if (value == null) return
    require(value.isNotBlank()) { "HTTP validator value must not be blank." }
    require(value.length <= maxCharacters) { "HTTP validator value is too long." }
    require(value.none { character -> character.code < 0x20 || character.code == 0x7f }) {
        "HTTP validator value contains control characters."
    }
}
