package app.muxtv.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomEpgRefreshStore(
    private val dao: EpgRefreshDao,
) : EpgRefreshStore {
    override suspend fun getTarget(sourceId: String): EpgRefreshTarget? {
        require(sourceId.isNotBlank())
        return dao.getTarget(sourceId)?.toModel()
    }

    override suspend fun getPolicies(): List<EpgRefreshPolicy> =
        dao.getPolicies().map(EpgRefreshPolicyEntity::toModel)

    override suspend fun getPolicy(sourceId: String): EpgRefreshPolicy? {
        require(sourceId.isNotBlank())
        return dao.getPolicy(sourceId)?.toModel()
    }

    override suspend fun upsertPolicy(policy: EpgRefreshPolicy) {
        dao.upsertPolicy(policy.toEntity())
    }

    override suspend fun removePolicy(sourceId: String) {
        require(sourceId.isNotBlank())
        dao.removePolicy(sourceId)
    }

    override fun observeStatus(sourceId: String): Flow<EpgRefreshStatus?> {
        require(sourceId.isNotBlank())
        return dao.observeState(sourceId).map { entity -> entity?.toModel() }
    }

    override suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int,
    ): List<EpgRefreshAttempt> {
        require(sourceId.isNotBlank())
        require(limit in 1..MAX_EPG_REFRESH_ATTEMPTS)
        return dao.getRecentAttempts(sourceId, limit).map(EpgRefreshAttemptEntity::toModel)
    }

    override suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean {
        require(sourceId.isNotBlank())
        require(runToken.isNotBlank())
        require(startedAtEpochMillis >= 0)
        require(staleBeforeEpochMillis <= startedAtEpochMillis)
        return dao.tryAcquire(
            sourceId = sourceId,
            runToken = runToken,
            startedAtEpochMillis = startedAtEpochMillis,
            staleBeforeEpochMillis = staleBeforeEpochMillis,
        )
    }

    override suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: EpgRefreshTrigger,
        completion: EpgRefreshCompletion,
        expectedAccessRef: String?,
    ) {
        require(sourceId.isNotBlank())
        require(runToken.isNotBlank())
        require(expectedAccessRef == null || expectedAccessRef.isNotBlank())
        dao.complete(
            sourceId = sourceId,
            runToken = runToken,
            trigger = trigger,
            completion = completion,
            expectedAccessRef = expectedAccessRef,
        )
    }
}

private fun EpgRefreshTargetRow.toModel(): EpgRefreshTarget = EpgRefreshTarget(
    sourceId = sourceId,
    sourceName = sourceName,
    providerSourceId = providerSourceId,
    accessRef = accessRef,
    defaultZoneId = defaultZoneId,
    activeRevision = activeRevision,
    validators = EpgRefreshHttpValidators(
        etag = etag,
        lastModified = lastModified,
    ),
)

private fun EpgRefreshPolicyEntity.toModel(): EpgRefreshPolicy = EpgRefreshPolicy(
    sourceId = sourceId,
    enabled = enabled,
    intervalMinutes = intervalMinutes,
    unmeteredOnly = unmeteredOnly,
    requiresCharging = requiresCharging,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun EpgRefreshPolicy.toEntity(): EpgRefreshPolicyEntity = EpgRefreshPolicyEntity(
    sourceId = sourceId,
    enabled = enabled,
    intervalMinutes = intervalMinutes,
    unmeteredOnly = unmeteredOnly,
    requiresCharging = requiresCharging,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun EpgRefreshStateEntity.toModel(): EpgRefreshStatus = EpgRefreshStatus(
    sourceId = sourceId,
    state = EpgRefreshRunState.valueOf(state),
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    lastSuccessRevision = lastSuccessRevision,
    lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
    resultFamily = resultFamily,
    resultCode = resultCode,
    httpStatus = httpStatus,
)

private fun EpgRefreshAttemptEntity.toModel(): EpgRefreshAttempt = EpgRefreshAttempt(
    id = id,
    sourceId = sourceId,
    trigger = EpgRefreshTrigger.valueOf(trigger),
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    state = EpgRefreshRunState.valueOf(resultState),
    resultFamily = resultFamily,
    resultCode = resultCode,
    revisionNumber = revisionNumber,
    channelCount = channelCount,
    programmeCount = programmeCount,
    skippedProgrammeCount = skippedProgrammeCount,
    warningCount = warningCount,
    unresolvedTimeCount = unresolvedTimeCount,
    httpStatus = httpStatus,
)
