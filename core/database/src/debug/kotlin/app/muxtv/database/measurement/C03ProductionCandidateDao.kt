package app.muxtv.database.measurement

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

@Dao
abstract class C03ProductionCandidateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSource(entity: C03ProductionCandidateSourceEntity): Long

    @Query(
        """
        UPDATE c03_production_candidate_sources
        SET credentialRef = :credentialRef
        WHERE sourceId = :sourceId
        """,
    )
    protected abstract suspend fun updateSourceCredential(
        sourceId: String,
        credentialRef: String?,
    ): Int

    @Query(
        """
        SELECT *
        FROM c03_production_candidate_sources
        WHERE sourceId = :sourceId
        LIMIT 1
        """,
    )
    protected abstract suspend fun sourceById(sourceId: String): C03ProductionCandidateSourceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceRefreshOwner(entity: C03ProductionCandidateRefreshOwnerEntity)

    @Query(
        """
        SELECT *
        FROM c03_production_candidate_refresh_owners
        WHERE sourceId = :sourceId
        LIMIT 1
        """,
    )
    protected abstract suspend fun refreshOwner(
        sourceId: String,
    ): C03ProductionCandidateRefreshOwnerEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertRevision(entity: C03ProductionCandidateRevisionEntity)

    @Query(
        """
        SELECT status
        FROM c03_production_candidate_revisions
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        LIMIT 1
        """,
    )
    protected abstract suspend fun revisionStatus(
        sourceId: String,
        revisionNumber: Long,
    ): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertSearchPayload(
        entity: C03ProductionCandidateSearchPayloadEntity,
    ): Long

    @Query(
        """
        SELECT *
        FROM c03_production_candidate_search_payloads
        WHERE searchPayloadId = :searchPayloadId
        LIMIT 1
        """,
    )
    protected abstract suspend fun searchPayloadById(
        searchPayloadId: String,
    ): C03ProductionCandidateSearchPayloadEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertSearchDocuments(
        entities: List<C03ProductionCandidateSearchDocumentEntity>,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertPayload(entity: C03ProductionCandidatePayloadEntity): Long

    @Query(
        """
        SELECT *
        FROM c03_production_candidate_payloads
        WHERE payloadId = :payloadId
        LIMIT 1
        """,
    )
    protected abstract suspend fun payloadById(payloadId: String): C03ProductionCandidatePayloadEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertMembership(entity: C03ProductionCandidateMembershipEntity)

    @Query(
        """
        SELECT COUNT(*)
        FROM c03_production_candidate_memberships
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        """,
    )
    abstract suspend fun membershipCount(
        sourceId: String,
        revisionNumber: Long,
    ): Int

    @Query(
        """
        SELECT COUNT(*)
        FROM c03_production_candidate_revisions
        WHERE sourceId = :sourceId AND revisionNumber = :revisionNumber
        """,
    )
    protected abstract suspend fun revisionCount(
        sourceId: String,
        revisionNumber: Long,
    ): Int

    @Query(
        """
        UPDATE c03_production_candidate_revisions
        SET status = :status, activatedAtEpochMillis = :activatedAtEpochMillis
        WHERE sourceId = :sourceId
          AND revisionNumber = :revisionNumber
          AND status = :expectedStatus
        """,
    )
    protected abstract suspend fun updateRevisionStatus(
        sourceId: String,
        revisionNumber: Long,
        expectedStatus: String,
        status: String,
        activatedAtEpochMillis: Long?,
    ): Int

    @Query(
        """
        UPDATE c03_production_candidate_revisions
        SET status = :retainedStatus
        WHERE sourceId = :sourceId
          AND revisionNumber = :revisionNumber
          AND status = :activeStatus
        """,
    )
    protected abstract suspend fun retainRevision(
        sourceId: String,
        revisionNumber: Long,
        activeStatus: String = C03ProductionCandidateRevisionStatus.ACTIVE,
        retainedStatus: String = C03ProductionCandidateRevisionStatus.RETAINED,
    ): Int

    @Query(
        """
        UPDATE c03_production_candidate_sources
        SET activeRevision = :revisionNumber
        WHERE sourceId = :sourceId
        """,
    )
    protected abstract suspend fun updateActiveRevision(
        sourceId: String,
        revisionNumber: Long,
    ): Int

    @Query(
        """
        DELETE FROM c03_production_candidate_revisions
        WHERE sourceId = :sourceId
          AND revisionNumber = :revisionNumber
          AND status = :stagingStatus
        """,
    )
    protected abstract suspend fun deleteStagingRevision(
        sourceId: String,
        revisionNumber: Long,
        stagingStatus: String = C03ProductionCandidateRevisionStatus.STAGING,
    ): Int

    @Query(
        """
        DELETE FROM c03_production_candidate_revisions
        WHERE sourceId = :sourceId
          AND revisionNumber != :currentRevision
          AND (:previousRevision <= 0 OR revisionNumber != :previousRevision)
        """,
    )
    protected abstract suspend fun deleteRevisionsExcept(
        sourceId: String,
        currentRevision: Long,
        previousRevision: Long,
    ): Int

    @Query(
        """
        SELECT COALESCE(MAX(activeRevision), 0)
        FROM c03_production_candidate_sources
        WHERE sourceId = :sourceId
        """,
    )
    abstract suspend fun activeRevision(sourceId: String): Long

    @Query(
        """
        SELECT revisionNumber
        FROM c03_production_candidate_revisions
        WHERE sourceId = :sourceId AND status = :retainedStatus
        ORDER BY revisionNumber
        """,
    )
    abstract suspend fun retainedRevisions(
        sourceId: String,
        retainedStatus: String = C03ProductionCandidateRevisionStatus.RETAINED,
    ): List<Long>

    @Query(
        """
        SELECT
            m.ordinal AS ordinal,
            m.logicalChannelId AS logicalChannelId,
            p.payloadId AS payloadId,
            p.contentHash AS contentHash,
            p.canonicalChannelId AS canonicalChannelId,
            p.rawName AS rawName,
            p.groupTitle AS groupTitle,
            p.channelNumber AS channelNumber
        FROM c03_production_candidate_sources AS s
        INNER JOIN c03_production_candidate_memberships AS m
            ON m.sourceId = s.sourceId AND m.revisionNumber = s.activeRevision
        INNER JOIN c03_production_candidate_payloads AS p
            ON p.payloadId = m.payloadId
        WHERE s.sourceId = :sourceId
        ORDER BY m.ordinal
        """,
    )
    abstract suspend fun activeRows(sourceId: String): List<C03ProductionCandidateActiveRow>

    @Query(
        """
        SELECT
            m.ordinal AS ordinal,
            m.logicalChannelId AS logicalChannelId,
            p.payloadId AS payloadId,
            p.canonicalChannelId AS canonicalChannelId,
            p.rawName AS rawName
        FROM c03_production_candidate_search_documents_fts
        INNER JOIN c03_production_candidate_search_documents AS d
            ON d.rowid = c03_production_candidate_search_documents_fts.rowid
        INNER JOIN c03_production_candidate_payloads AS p
            ON p.searchPayloadId = d.searchPayloadId
        INNER JOIN c03_production_candidate_memberships AS m
            ON m.payloadId = p.payloadId
        INNER JOIN c03_production_candidate_sources AS s
            ON s.sourceId = m.sourceId AND s.activeRevision = m.revisionNumber
        WHERE s.sourceId = :sourceId
          AND c03_production_candidate_search_documents_fts MATCH :ftsExpression
        GROUP BY m.sourceId, m.revisionNumber, m.ordinal
        ORDER BY m.ordinal
        LIMIT :limit
        """,
    )
    abstract suspend fun activeSearch(
        sourceId: String,
        ftsExpression: String,
        limit: Int,
    ): List<C03ProductionCandidateSearchRow>

    @Query("SELECT COUNT(*) FROM c03_production_candidate_payloads")
    protected abstract suspend fun payloadRowCount(): Int

    @Query("SELECT COUNT(*) FROM c03_production_candidate_search_payloads")
    protected abstract suspend fun searchPayloadRowCount(): Int

    @Query("SELECT COUNT(*) FROM c03_production_candidate_memberships")
    protected abstract suspend fun membershipRowCount(): Int

    @Query("SELECT COUNT(*) FROM c03_production_candidate_search_documents")
    protected abstract suspend fun searchDocumentRowCount(): Int

    @Query(
        """
        SELECT p.payloadId
        FROM c03_production_candidate_payloads AS p
        WHERE NOT EXISTS (
            SELECT 1
            FROM c03_production_candidate_memberships AS m
            WHERE m.payloadId = p.payloadId
        )
        ORDER BY p.payloadId
        LIMIT :limit
        """,
    )
    protected abstract suspend fun orphanPayloadIds(limit: Int): List<String>

    @Query("DELETE FROM c03_production_candidate_payloads WHERE payloadId IN (:payloadIds)")
    protected abstract suspend fun deletePayloads(payloadIds: List<String>): Int

    @Query(
        """
        SELECT s.searchPayloadId
        FROM c03_production_candidate_search_payloads AS s
        WHERE NOT EXISTS (
            SELECT 1
            FROM c03_production_candidate_payloads AS p
            WHERE p.searchPayloadId = s.searchPayloadId
        )
        ORDER BY s.searchPayloadId
        LIMIT :limit
        """,
    )
    protected abstract suspend fun orphanSearchPayloadIds(limit: Int): List<String>

    @Query(
        "DELETE FROM c03_production_candidate_search_payloads WHERE searchPayloadId IN (:searchPayloadIds)",
    )
    protected abstract suspend fun deleteSearchPayloads(searchPayloadIds: List<String>): Int

    @Transaction
    open suspend fun upsertSource(
        sourceId: String,
        credentialRef: String?,
    ) {
        require(sourceId.isNotBlank())
        require(credentialRef == null || credentialRef.isNotBlank())
        insertSource(
            C03ProductionCandidateSourceEntity(
                sourceId = sourceId,
                credentialRef = credentialRef,
            ),
        )
        check(updateSourceCredential(sourceId, credentialRef) == 1) {
            "C03 candidate source update failed."
        }
    }

    @Transaction
    open suspend fun setRunningRefreshOwner(
        sourceId: String,
        runToken: String,
    ) {
        require(sourceId.isNotBlank())
        require(runToken.isNotBlank())
        check(sourceById(sourceId) != null) { "C03 candidate source is missing." }
        replaceRefreshOwner(
            C03ProductionCandidateRefreshOwnerEntity(
                sourceId = sourceId,
                state = C03ProductionCandidateRefreshState.RUNNING,
                runToken = runToken,
            ),
        )
    }

    @Transaction
    open suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    ) {
        require(sourceId.isNotBlank())
        require(revisionNumber > 0)
        insertRevision(
            C03ProductionCandidateRevisionEntity(
                sourceId = sourceId,
                revisionNumber = revisionNumber,
                status = C03ProductionCandidateRevisionStatus.STAGING,
                startedAtEpochMillis = startedAtEpochMillis,
            ),
        )
    }

    @Transaction
    open suspend fun stageBatch(
        sourceId: String,
        revisionNumber: Long,
        entries: List<C03ProductionCandidateStageEntry>,
    ) {
        require(
            revisionStatus(sourceId, revisionNumber) == C03ProductionCandidateRevisionStatus.STAGING,
        ) { "C03 candidate revision is not staging." }

        entries.forEach { entry ->
            val searchPayloadId = C03ProductionCandidateContentAddress.searchPayloadId(
                searchHashVersion = entry.searchHashVersion,
                searchContentHash = entry.searchContentHash,
            )
            val searchPayload = C03ProductionCandidateSearchPayloadEntity(
                searchPayloadId = searchPayloadId,
                searchContentHash = entry.searchContentHash,
                searchHashVersion = entry.searchHashVersion,
                canonicalChannelId = entry.canonicalChannelId,
                rawName = entry.rawName,
                groupTitle = entry.groupTitle,
                channelNumber = entry.channelNumber,
            )
            val searchInsert = insertSearchPayload(searchPayload)
            if (searchInsert == INSERT_IGNORED) {
                check(searchPayloadById(searchPayloadId) == searchPayload) {
                    "C03 candidate immutable search payload collision."
                }
            } else {
                insertSearchDocuments(searchDocuments(searchPayload))
            }

            val payloadId = C03ProductionCandidateContentAddress.payloadId(
                sourceId = sourceId,
                logicalChannelId = entry.logicalChannelId,
                contentHashVersion = entry.contentHashVersion,
                contentHash = entry.contentHash,
            )
            val payload = C03ProductionCandidatePayloadEntity(
                payloadId = payloadId,
                sourceId = sourceId,
                logicalChannelId = entry.logicalChannelId,
                contentHash = entry.contentHash,
                contentHashVersion = entry.contentHashVersion,
                canonicalChannelId = entry.canonicalChannelId,
                providerKey = entry.providerKey,
                rawName = entry.rawName,
                tvgId = entry.tvgId,
                tvgName = entry.tvgName,
                logoUrl = entry.logoUrl,
                groupTitle = entry.groupTitle,
                channelNumber = entry.channelNumber,
                locator = entry.locator,
                catchupMode = entry.catchupMode,
                catchupSource = entry.catchupSource,
                catchupDays = entry.catchupDays,
                catchupCorrection = entry.catchupCorrection,
                userAgent = entry.userAgent,
                referrer = entry.referrer,
                searchPayloadId = searchPayloadId,
            )
            if (insertPayload(payload) == INSERT_IGNORED) {
                check(payloadById(payloadId) == payload) {
                    "C03 candidate immutable catalog payload collision."
                }
            }

            insertMembership(
                C03ProductionCandidateMembershipEntity(
                    sourceId = sourceId,
                    revisionNumber = revisionNumber,
                    ordinal = entry.ordinal,
                    logicalChannelId = entry.logicalChannelId,
                    payloadId = payloadId,
                ),
            )
        }
    }

    @Transaction
    open suspend fun activateIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
    ): C03ProductionCandidateActivationResult {
        val source = sourceById(sourceId)
        val owner = refreshOwner(sourceId)
        val owned = source?.credentialRef == expectedCredentialRef &&
            owner?.state == C03ProductionCandidateRefreshState.RUNNING &&
            owner.runToken == expectedRunToken &&
            revisionStatus(sourceId, revisionNumber) == C03ProductionCandidateRevisionStatus.STAGING

        if (!owned) {
            deleteStagingRevision(sourceId, revisionNumber)
            return C03ProductionCandidateActivationResult.Superseded
        }

        if (membershipCount(sourceId, revisionNumber) == 0) {
            return C03ProductionCandidateActivationResult.EmptyRevisionRejected
        }

        val previousRevision = checkNotNull(source) {
            "C03 candidate owned source is missing."
        }.activeRevision
        if (previousRevision > 0) {
            retainRevision(sourceId, previousRevision)
        }
        val activated = updateRevisionStatus(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            expectedStatus = C03ProductionCandidateRevisionStatus.STAGING,
            status = C03ProductionCandidateRevisionStatus.ACTIVE,
            activatedAtEpochMillis = activatedAtEpochMillis,
        )
        if (activated != 1) {
            deleteStagingRevision(sourceId, revisionNumber)
            return C03ProductionCandidateActivationResult.Superseded
        }
        check(updateActiveRevision(sourceId, revisionNumber) == 1) {
            "C03 candidate active revision pointer update failed."
        }
        deleteRevisionsExcept(
            sourceId = sourceId,
            currentRevision = revisionNumber,
            previousRevision = previousRevision,
        )
        return C03ProductionCandidateActivationResult.Published
    }

    @Transaction
    open suspend fun discardRevision(
        sourceId: String,
        revisionNumber: Long,
    ) {
        deleteStagingRevision(sourceId, revisionNumber)
    }

    @Transaction
    open suspend fun revisionExists(
        sourceId: String,
        revisionNumber: Long,
    ): Boolean = revisionCount(sourceId, revisionNumber) > 0

    @Transaction
    open suspend fun rowCounts(): C03ProductionCandidateRowCounts = C03ProductionCandidateRowCounts(
        payloadRows = payloadRowCount(),
        searchPayloadRows = searchPayloadRowCount(),
        membershipRows = membershipRowCount(),
        searchDocumentRows = searchDocumentRowCount(),
    )

    @Transaction
    open suspend fun compactOrphans(limit: Int): C03ProductionCandidateCompactionResult {
        require(limit > 0)
        val payloadIds = orphanPayloadIds(limit)
        val payloadRowsDeleted = if (payloadIds.isEmpty()) 0 else deletePayloads(payloadIds)
        val searchPayloadIds = orphanSearchPayloadIds(limit)
        val searchPayloadRowsDeleted = if (searchPayloadIds.isEmpty()) {
            0
        } else {
            deleteSearchPayloads(searchPayloadIds)
        }
        return C03ProductionCandidateCompactionResult(
            payloadRowsDeleted = payloadRowsDeleted,
            searchPayloadRowsDeleted = searchPayloadRowsDeleted,
        )
    }

    private fun searchDocuments(
        payload: C03ProductionCandidateSearchPayloadEntity,
    ): List<C03ProductionCandidateSearchDocumentEntity> = buildList {
        add(
            C03ProductionCandidateSearchDocumentEntity(
                documentKey = "${payload.searchPayloadId}:raw-name",
                searchPayloadId = payload.searchPayloadId,
                kind = C03ProductionCandidateSearchKind.RAW_NAME,
                text = payload.rawName,
            ),
        )
        payload.groupTitle?.takeIf(String::isNotBlank)?.let { groupTitle ->
            add(
                C03ProductionCandidateSearchDocumentEntity(
                    documentKey = "${payload.searchPayloadId}:group",
                    searchPayloadId = payload.searchPayloadId,
                    kind = C03ProductionCandidateSearchKind.GROUP,
                    text = groupTitle,
                ),
            )
        }
        payload.channelNumber?.takeIf(String::isNotBlank)?.let { channelNumber ->
            add(
                C03ProductionCandidateSearchDocumentEntity(
                    documentKey = "${payload.searchPayloadId}:number",
                    searchPayloadId = payload.searchPayloadId,
                    kind = C03ProductionCandidateSearchKind.NUMBER,
                    text = channelNumber,
                ),
            )
        }
    }

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}

private object C03ProductionCandidateContentAddress {
    fun payloadId(
        sourceId: String,
        logicalChannelId: String,
        contentHashVersion: Int,
        contentHash: String,
    ): String = sha256(
        "c03-production-payload-v1",
        sourceId,
        logicalChannelId,
        contentHashVersion.toString(),
        contentHash,
    )

    fun searchPayloadId(
        searchHashVersion: Int,
        searchContentHash: String,
    ): String = sha256(
        "c03-production-search-payload-v1",
        searchHashVersion.toString(),
        searchContentHash,
    )

    private fun sha256(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            val bytes = part.toByteArray(StandardCharsets.UTF_8)
            digest.update((bytes.size ushr 24).toByte())
            digest.update((bytes.size ushr 16).toByte())
            digest.update((bytes.size ushr 8).toByte())
            digest.update(bytes.size.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }
}
