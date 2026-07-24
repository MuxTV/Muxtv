package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

internal data class SourceRefreshTargetRow(
    val sourceId: String,
    val sourceName: String,
    val credentialRef: String?,
)

@Dao
internal abstract class SourceRefreshDao {
    @Query(
        """
        SELECT id AS sourceId, name AS sourceName, credentialRef
        FROM sources
        WHERE id = :sourceId
        LIMIT 1
        """,
    )
    abstract suspend fun getTarget(sourceId: String): SourceRefreshTargetRow?

    @Query("SELECT * FROM source_refresh_policies ORDER BY sourceId")
    abstract suspend fun getPolicies(): List<SourceRefreshPolicyEntity>

    @Upsert
    abstract suspend fun upsertPolicy(policy: SourceRefreshPolicyEntity)

    @Query("DELETE FROM source_refresh_policies WHERE sourceId = :sourceId")
    abstract suspend fun deletePolicy(sourceId: String): Int

    @Query("SELECT * FROM source_refresh_states WHERE sourceId = :sourceId LIMIT 1")
    abstract fun observeState(sourceId: String): Flow<SourceRefreshStateEntity?>

    @Query(
        """
        SELECT *
        FROM source_refresh_attempts
        WHERE sourceId = :sourceId
        ORDER BY startedAtEpochMillis DESC, id DESC
        LIMIT :limit
        """,
    )
    abstract suspend fun getRecentAttempts(
        sourceId: String,
        limit: Int,
    ): List<SourceRefreshAttemptEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertState(state: SourceRefreshStateEntity): Long

    @Query(
        """
        UPDATE source_refresh_states
        SET state = :runningState,
            runToken = :runToken,
            startedAtEpochMillis = :startedAtEpochMillis,
            completedAtEpochMillis = NULL,
            failureFamily = NULL,
            failureCode = NULL,
            httpStatus = NULL,
            skippedEntries = 0,
            warningCount = 0
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
        runningState: String = SourceRefreshRunState.RUNNING.name,
    ): Int

    @Transaction
    open suspend fun tryAcquire(
        sourceId: String,
        runToken: String,
        startedAtEpochMillis: Long,
        staleBeforeEpochMillis: Long,
    ): Boolean {
        insertState(SourceRefreshStateEntity(sourceId = sourceId))
        return markRunning(
            sourceId = sourceId,
            runToken = runToken,
            startedAtEpochMillis = startedAtEpochMillis,
            staleBeforeEpochMillis = staleBeforeEpochMillis,
        ) == 1
    }

    @Query(
        """
        SELECT startedAtEpochMillis
        FROM source_refresh_states
        WHERE sourceId = :sourceId AND runToken = :runToken
        """,
    )
    abstract suspend fun startedAt(
        sourceId: String,
        runToken: String,
    ): Long?

    @Query(
        """
        UPDATE source_refresh_states
        SET state = :state,
            runToken = NULL,
            completedAtEpochMillis = :completedAtEpochMillis,
            lastSuccessRevision = CASE
                WHEN :state = :successState THEN :revisionNumber
                ELSE lastSuccessRevision
            END,
            lastSuccessAtEpochMillis = CASE
                WHEN :state = :successState THEN :completedAtEpochMillis
                ELSE lastSuccessAtEpochMillis
            END,
            failureFamily = CASE
                WHEN :state = :successState THEN NULL
                ELSE :resultFamily
            END,
            failureCode = CASE
                WHEN :state = :successState THEN NULL
                ELSE :resultCode
            END,
            httpStatus = :httpStatus,
            skippedEntries = :skippedEntries,
            warningCount = :warningCount
        WHERE sourceId = :sourceId
          AND state = :runningState
          AND runToken = :runToken
        """,
    )
    abstract suspend fun finishState(
        sourceId: String,
        runToken: String,
        state: String,
        resultFamily: String,
        resultCode: String?,
        completedAtEpochMillis: Long,
        revisionNumber: Long?,
        skippedEntries: Int,
        warningCount: Int,
        httpStatus: Int?,
        runningState: String = SourceRefreshRunState.RUNNING.name,
        successState: String = SourceRefreshRunState.SUCCEEDED.name,
    ): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAttempt(attempt: SourceRefreshAttemptEntity)

    @Query(
        """
        DELETE FROM source_refresh_attempts
        WHERE sourceId = :sourceId
          AND id NOT IN (
              SELECT id
              FROM source_refresh_attempts
              WHERE sourceId = :sourceId
              ORDER BY startedAtEpochMillis DESC, id DESC
              LIMIT :keepCount
          )
        """,
    )
    abstract suspend fun pruneAttempts(
        sourceId: String,
        keepCount: Int,
    )

    @Transaction
    open suspend fun complete(
        sourceId: String,
        runToken: String,
        trigger: SourceRefreshTrigger,
        completion: SourceRefreshCompletion,
    ) {
        val startedAtEpochMillis = startedAt(sourceId, runToken) ?: return
        val updated = finishState(
            sourceId = sourceId,
            runToken = runToken,
            state = completion.state.name,
            resultFamily = completion.resultFamily,
            resultCode = completion.resultCode,
            completedAtEpochMillis = completion.completedAtEpochMillis,
            revisionNumber = completion.revisionNumber,
            skippedEntries = completion.skippedEntries,
            warningCount = completion.warningCount,
            httpStatus = completion.httpStatus,
        )
        if (updated != 1) return

        insertAttempt(
            SourceRefreshAttemptEntity(
                sourceId = sourceId,
                runToken = runToken,
                trigger = trigger.name,
                startedAtEpochMillis = startedAtEpochMillis,
                completedAtEpochMillis = completion.completedAtEpochMillis,
                resultState = completion.state.name,
                resultFamily = completion.resultFamily,
                resultCode = completion.resultCode,
                revisionNumber = completion.revisionNumber,
                parsedEntries = completion.parsedEntries,
                skippedEntries = completion.skippedEntries,
                warningCount = completion.warningCount,
                httpStatus = completion.httpStatus,
            ),
        )
        pruneAttempts(sourceId, MAX_SOURCE_REFRESH_ATTEMPTS)
    }
}
