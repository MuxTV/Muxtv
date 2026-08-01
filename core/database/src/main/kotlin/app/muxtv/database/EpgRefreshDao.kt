package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

internal data class EpgRefreshTargetRow(
    val sourceId: String,
    val sourceName: String,
    val providerSourceId: String?,
    val accessRef: String?,
    val defaultZoneId: String?,
    val activeRevision: Long,
    val etag: String?,
    val lastModified: String?,
) {
    override fun toString(): String =
        "EpgRefreshTargetRow(providerLinked=${providerSourceId != null}, " +
            "accessRefPresent=${accessRef != null}, defaultZonePresent=${defaultZoneId != null}, " +
            "activeRevision=$activeRevision, etagPresent=${etag != null}, " +
            "lastModifiedPresent=${lastModified != null})"
}

@Dao
internal abstract class EpgRefreshDao {
    @Query(
        """
        SELECT
            epg_sources.id AS sourceId,
            epg_sources.name AS sourceName,
            epg_sources.providerSourceId AS providerSourceId,
            epg_sources.accessRef AS accessRef,
            epg_sources.defaultZoneId AS defaultZoneId,
            epg_sources.activeRevision AS activeRevision,
            epg_refresh_http_validators.etag AS etag,
            epg_refresh_http_validators.lastModified AS lastModified
        FROM epg_sources
        LEFT JOIN epg_refresh_http_validators
            ON epg_refresh_http_validators.sourceId = epg_sources.id
           AND epg_refresh_http_validators.accessRefBinding = epg_sources.accessRef
        WHERE epg_sources.id = :sourceId
        LIMIT 1
        """,
    )
    protected abstract suspend fun selectTarget(sourceId: String): EpgRefreshTargetRow?

    @Query(
        """
        DELETE FROM epg_refresh_http_validators
        WHERE sourceId = :sourceId
          AND (
              (SELECT accessRef FROM epg_sources WHERE id = :sourceId) IS NULL
              OR accessRefBinding != (SELECT accessRef FROM epg_sources WHERE id = :sourceId)
          )
        """,
    )
    protected abstract suspend fun deleteStaleValidators(sourceId: String): Int

    @Transaction
    open suspend fun getTarget(sourceId: String): EpgRefreshTargetRow? {
        deleteStaleValidators(sourceId)
        return selectTarget(sourceId)
    }

    @Query("SELECT * FROM epg_refresh_policies ORDER BY sourceId")
    abstract suspend fun getPolicies(): List<EpgRefreshPolicyEntity>

    @Query("SELECT * FROM epg_refresh_policies WHERE sourceId = :sourceId LIMIT 1")
    abstract suspend fun getPolicy(sourceId: String): EpgRefreshPolicyEntity?

    @Upsert
    abstract suspend fun upsertPolicy(policy: EpgRefreshPolicyEntity)

    @Query("DELETE FROM epg_refresh_policies WHERE sourceId = :sourceId")
    protected abstract suspend fun deletePolicyRow(sourceId: String): Int

    @Query("DELETE FROM epg_refresh_states WHERE sourceId = :sourceId")
    protected abstract suspend fun deleteSchedulingState(sourceId: String): Int

    @Transaction
    open suspend fun removePolicy(sourceId: String) {
        deletePolicyRow(sourceId)
        deleteSchedulingState(sourceId)
    }

    @Query("SELECT * FROM epg_refresh_states WHERE sourceId = :sourceId LIMIT 1")
    abstract fun observeState(sourceId: String): Flow<EpgRefreshStateEntity?>

