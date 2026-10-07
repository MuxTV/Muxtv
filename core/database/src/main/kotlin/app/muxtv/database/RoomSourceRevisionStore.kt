package app.muxtv.database

import app.muxtv.common.catalog.CatalogFingerprintCodec
import app.muxtv.common.catalog.CatalogPayloadFingerprintInput
import app.muxtv.common.tracing.MuxTvTrace
import app.muxtv.common.tracing.MuxTvTraceSection
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class RoomSourceRevisionStore(
    private val dao: SourceRevisionDao,
) : SourceRevisionStore {
    override suspend fun upsertSource(source: SourceDefinition) {
        dao.upsertSource(source)
    }

    override suspend fun nextRevisionNumber(sourceId: String): Long {
        require(sourceId.isNotBlank())
        return dao.nextRevisionNumber(sourceId)
    }

    override suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    ) {
        require(sourceId.isNotBlank())
        dao.insertRevision(
            SourceRevisionEntity(
                sourceId = sourceId,
                revisionNumber = revisionNumber,
                status = SourceRevisionEntity.STATUS_STAGING,
                startedAtEpochMillis = startedAtEpochMillis,
            ),
        )
    }

    override suspend fun stageBatch(
        sourceId: String,
        revisionNumber: Long,
        entries: List<StagedCatalogEntry>,
    ) {
        require(sourceId.isNotBlank())
        require(revisionNumber > 0)
        require(entries.size <= MAX_BATCH_SIZE) {
            "Catalog staging batch exceeds the supported size."
        }
        if (entries.isEmpty()) return

        MuxTvTrace.global.coroutineSection(MuxTvTraceSection.CATALOG_STAGE) {
            val canonicalChannels = ArrayList<CanonicalChannelEntity>(entries.size)
            val providerChannels = ArrayList<ProviderChannelEntity>(entries.size)
            val streamVariants = ArrayList<StreamVariantEntity>(entries.size)
            val catalogSearchPayloads = ArrayList<CatalogSearchPayloadEntity>(entries.size)
            val catalogPayloads = ArrayList<CatalogPayloadEntity>(entries.size)
            val membershipDrafts = ArrayList<SourceRevisionMembershipDraft>(entries.size)
            val fingerprintCodec = CatalogFingerprintCodec()

            entries.forEach { entry ->
                canonicalChannels += CanonicalChannelEntity(
                    id = entry.canonicalChannelId,
                    displayName = entry.canonicalDisplayName,
                )
                providerChannels += ProviderChannelEntity(
                    id = entry.providerChannelId,
                    sourceId = sourceId,
                    revisionNumber = revisionNumber,
                    providerKey = entry.providerKey,
                    rawName = entry.rawName,
                    tvgId = entry.tvgId,
                    tvgName = entry.tvgName,
                    logoUrl = entry.logoUrl,
                    groupTitle = entry.groupTitle,
                    channelNumber = entry.channelNumber,
                    catchupMode = entry.catchupMode,
                    catchupSource = entry.catchupSource,
                    catchupDays = entry.catchupDays,
                    catchupCorrection = entry.catchupCorrection,
                )
                streamVariants += StreamVariantEntity(
                    id = entry.streamVariantId,
                    providerChannelId = entry.providerChannelId,
                    canonicalChannelId = entry.canonicalChannelId,
                    locator = entry.locator,
                    userAgent = entry.userAgent,
                    referrer = entry.referrer,
                )

                val logicalChannelId = fingerprintCodec.logicalChannelId(
                    sourceId = sourceId,
                    providerKey = entry.providerKey,
                )
                val fingerprint = fingerprintCodec.fingerprint(
                    CatalogPayloadFingerprintInput(
                        providerKey = entry.providerKey,
                        canonicalChannelId = entry.canonicalChannelId,
                        rawName = entry.rawName,
                        tvgId = entry.tvgId,
                        tvgName = entry.tvgName,
                        logoUrl = entry.logoUrl,
                        groupTitle = entry.groupTitle,
                        channelNumber = entry.channelNumber,
                        catchupMode = entry.catchupMode,
                        catchupSource = entry.catchupSource,
                        catchupDays = entry.catchupDays,
                        catchupCorrection = entry.catchupCorrection,
                        locator = entry.locator,
                        userAgent = entry.userAgent,
                        referrer = entry.referrer,
                    ),
                )
                val searchPayloadId = fingerprintCodec.searchPayloadId(
                    searchHashVersion = fingerprint.searchHashVersion,
                    searchContentHash = fingerprint.searchContentHash,
                )
                val payloadId = fingerprintCodec.payloadId(
                    sourceId = sourceId,
                    logicalChannelId = logicalChannelId,
                    contentHashVersion = fingerprint.contentHashVersion,
                    contentHash = fingerprint.contentHash,
                )

                catalogSearchPayloads += CatalogSearchPayloadEntity(
                    searchPayloadId = searchPayloadId,
                    searchContentHash = fingerprint.searchContentHash,
                    searchHashVersion = fingerprint.searchHashVersion,
                    canonicalChannelId = entry.canonicalChannelId,
                    rawName = entry.rawName,
                    groupTitle = entry.groupTitle,
                    channelNumber = entry.channelNumber,
                )
                catalogPayloads += CatalogPayloadEntity(
                    payloadId = payloadId,
                    sourceId = sourceId,
                    logicalChannelId = logicalChannelId,
                    contentHash = fingerprint.contentHash,
                    contentHashVersion = fingerprint.contentHashVersion,
                    canonicalChannelId = entry.canonicalChannelId,
                    providerKey = entry.providerKey,
                    rawName = entry.rawName,
                    tvgId = entry.tvgId,
                    tvgName = entry.tvgName,
                    logoUrl = entry.logoUrl,
                    groupTitle = entry.groupTitle,
                    channelNumber = entry.channelNumber,
                    catchupMode = entry.catchupMode,
                    catchupSource = entry.catchupSource,
                    catchupDays = entry.catchupDays,
                    catchupCorrection = entry.catchupCorrection,
                    locator = entry.locator,
                    userAgent = entry.userAgent,
                    referrer = entry.referrer,
                    searchPayloadId = searchPayloadId,
                )
                membershipDrafts += SourceRevisionMembershipDraft(
                    logicalChannelId = logicalChannelId,
                    payloadId = payloadId,
                    variantId = entry.streamVariantId,
                )
            }

            dao.stageCatalogBatch(
                sourceId = sourceId,
                revisionNumber = revisionNumber,
                canonicalChannels = canonicalChannels,
                providerChannels = providerChannels,
                streamVariants = streamVariants,
                catalogSearchPayloads = catalogSearchPayloads,
                catalogPayloads = catalogPayloads,
                membershipDrafts = membershipDrafts,
            )
        }
    }

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = finalizeTerminalMutation(
        dao.activateRevision(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            activatedAtEpochMillis = activatedAtEpochMillis,
            statistics = statistics,
        ),
    )

    override suspend fun activateIfCredentialMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = finalizeTerminalMutation(
        dao.activateRevisionIfCredentialMatches(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            expectedCredentialRef = expectedCredentialRef,
            activatedAtEpochMillis = activatedAtEpochMillis,
            statistics = statistics,
        ),
    )

    override suspend fun activateIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedCredentialRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = finalizeTerminalMutation(
        dao.activateRevisionIfRefreshOwnerMatches(
            sourceId = sourceId,
            revisionNumber = revisionNumber,
            expectedCredentialRef = expectedCredentialRef,
            expectedRunToken = expectedRunToken,
            activatedAtEpochMillis = activatedAtEpochMillis,
            statistics = statistics,
        ),
    )

    override suspend fun discard(
        sourceId: String,
        revisionNumber: Long,
    ) {
        dao.discardRevision(sourceId, revisionNumber)
        drainCatalogPayloadOrphans()
    }

    override suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult {
        require(sourceId.isNotBlank())
        require(expectedCredentialRef.isNotBlank())
        val result = dao.removeInactiveSource(
            sourceId = sourceId,
            expectedCredentialRef = expectedCredentialRef,
        )
        if (result == InactiveSourceRemovalResult.Removed) {
            drainCatalogPayloadOrphans()
        }
        return result
    }

    private suspend fun finalizeTerminalMutation(
        result: SourceRevisionActivationResult,
    ): SourceRevisionActivationResult {
        if (
            result is SourceRevisionActivationResult.Activated ||
            result == SourceRevisionActivationResult.Superseded
        ) {
            drainCatalogPayloadOrphans()
        }
        return result
    }

    private suspend fun drainCatalogPayloadOrphans() {
        // Publication/discard is already committed before this helper runs. Cleanup therefore uses
        // independent bounded transactions and can drain arbitrarily large unreachable backlogs
        // without extending the atomic active-pointer transaction.
        withContext(NonCancellable) {
            while (dao.compactCatalogPayloadOrphansBatch().totalRowsDeleted > 0) {
                // Each batch is capped by SourceRevisionDao and remains below old-edge SQLite
                // variable limits. Continue until storage is bounded by reachable revisions.
            }
        }
    }

    private companion object {
        const val MAX_BATCH_SIZE = 500
    }
}
