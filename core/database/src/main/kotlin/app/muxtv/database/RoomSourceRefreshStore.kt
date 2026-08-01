package app.muxtv.database

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal class RoomSourceRefreshStore(
    private val dao: SourceRefreshDao,
) : SourceRefreshStore {
    override suspend fun getTarget(sourceId: String): SourceRefreshTarget? {
        require(sourceId.isNotBlank())
        return dao.getTarget(sourceId)?.let { row ->
            SourceRefreshTarget(
                sourceId = row.sourceId,
                sourceName = row.sourceName,
                credentialRef = row.credentialRef,
            )
        }
    }

    override fun observeOverviews(): Flow<List<SourceRefreshOverview>> =
        dao.observeOverviews().map { rows -> rows.map(SourceRefreshOverviewRow::toModel) }

    override suspend fun getPolicies(): List<SourceRefreshPolicy> =
        dao.getPolicies().map(SourceRefreshPolicyEntity::toModel)

    override suspend fun upsertPolicy(policy: SourceRefreshPolicy) {
        dao.upsertPolicy(policy.toEntity())
    }

    override suspend fun removePolicy(sourceId: String) {
        require(sourceId.isNotBlank())
        dao.removePolicy(sourceId)
    }

    override fun observeStatus(sourceId: String): Flow<SourceRefreshStatus?> {
        require(sourceId.isNotBlank())
        return dao.observeState(sourceId).map { entity -> entity?.toModel() }
    }

    override suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int,
    ): List<SourceRefreshAttempt> {
        require(sourceId.isNotBlank())
        require(limit in 1..MAX_SOURCE_REFRESH_ATTEMPTS)
        return dao.getRecentAttempts(sourceId, limit).map(SourceRefreshAttemptEntity::toModel)
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
        trigger: SourceRefreshTrigger,
        completion: SourceRefreshCompletion,
        expectedCredentialRef: String?,
    ) {
        require(sourceId.isNotBlank())
        require(runToken.isNotBlank())
        dao.complete(
            sourceId = sourceId,
            runToken = runToken,
            trigger = trigger,
            completion = completion,
            expectedCredentialRef = expectedCredentialRef,
        )
    }
}

private fun SourceRefreshOverviewRow.toModel(): SourceRefreshOverview {
    val policy = policyEnabled?.let { enabled ->
        SourceRefreshPolicy(
            sourceId = sourceId,
            enabled = enabled,
            intervalMinutes = requireNotNull(policyIntervalMinutes),
            unmeteredOnly = requireNotNull(policyUnmeteredOnly),
            requiresCharging = requireNotNull(policyRequiresCharging),
            updatedAtEpochMillis = requireNotNull(policyUpdatedAtEpochMillis),
        )
    }
    val status = refreshState?.let { state ->
        SourceRefreshStatus(
            sourceId = sourceId,
            state = SourceRefreshRunState.valueOf(state),
            startedAtEpochMillis = startedAtEpochMillis,
            completedAtEpochMillis = completedAtEpochMillis,
            lastSuccessRevision = lastSuccessRevision,
            lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
            failureFamily = failureFamily,
            failureCode = failureCode,
            httpStatus = httpStatus,
            skippedEntries = skippedEntries ?: 0,
            warningCount = warningCount ?: 0,
        )
    }
    return SourceRefreshOverview(
        sourceId = sourceId,
        sourceName = sourceName,
        hasCredentialReference = hasCredentialReference,
        activeRevision = activeRevision,
        policy = policy,
        status = status,
    )
}

private fun SourceRefreshPolicyEntity.toModel(): SourceRefreshPolicy = SourceRefreshPolicy(
    sourceId = sourceId,
    enabled = enabled,
    intervalMinutes = intervalMinutes,
    unmeteredOnly = unmeteredOnly,
    requiresCharging = requiresCharging,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SourceRefreshPolicy.toEntity(): SourceRefreshPolicyEntity = SourceRefreshPolicyEntity(
    sourceId = sourceId,
    enabled = enabled,
    intervalMinutes = intervalMinutes,
    unmeteredOnly = unmeteredOnly,
    requiresCharging = requiresCharging,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SourceRefreshStateEntity.toModel(): SourceRefreshStatus = SourceRefreshStatus(
    sourceId = sourceId,
    state = SourceRefreshRunState.valueOf(state),
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    lastSuccessRevision = lastSuccessRevision,
    lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
    failureFamily = failureFamily,
    failureCode = failureCode,
    httpStatus = httpStatus,
    skippedEntries = skippedEntries,
    warningCount = warningCount,
)

private fun SourceRefreshAttemptEntity.toModel(): SourceRefreshAttempt = SourceRefreshAttempt(
    id = id,
    sourceId = sourceId,
    trigger = SourceRefreshTrigger.valueOf(trigger),
    startedAtEpochMillis = startedAtEpochMillis,
    completedAtEpochMillis = completedAtEpochMillis,
    state = SourceRefreshRunState.valueOf(resultState),
    resultFamily = resultFamily,
    resultCode = resultCode,
    revisionNumber = revisionNumber,
    parsedEntries = parsedEntries,
    skippedEntries = skippedEntries,
    warningCount = warningCount,
    httpStatus = httpStatus,
)
