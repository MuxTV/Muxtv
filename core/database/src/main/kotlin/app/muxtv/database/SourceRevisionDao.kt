package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

internal data class SourceRemovalSnapshot(
    val activeRevision: Long,
    val credentialRef: String?,
) {
    override fun toString(): String =
        "SourceRemovalSnapshot(activeRevision=$activeRevision, credentialRefPresent=${credentialRef != null})"
}

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
        DELETE FROM search_documents
        WHERE providerChannelId IN (
            SELECT id
            FROM provider_channels
            WHERE sourceId = :sourceId
        )
        """,
    )
    protected abstract suspend fun deleteProviderSearchDocumentsForSource(sourceId: String): Int

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
        deleteProviderSearchDocumentsForSource(sourceId)
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertCanonicalChannels(channels: List<CanonicalChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProviderChannels(channels: List<ProviderChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertStreamVariants(variants: List<StreamVariantEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun insertSearchDocuments(documents: List<SearchDocumentEntity>)

    @Transaction
    open suspend fun stageCatalogBatch(
        canonicalChannels: List<CanonicalChannelEntity>,
        providerChannels: List<ProviderChannelEntity>,
        streamVariants: List<StreamVariantEntity>,
    ) {
        // STAGING may create a missing canonical identity, but it cannot mutate metadata already
        // visible through an active revision. Canonical-name search metadata is therefore published
        // only in activateRevision(), after canonical display metadata is accepted.
        insertCanonicalChannels(canonicalChannels)
        insertProviderChannels(providerChannels)
        insertStreamVariants(streamVariants)
        val searchDocuments = providerSearchDocuments(providerChannels, streamVariants)
        if (searchDocuments.isNotEmpty()) insertSearchDocuments(searchDocuments)
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
        UPDATE canonical_channels
        SET displayName = (
            SELECT CASE
                WHEN provider_channels.tvgName IS NOT NULL
                 AND TRIM(provider_channels.tvgName) != ''
                    THEN provider_channels.tvgName
                ELSE provider_channels.rawName
            END
            FROM stream_variants
            INNER JOIN provider_channels
                ON provider_channels.id = stream_variants.providerChannelId
            INNER JOIN sources AS active_sources
                ON active_sources.id = provider_channels.sourceId
            WHERE stream_variants.canonicalChannelId = canonical_channels.id
              AND provider_channels.revisionNumber = active_sources.activeRevision
            ORDER BY provider_channels.sourceId COLLATE BINARY ASC,
                     provider_channels.providerKey COLLATE BINARY ASC,
                     provider_channels.id COLLATE BINARY ASC
            LIMIT 1
        )
        WHERE id IN (
            SELECT DISTINCT stream_variants.canonicalChannelId
            FROM stream_variants
            INNER JOIN provider_channels
                ON provider_channels.id = stream_variants.providerChannelId
            WHERE provider_channels.sourceId = :sourceId
              AND (
                  provider_channels.revisionNumber = :currentRevision
                  OR provider_channels.revisionNumber = :previousRevision
              )
        )
          AND EXISTS (
              SELECT 1
              FROM stream_variants
              INNER JOIN provider_channels
                  ON provider_channels.id = stream_variants.providerChannelId
              INNER JOIN sources AS active_sources
                  ON active_sources.id = provider_channels.sourceId
              WHERE stream_variants.canonicalChannelId = canonical_channels.id
                AND provider_channels.revisionNumber = active_sources.activeRevision
          )
        """,
    )
    abstract suspend fun publishCanonicalDisplayMetadata(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
    ): Int

    @Query(
        """
        SELECT DISTINCT canonical_channels.*
        FROM canonical_channels
        INNER JOIN stream_variants
            ON stream_variants.canonicalChannelId = canonical_channels.id
        INNER JOIN provider_channels
            ON provider_channels.id = stream_variants.providerChannelId
        WHERE provider_channels.sourceId = :sourceId
          AND (
              provider_channels.revisionNumber = :currentRevision
              OR provider_channels.revisionNumber = :previousRevision
          )
        ORDER BY canonical_channels.id COLLATE BINARY
        """,
    )
    protected abstract suspend fun affectedCanonicalChannels(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
    ): List<CanonicalChannelEntity>

    @Query(
        """
        DELETE FROM search_documents
        WHERE providerChannelId IN (
            SELECT id
            FROM provider_channels
            WHERE sourceId = :sourceId
              AND revisionNumber != :currentRevision
              AND revisionNumber != :previousRevision
        )
        """,
    )
    protected abstract suspend fun deleteProviderSearchDocumentsExcept(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
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
        DELETE FROM search_documents
        WHERE providerChannelId IN (
            SELECT id
            FROM provider_channels
            WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        )
        """,
    )
    protected abstract suspend fun deleteProviderSearchDocumentsForRevision(
        sourceId: String,
        revisionNumber: Long,
    ): Int

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

    @Query(
        """
        DELETE FROM canonical_channels
        WHERE NOT EXISTS (
            SELECT 1
            FROM stream_variants
            WHERE stream_variants.canonicalChannelId = canonical_channels.id
        )
          AND NOT EXISTS (
              SELECT 1
              FROM user_channel_overlays
              WHERE user_channel_overlays.canonicalChannelId = canonical_channels.id
          )
        """,
    )
    abstract suspend fun deleteUnreferencedCanonicalChannels(): Int

    @Query(
        """
        DELETE FROM search_documents
        WHERE kind = '${SearchDocumentKind.CANONICAL_NAME}'
          AND canonicalChannelId IS NOT NULL
          AND NOT EXISTS (
              SELECT 1
              FROM canonical_channels
              WHERE canonical_channels.id = search_documents.canonicalChannelId
          )
        """,
    )
    protected abstract suspend fun deleteOrphanCanonicalSearchDocuments(): Int

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
        publishCanonicalDisplayMetadata(
            sourceId = sourceId,
            currentRevision = revisionNumber,
            previousRevision = previousRevision,
        )
        val canonicalDocuments = canonicalSearchDocuments(
            affectedCanonicalChannels(
                sourceId = sourceId,
                currentRevision = revisionNumber,
                previousRevision = previousRevision,
            ),
        )
        if (canonicalDocuments.isNotEmpty()) insertSearchDocuments(canonicalDocuments)

        deleteProviderSearchDocumentsExcept(
            sourceId = sourceId,
            currentRevision = revisionNumber,
            previousRevision = previousRevision,
        )
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
        deleteUnreferencedCanonicalChannels()
        deleteOrphanCanonicalSearchDocuments()

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
        deleteProviderSearchDocumentsForRevision(sourceId, revisionNumber)
        deleteProviderChannelsForRevision(sourceId, revisionNumber)
        deleteStagingRevision(sourceId, revisionNumber)
        deleteUnreferencedCanonicalChannels()
        deleteOrphanCanonicalSearchDocuments()
    }
}
