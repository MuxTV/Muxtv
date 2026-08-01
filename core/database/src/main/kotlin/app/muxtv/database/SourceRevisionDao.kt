package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert

internal data class SourceRemovalSnapshot(
    val activeRevision: Long,
    val credentialRef: String?,
)

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
        SELECT activeRevision, credentialRef
        FROM sources
        WHERE id = :sourceId
        LIMIT 1
        """,
    )
    abstract suspend fun sourceRemovalSnapshot(sourceId: String): SourceRemovalSnapshot?

    @Query("SELECT credentialRef FROM sources WHERE id = :sourceId LIMIT 1")
    protected abstract suspend fun sourceCredentialRef(sourceId: String): String?

    @Query(
        """
        SELECT COUNT(*)
        FROM source_refresh_states
        WHERE sourceId = :sourceId
          AND state = :runningState
          AND runToken = :expectedRunToken
        """,
    )
    protected abstract suspend fun refreshRunOwnerCount(
        sourceId: String,
        expectedRunToken: String,
        runningState: String,
    ): Int

    @Query(
        """
        DELETE FROM sources
        WHERE id = :sourceId
          AND activeRevision = 0
          AND credentialRef = :expectedCredentialRef
        """,
    )
    abstract suspend fun deleteInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): Int

    @Transaction
    open suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult {
        val snapshot = sourceRemovalSnapshot(sourceId)
            ?: return InactiveSourceRemovalResult.NotFound
        if (snapshot.activeRevision != 0L) {
            return InactiveSourceRemovalResult.Active
        }
        if (snapshot.credentialRef != expectedCredentialRef) {
            return InactiveSourceRemovalResult.CredentialMismatch
        }
        return if (deleteInactiveSource(sourceId, expectedCredentialRef) == 1) {
            InactiveSourceRemovalResult.Removed
        } else {
            InactiveSourceRemovalResult.ConcurrentChange
        }
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

    @Upsert
    abstract suspend fun upsertCanonicalChannels(channels: List<CanonicalChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProviderChannels(channels: List<ProviderChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStreamVariants(variants: List<StreamVariantEntity>)

    @Transaction
    open suspend fun stageCatalogBatch(
        canonicalChannels: List<CanonicalChannelEntity>,
        providerChannels: List<ProviderChannelEntity>,
        streamVariants: List<StreamVariantEntity>,
    ) {
        upsertCanonicalChannels(canonicalChannels)
        insertProviderChannels(providerChannels)
        insertStreamVariants(streamVariants)
    }

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
    open suspend fun activateRevisionIfCredentialMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        require(expectedCredentialRef.isNotBlank())
        if (sourceCredentialRef(sourceId) != expectedCredentialRef) {
            discardRevision(sourceId, revisionNumber)
            return SourceRevisionActivationResult.Superseded
        }
        return activateRevision(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = activatedAtEpochMillis,
            statistics = statistics,
        )
    }

    @Transaction
    open suspend fun activateRevisionIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult {
        require(expectedCredentialRef.isNotBlank())
        require(expectedRunToken.isNotBlank())
        val ownsRefresh = refreshRunOwnerCount(
            sourceId = sourceId,
            expectedRunToken = expectedRunToken,
            runningState = SourceRefreshRunState.RUNNING.name,
        ) == 1
        if (sourceCredentialRef(sourceId) != expectedCredentialRef || !ownsRefresh) {
            discardRevision(sourceId, revisionNumber)
            return SourceRevisionActivationResult.Superseded
        }
        return activateRevision(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = activatedAtEpochMillis,
            statistics = statistics,
        )
    }

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
