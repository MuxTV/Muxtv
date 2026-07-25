package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Upsert

@Dao
internal interface PendingSourcePreparationDao {
    @Upsert
    suspend fun upsert(entity: PendingSourcePreparationEntity)

    @Query(
        """
        DELETE FROM pending_source_preparations
        WHERE preparationId = :preparationId
        """,
    )
    suspend fun delete(preparationId: String): Int

    @Query(
        """
        SELECT *
        FROM pending_source_preparations
        WHERE preparationId = :preparationId
        LIMIT 1
        """,
    )
    suspend fun get(preparationId: String): PendingSourcePreparationEntity?

    @Query(
        """
        SELECT *
        FROM pending_source_preparations
        WHERE expiresAtEpochMillis > :nowEpochMillis
        ORDER BY createdAtEpochMillis DESC, preparationId DESC
        LIMIT 1
        """,
    )
    suspend fun getLatestActive(nowEpochMillis: Long): PendingSourcePreparationEntity?

    @Query(
        """
        SELECT *
        FROM pending_source_preparations
        WHERE expiresAtEpochMillis <= :nowEpochMillis
        ORDER BY expiresAtEpochMillis, createdAtEpochMillis, preparationId
        LIMIT :limit
        """,
    )
    suspend fun getExpired(
        nowEpochMillis: Long,
        limit: Int,
    ): List<PendingSourcePreparationEntity>
}