    @Query(
        """
        SELECT *
        FROM epg_refresh_attempts
        WHERE sourceId = :sourceId
        ORDER BY completedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int,
    ): List<EpgRefreshAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertState(state: EpgRefreshStateEntity): Long

    @Query(
        """
        UPDATE epg_refresh_states
        SET state = :runningState,
            runToken = :runToken,
            startedAtEpochMillis = :startedAtEpochMillis,
            completedAtEpochMillis = NULL,
            resultFamily = NULL,
            resultCode = NULL,
            httpStatus = NULL
        WHERE sourceId = :sourceId
          AND (
              state != :runningState
              OR startedAtEpochMillis IS NULL
              OR startedAtEpochMillis <= :staleBeforeEpochMillis
          )
        """,
    )
    abstract suspend fun markRunning(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
        runningState: String = "RUNNING",
    ): Int

    @Transaction
    open suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean {
        insertState(EpgRefreshStateEntity(sourceId = sourceId))
        return markRunning(
            sourceId = sourceId,
            runToken = runToken,
            startedAtEpochMillis = startedAtEpochMillis,
            staleBeforeEpochMillis = staleBeforeEpochMillis,
            runningState = EpgRefreshRunState.RUNNING.name,
        ) == 1
    }

    @Query(
        """
        SELECT startedAtEpochMillis
        FROM epg_refresh_states
        WHERE sourceId = :sourceId
          AND state = :runningState
          AND runToken = :runToken
        LIMIT 1
        """,
    )
    protected abstract suspend fun startedAt(
        sourceId: String,
        runToken: String,
        runningState: String = "RUNNING",
    ): Long?

    @Query("SELECT accessRef FROM epg_sources WHERE id = :sourceId LIMIT 1")
    protected abstract suspend fun currentAccessRef(sourceId: String): String?

    @Query(
        """
        UPDATE epg_refresh_states
        SET state = :successState,
            runToken = NULL,
            completedAtEpochMillis = :completedAtEpochMillis,
            lastSuccessRevision = :revisionNumber,
            lastSuccessAtEpochMillis = :completedAtEpochMillis,
            resultFamily = :resultFamily,
            resultCode = :resultCode,
            httpStatus = 200
        WHERE sourceId = :sourceId
          AND state = :runningState
          AND runToken = :runToken
        """,
    )
    protected abstract suspend fun finishRefreshed(
        sourceId: String,
        runToken: String,
        completedAtEpochMillis: Long,
        revisionNumber: Long,
        resultFamily: String,
        resultCode: String,
        runningState: String = "RUNNING",
        successState: String = "SUCCEEDED",
    ): Int

    @Query(
        """
        UPDATE epg_refresh_states
        SET state = :successState,
            runToken = NULL,
            completedAtEpochMillis = :completedAtEpochMillis,
            lastSuccessAtEpochMillis = :completedAtEpochMillis,
            resultFamily = :resultFamily,
            resultCode = :resultCode,
            httpStatus = 304
        WHERE sourceId = :sourceId
          AND state = :runningState
          AND runToken = :runToken
        """,
    )
    protected abstract suspend fun finishNotModified(
        sourceId: String,
        runToken: String,
        completedAtEpochMillis: Long,
        resultFamily: String,
        resultCode: String,
        runningState: String = "RUNNING",
        successState: String = "SUCCEEDED",
    ): Int

    @Query(
        """
        UPDATE epg_refresh_states
        SET state = :state,
            runToken = NULL,
            completedAtEpochMillis = :completedAtEpochMillis,
            resultFamily = :resultFamily,
            resultCode = :resultCode,
            httpStatus = :httpStatus
        WHERE sourceId = :sourceId
          AND state = :runningState
          AND runToken = :runToken
        """,
    )
    protected abstract suspend fun finishTerminal(
        sourceId: String,
        runToken: String,
        state: String,
        completedAtEpochMillis: Long,
        resultFamily: String,
        resultCode: String?,
        httpStatus: Int?,
        runningState: String = "RUNNING",
    ): Int

    @Upsert
    protected abstract suspend fun upsertValidators(validators: EpgRefreshHttpValidatorEntity)

    @Query("DELETE FROM epg_refresh_http_validators WHERE sourceId = :sourceId")
    protected abstract suspend fun deleteValidators(sourceId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAttempt(attempt: EpgRefreshAttemptEntity)

    @Query(
        """
        DELETE FROM epg_refresh_attempts
        WHERE sourceId = :sourceId
          AND id NOT IN (
              SELECT id
              FROM epg_refresh_attempts
              WHERE sourceId = :sourceId
              ORDER BY completedAtEpochMillis DESC, id DESC
              LIMIT :keepCount
          )
        """,
    )
    protected abstract suspend fun pruneAttempts(
        sourceId: String,
        keepCount: Int,
    )

    @Transaction
    open suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: EpgRefreshTrigger,
        completion: EpgRefreshCompletion,
        expectedAccessRef: String?,
    ) {
        val startedAtEpochMillis = startedAt(
            sourceId = sourceId,
            runToken = runToken,
            runningState = EpgRefreshRunState.RUNNING.name,
        ) ?: return

        val effectiveCompletion = accessCheckedCompletion(
            sourceId = sourceId,
            completion = completion,
            expectedAccessRef = expectedAccessRef,
        )
        val updated = when (effectiveCompletion) {
            is EpgRefreshCompletion.Refreshed -> finishRefreshed(
                sourceId = sourceId,
                runToken = runToken,
                completedAtEpochMillis = effectiveCompletion.completedAtEpochMillis,
                revisionNumber = effectiveCompletion.revisionNumber,
                resultFamily = EpgRefreshCompletion.RESULT_FAMILY,
                resultCode = EpgRefreshCompletion.RESULT_REFRESHED,
                runningState = EpgRefreshRunState.RUNNING.name,
                successState = EpgRefreshRunState.SUCCEEDED.name,
            )

            is EpgRefreshCompletion.NotModified -> finishNotModified(
                sourceId = sourceId,
                runToken = runToken,
                completedAtEpochMillis = effectiveCompletion.completedAtEpochMillis,
                resultFamily = EpgRefreshCompletion.RESULT_FAMILY,
                resultCode = EpgRefreshCompletion.RESULT_NOT_MODIFIED,
                runningState = EpgRefreshRunState.RUNNING.name,
                successState = EpgRefreshRunState.SUCCEEDED.name,
            )

            is EpgRefreshCompletion.Terminal -> finishTerminal(
                sourceId = sourceId,
                runToken = runToken,
                state = effectiveCompletion.state.name,
                completedAtEpochMillis = effectiveCompletion.completedAtEpochMillis,
                resultFamily = effectiveCompletion.resultFamily,
                resultCode = effectiveCompletion.resultCode,
                httpStatus = effectiveCompletion.httpStatus,
                runningState = EpgRefreshRunState.RUNNING.name,
            )
        }
        if (updated != 1) return

        when (effectiveCompletion) {
            is EpgRefreshCompletion.Refreshed -> replaceValidators(
                sourceId = sourceId,
                accessRefBinding = effectiveCompletion.accessRefBinding,
                validators = effectiveCompletion.validators,
                updatedAtEpochMillis = effectiveCompletion.completedAtEpochMillis,
            )

            is EpgRefreshCompletion.NotModified -> replaceValidators(
                sourceId = sourceId,
                accessRefBinding = effectiveCompletion.accessRefBinding,
                validators = effectiveCompletion.validators,
                updatedAtEpochMillis = effectiveCompletion.completedAtEpochMillis,
            )

            is EpgRefreshCompletion.Terminal -> Unit
        }

        insertAttempt(
            effectiveCompletion.toAttemptEntity(
                sourceId = sourceId,
                runToken = runToken,
                trigger = trigger,
                startedAtEpochMillis = startedAtEpochMillis,
            ),
        )
        pruneAttempts(sourceId, MAX_EPG_REFRESH_ATTEMPTS)
    }

    private suspend fun accessCheckedCompletion(
        sourceId: String,
        completion: EpgRefreshCompletion,
        expectedAccessRef: String?,
    ): EpgRefreshCompletion {
        val currentAccessRef = currentAccessRef(sourceId)
        if (expectedAccessRef != null) {
            if (currentAccessRef != expectedAccessRef) return completion.toSuperseded()
            return when (completion) {
                is EpgRefreshCompletion.Refreshed ->
                    if (completion.accessRefBinding == expectedAccessRef) completion else completion.toSuperseded()

                is EpgRefreshCompletion.NotModified ->
                    if (completion.accessRefBinding == expectedAccessRef) completion else completion.toSuperseded()

                is EpgRefreshCompletion.Terminal -> completion
            }
        }

        return when (completion) {
            is EpgRefreshCompletion.Refreshed ->
                if (currentAccessRef == completion.accessRefBinding) completion else completion.toSuperseded()

            is EpgRefreshCompletion.NotModified ->
                if (currentAccessRef == completion.accessRefBinding) completion else completion.toSuperseded()

            is EpgRefreshCompletion.Terminal -> completion
        }
    }

    private suspend fun replaceValidators(
        sourceId: String,
        accessRefBinding: String,
        validators: EpgRefreshHttpValidators,
        updatedAtEpochMillis: Long,
    ) {
        if (validators.isEmpty) {
            deleteValidators(sourceId)
        } else {
            upsertValidators(
                EpgRefreshHttpValidatorEntity(
                    sourceId = sourceId,
                    accessRefBinding = accessRefBinding,
                    etag = validators.etag,
                    lastModified = validators.lastModified,
                    updatedAtEpochMillis = updatedAtEpochMillis,
                ),
            )
        }
    }
}

private fun EpgRefreshCompletion.toSuperseded(): EpgRefreshCompletion.Terminal =
    EpgRefreshCompletion.Terminal(
        state = EpgRefreshRunState.CANCELLED,
        completedAtEpochMillis = completedAtEpochMillis,
        resultFamily = EpgRefreshCompletion.RESULT_FAMILY,
        resultCode = EpgRefreshCompletion.RESULT_SUPERSEDED,
    )

private fun EpgRefreshCompletion.toAttemptEntity(
    sourceId: String,
    runToken: String,
    trigger: EpgRefreshTrigger,
    startedAtEpochMillis: Long,
): EpgRefreshAttemptEntity = when (this) {
    is EpgRefreshCompletion.Refreshed -> EpgRefreshAttemptEntity(
        sourceId = sourceId,
        runToken = runToken,
        trigger = trigger.name,
        startedAtEpochMillis = startedAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
        resultState = EpgRefreshRunState.SUCCEEDED.name,
        resultFamily = EpgRefreshCompletion.RESULT_FAMILY,
        resultCode = EpgRefreshCompletion.RESULT_REFRESHED,
        revisionNumber = revisionNumber,
        channelCount = channelCount,
        programmeCount = programmeCount,
        skippedProgrammeCount = skippedProgrammeCount,
        warningCount = warningCount,
        unresolvedTimeCount = unresolvedTimeCount,
        httpStatus = 200,
    )

    is EpgRefreshCompletion.NotModified -> EpgRefreshAttemptEntity(
        sourceId = sourceId,
        runToken = runToken,
        trigger = trigger.name,
        startedAtEpochMillis = startedAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
        resultState = EpgRefreshRunState.SUCCEEDED.name,
        resultFamily = EpgRefreshCompletion.RESULT_FAMILY,
        resultCode = EpgRefreshCompletion.RESULT_NOT_MODIFIED,
        httpStatus = 304,
    )

    is EpgRefreshCompletion.Terminal -> EpgRefreshAttemptEntity(
        sourceId = sourceId,
        runToken = runToken,
        trigger = trigger.name,
        startedAtEpochMillis = startedAtEpochMillis,
        completedAtEpochMillis = completedAtEpochMillis,
        resultState = state.name,
        resultFamily = resultFamily,
        resultCode = resultCode,
        httpStatus = httpStatus,
    )
}
