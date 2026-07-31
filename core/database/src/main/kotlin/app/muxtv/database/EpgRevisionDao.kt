package app.muxtv.database

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
internal abstract class EpgRevisionDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSourceRow(source: EpgSourceEntity): Long

    @Query(
        """
        UPDATE epg_sources
        SET name = :name,
            providerSourceId = :providerSourceId,
            accessRef = :accessRef,
            defaultZoneId = :defaultZoneId
        WHERE id = :sourceId
        """,
    )
    protected abstract suspend fun updateSourceMetadata(
        sourceId: String,
        name: String,
        providerSourceId: String?,
        accessRef: String?,
        defaultZoneId: String?,
    ): Int

    @Transaction
    open suspend fun insertSource(source: EpgSourceEntity) {
        insertSourceRow(source)
        check(
            updateSourceMetadata(
                sourceId = source.id,
                name = source.name,
                providerSourceId = source.providerSourceId,
                accessRef = source.accessRef,
                defaultZoneId = source.defaultZoneId,
            ) == 1,
        ) { "Unable to persist EPG source metadata." }
    }

    @Query(
        """
        SELECT COALESCE(MAX(revisionNumber), 0) + 1
        FROM epg_revisions
        WHERE sourceId = :sourceId
        """,
    )
    protected abstract suspend fun nextRevisionNumber(sourceId: String): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRevision(revision: EpgRevisionEntity)

    @Transaction
    open suspend fun beginNextRevision(
        sourceId: String,
        startedAtEpochMillis: Long,
    ): Long {
        require(sourceId.isNotBlank())
        require(startedAtEpochMillis >= 0)
        val revisionNumber = nextRevisionNumber(sourceId)
        insertRevision(
            EpgRevisionEntity(
                sourceId = sourceId,
                revisionNumber = revisionNumber,
                status = EpgRevisionEntity.STATUS_STAGING,
                startedAtEpochMillis = startedAtEpochMillis,
            ),
        )
        return revisionNumber
    }

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertChannels(channels: List<EpgChannelEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertProgrammes(programmes: List<EpgProgrammeEntity>)

    @Transaction
    open suspend fun stageBatch(
        channels: List<EpgChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
    ) {
        if (channels.isNotEmpty()) insertChannels(channels)
        if (programmes.isNotEmpty()) insertProgrammes(programmes)
    }

    open suspend fun activeProgrammes(
        sourceId: String,
        externalChannelIds: List<String>,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        limit: Int,
    ): List<EpgProgrammeEntity> {
        require(sourceId.isNotBlank())
        require(fromEpochMillis >= 0)
        require(toEpochMillis > fromEpochMillis)
        require(toEpochMillis - fromEpochMillis <= MAX_ACTIVE_WINDOW_MILLIS)
        require(limit in 1..MAX_ACTIVE_PROGRAMME_LIMIT)
        if (externalChannelIds.isEmpty()) return emptyList()
        require(externalChannelIds.size <= MAX_ACTIVE_CHANNEL_IDS)
        require(externalChannelIds.none(String::isBlank))

        return selectActiveProgrammes(
            sourceId = sourceId,
            externalChannelIds = externalChannelIds.distinct(),
            fromEpochMillis = fromEpochMillis,
            toEpochMillis = toEpochMillis,
            limit = limit,
        )
    }

    @Query(
        """
        SELECT p.*
        FROM epg_programmes AS p
        INNER JOIN epg_sources AS s
            ON s.id = p.sourceId AND s.activeRevision = p.revisionNumber
        WHERE p.sourceId = :sourceId
          AND p.externalChannelId IN (:externalChannelIds)
          AND p.startEpochMillis < :toEpochMillis
          AND (
              (p.stopEpochMillis IS NOT NULL AND p.stopEpochMillis > :fromEpochMillis)
              OR (p.stopEpochMillis IS NULL AND p.startEpochMillis >= :fromEpochMillis)
          )
        ORDER BY p.startEpochMillis ASC, p.sequenceNumber ASC
        LIMIT :limit
        """,
    )
    protected abstract suspend fun selectActiveProgrammes(
        sourceId: String,
        externalChannelIds: List<String>,
        fromEpochMillis: Long,
        toEpochMillis: Long,
        limit: Int,
    ): List<EpgProgrammeEntity>

    @Query("SELECT activeRevision FROM epg_sources WHERE id = :sourceId")
    abstract suspend fun activeRevision(sourceId: String): Long?

    @Query(
        """
        SELECT COUNT(*)
        FROM epg_programmes
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        """,
    )
    abstract suspend fun countRevisionProgrammes(sourceId: String, revisionNumber: Long): Int

    @Query(
        """
        SELECT status
        FROM epg_revisions
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        LIMIT 1
        """,
    )
    abstract suspend fun revisionStatus(sourceId: String, revisionNumber: Long): String?

    @Query(
        """
        SELECT revisionNumber
        FROM epg_revisions
        WHERE sourceId = :sourceId
        ORDER BY revisionNumber ASC
        """,
    )
    abstract suspend fun revisionNumbers(sourceId: String): List<Long>

    @Query(
        """
        UPDATE epg_revisions
        SET status = :retainedStatus
        WHERE sourceId = :sourceId AND status = :activeStatus
        """,
    )
    abstract suspend fun markCurrentActiveAsRetained(
        sourceId: String,
        activeStatus: String = EpgRevisionEntity.STATUS_ACTIVE,
        retainedStatus: String = EpgRevisionEntity.STATUS_RETAINED,
    )

    @Query(
        """
        UPDATE epg_revisions
        SET status = :activeStatus,
            activatedAtEpochMillis = :activatedAtEpochMillis,
            acceptedChannels = :acceptedChannels,
            acceptedProgrammes = :acceptedProgrammes,
            skippedProgrammes = :skippedProgrammes,
            warningCount = :warningCount,
            unresolvedTimeCount = :unresolvedTimeCount
        WHERE sourceId = :sourceId
          AND revisionNumber = :revisionNumber
          AND status = :stagingStatus
        """,
    )
    abstract suspend fun markRevisionActive(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        acceptedChannels: Int,
        acceptedProgrammes: Int,
        skippedProgrammes: Int,
        warningCount: Int,
        unresolvedTimeCount: Int,
        stagingStatus: String = EpgRevisionEntity.STATUS_STAGING,
        activeStatus: String = EpgRevisionEntity.STATUS_ACTIVE,
    ): Int

    @Query("UPDATE epg_sources SET activeRevision = :revisionNumber WHERE id = :sourceId")
    abstract suspend fun updateActiveRevision(sourceId: String, revisionNumber: Long): Int

    @Query(
        """
        DELETE FROM epg_channels
        WHERE sourceId = :sourceId
          AND revisionNumber != :currentRevision
          AND revisionNumber != :previousRevision
        """,
    )
    abstract suspend fun deleteChannelsExcept(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
    )

    @Query(
        """
        DELETE FROM epg_programmes
        WHERE sourceId = :sourceId
          AND revisionNumber != :currentRevision
          AND revisionNumber != :previousRevision
        """,
    )
    abstract suspend fun deleteProgrammesExcept(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
    )

    @Query(
        """
        DELETE FROM epg_revisions
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
        DELETE FROM epg_revisions
        WHERE sourceId = :sourceId
          AND revisionNumber = :revisionNumber
          AND status = :stagingStatus
        """,
    )
    abstract suspend fun deleteStagingRevision(
        sourceId: String,
        revisionNumber: Long,
        stagingStatus: String = EpgRevisionEntity.STATUS_STAGING,
    ): Int

    @Transaction
    open suspend fun activateRevision(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult {
        val previousRevision = requireNotNull(activeRevision(sourceId)) {
            "EPG source does not exist."
        }
        if (previousRevision > revisionNumber) {
            return EpgRevisionActivationResult.Superseded
        }

        val programmeCount = countRevisionProgrammes(sourceId, revisionNumber)
        if (programmeCount == 0) return EpgRevisionActivationResult.EmptyRevisionRejected
        if (revisionStatus(sourceId, revisionNumber) != EpgRevisionEntity.STATUS_STAGING) {
            return EpgRevisionActivationResult.NotStaging
        }

        markCurrentActiveAsRetained(sourceId)
        check(
            markRevisionActive(
                sourceId = sourceId,
                revisionNumber = revisionNumber,
                activatedAtEpochMillis = activatedAtEpochMillis,
                acceptedChannels = statistics.acceptedChannels,
                acceptedProgrammes = statistics.acceptedProgrammes,
                skippedProgrammes = statistics.skippedProgrammes,
                warningCount = statistics.warningCount,
                unresolvedTimeCount = statistics.unresolvedTimeCount,
            ) == 1,
        ) { "EPG revision activation failed." }
        check(updateActiveRevision(sourceId, revisionNumber) == 1) {
            "EPG source does not exist."
        }

        deleteChannelsExcept(sourceId, revisionNumber, previousRevision)
        deleteProgrammesExcept(sourceId, revisionNumber, previousRevision)
        deleteRevisionsExcept(sourceId, revisionNumber, previousRevision)

        return EpgRevisionActivationResult.Activated(
            revisionNumber = revisionNumber,
            previousRevisionNumber = previousRevision,
            programmeCount = programmeCount,
        )
    }

    @Transaction
    open suspend fun discardRevision(sourceId: String, revisionNumber: Long) {
        deleteStagingRevision(sourceId, revisionNumber)
    }

    private companion object {
        const val MAX_ACTIVE_CHANNEL_IDS = 256
        const val MAX_ACTIVE_PROGRAMME_LIMIT = 500
        const val MAX_ACTIVE_WINDOW_MILLIS = 31L * 24 * 60 * 60 * 1_000
    }
}
