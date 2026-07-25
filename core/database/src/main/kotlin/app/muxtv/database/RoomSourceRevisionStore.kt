package app.muxtv.database

import androidx.tracing.Trace
import java.util.concurrent.atomic.AtomicInteger

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

        traceAsyncSection(TRACE_STAGE_BATCH) {
            val canonicalChannels = ArrayList<CanonicalChannelEntity>(entries.size)
            val providerChannels = ArrayList<ProviderChannelEntity>(entries.size)
            val streamVariants = ArrayList<StreamVariantEntity>(entries.size)

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
            }

            dao.stageCatalogBatch(
                canonicalChannels = canonicalChannels,
                providerChannels = providerChannels,
                streamVariants = streamVariants,
            )
        }
    }

    override suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult = dao.activateRevision(
        sourceId = sourceId,
        revisionNumber = revisionNumber,
        activatedAtEpochMillis = activatedAtEpochMillis,
        statistics = statistics,
    )

    override suspend fun discard(
        sourceId: String,
        revisionNumber: Long,
    ) {
        dao.discardRevision(sourceId, revisionNumber)
    }

    override suspend fun removeInactiveSource(
        sourceId: String,
        expectedCredentialRef: String,
    ): InactiveSourceRemovalResult {
        require(sourceId.isNotBlank())
        require(expectedCredentialRef.isNotBlank())
        return dao.removeInactiveSource(
            sourceId = sourceId,
            expectedCredentialRef = expectedCredentialRef,
        )
    }

    private companion object {
        const val MAX_BATCH_SIZE = 500
    }
}

private suspend inline fun <T> traceAsyncSection(
    sectionName: String,
    block: () -> T,
): T {
    if (!Trace.isEnabled()) return block()

    val cookie = TRACE_COOKIE.incrementAndGet()
    Trace.beginAsyncSection(sectionName, cookie)
    return try {
        block()
    } finally {
        Trace.endAsyncSection(sectionName, cookie)
    }
}

private const val TRACE_STAGE_BATCH = "MuxTV.catalog.stageBatch"
private val TRACE_COOKIE = AtomicInteger()
