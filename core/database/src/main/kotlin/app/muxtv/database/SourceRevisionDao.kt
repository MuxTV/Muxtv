package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
internal abstract class SourceRevisionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSource(source: SourceEntity): Long

    @Query(
        """
        UPDATE sources
        SET name = :name, credentialRef = :credentialRef
        WHERE id = :sourceId
        """,
    )
    abstract suspend fun updateSourceMetadata(
        sourceId: String,
        name: String,
        credentialRef: String?,
    ): Int

    @Transaction
    open suspend fun upsertSource(source: SourceDefinition) {
        insertSource(
            SourceEntity(
                id = source.id,
                name = source.name,
                credentialRef = source.credentialRef,
            ),
        )
        check(
            updateSourceMetadata(
                sourceId = source.id,
                name = source.name,
                credentialRef = source.credentialRef,
            ) == 1,
        ) { "Unable to persist source metadata." }
    }

    @Query(
        """
        SELECT COALESCE(MAX(revisionNumber), 0) + 1
        FROM source_revisions
        WHERE sourceId = :sourceId
        """,
    )
    abstract suspend fun nextRevisionNumber(sourceId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRevision(revision: SourceRevisionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertCanonicalChannels(channels: List<CanonicalChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProviderChannels(channels: List<ProviderChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStreamVariants(variants: List<StreamVariantEntity>)

    @Query(
        """
        SELECT COUNT(*)
        FROM provider_channels
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        """,
    )
    abstract suspend fun countRevisionEntries(
        sourceId: String,
        revisionNumber: Long,
    ): Int

    @Query("SELECT activeRevision FROM sources WHERE id = :sourceId")
    abstract suspend fun activeRevision(sourceId: String): Long?

    @Query(
        """
        UPDATE source_revisions
        SET status = :retainedStatus
        WHERE sourceId = :sourceId AND status = :activeStatus
        """,
    )
    abstract suspend fun markCurrentActiveAsRetained(
        sourceId: String,
        activeStatus: String = SourceRevisionEntity.STATUS_ACTIVE,
        retainedStatus: String = SourceRevisionEntity.STATUS_RETAINED,
    )

    @Query(
        """
        UPDATE source_revisions
        SET status = :activeStatus,
            activatedAtEpochMillis = :activatedAtEpochMillis,
            parsedEntries = :parsedEntries,
            skippedEntries = :skippedEntries,
            warningCount = :warningCount
        WHERE sourceId = :sourceId
          AND revisionNumber = :revisionNumber
          AND status = :stagingStatus
        """,
    )
    abstract suspend fun markRevisionActive(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        parsedEntries: Int,
        skippedEntries: Int,
        warningCount: Int,
        stagingStatus: String = SourceRevisionEntity.STATUS_STAGING,
        activeStatus: String = SourceRevisionEntity.STATUS_ACTIVE,
    ): Int

    @Query("UPDATE sources SET activeRevision = :revisionNumber WHERE id = :sourceId")
    abstract suspend fun updateActiveRevision(
        sourceId: String,
        revisionNumber: Long,
    ): Int

    @Query(
        """
        DELETE FROM provider_channels
        WHERE sourceId = :sourceId
          AND revisionNumber != :currentRevision
          AND revisionNumber != :previousRevision
        """,
    )
    abstract suspend fun deleteProviderChannelsExcept(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
    )

    @Query(
        """
        DELETE FROM source_revisions
        WHERE sourceId = :sourceId
          AND revisionNumber != :currentRevision
          AND revisionNumber != :previousRevision
        """,
    )
    abstract suspend fun deleteRevisionsExcept(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
    )

    @Query(
        """
        DELETE FROM provider_channels
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        """,
    )
    abstract suspend fun deleteProviderChannelsForRevision(
        sourceId: String,
        revisionNumber: Long,
    )

    @Query(
        """
        DELETE FROM source_revisions
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber AND status = :stagingStatus
        """,
    )
    abstract suspend fun deleteStagingRevision(
        sourceId: String,
        revisionNumber: Long,
        stagingStatus: String = SourceRevisionEntity.STATUS_STAGING,
    )

    @Transaction
    open suspend fun activateRevision(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        val entryCount = countRevisionEntries(sourceId, revisionNumber)
        if (entryCount == 0) return SourceRevisionActivationResult.EmptyRevisionRejected

        val previousRevision = requireNotNull(activeRevision(sourceId)) {
            "Source does not exist."
        }
        markCurrentActiveAsRetained(sourceId)
        check(
            markRevisionActive(
                sourceId = sourceId,
                revisionNumber = revisionNumber,
                activatedAtEpochMillis = activatedAtEpochMillis,
                parsedEntries = statistics.parsedEntries,
                skippedEntries = statistics.skippedEntries,
                warningCount = statistics.warningCount,
            ) == 1,
        ) { "Source revision is not in staging state." }
        check(updateActiveRevision(sourceId, revisionNumber) == 1) {
            "Source does not exist."
        }

        deleteProviderChannelsExcept(
            sourceId = sourceId,
            currentRevision = revisionNumber,
            previousRevision = previousRevision,
        )
        deleteRevisionsExcept(
            sourceId = sourceId,
            currentRevision = revisionNumber,
            previousRevision = previousRevision,
        )

        return SourceRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = previousRevision,
            entryCount = entryCount,
        )
    }

    @Transaction
    open suspend fun discardRevision(
        sourceId: String,
        revisionNumber: Long,
    ) {
        deleteProviderChannelsForRevision(sourceId, revisionNumber)
        deleteStagingRevision(sourceId, revisionNumber)
    }
}
