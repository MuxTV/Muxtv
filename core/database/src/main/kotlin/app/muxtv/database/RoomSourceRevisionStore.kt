package app.muxtv.database

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

        dao.insertCanonicalChannels(
            entries.map { entry ->
                CanonicalChannelEntity(
                    id = entry.canonicalChannelId,
                    displayName = entry.canonicalDisplayName,
                )
            },
        )
        dao.insertProviderChannels(
            entries.map { entry ->
                ProviderChannelEntity(
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
            },
        )
        dao.insertStreamVariants(
            entries.map { entry ->
                StreamVariantEntity(
                    id = entry.streamVariantId,
                    providerChannelId = entry.providerChannelId,
                    canonicalChannelId = entry.canonicalChannelId,
                    locator = entry.locator,
                    userAgent = entry.userAgent,
                    referrer = entry.referrer,
                )
            },
        )
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

    private companion object {
        const val MAX_BATCH_SIZE = 500
    }
}
