package app.muxtv.database

internal class RoomEpgRevisionStore(
    private val dao: EpgRevisionDao,
) : EpgRevisionStore {
    override suspend fun upsertSource(source: EpgSourceDefinition) {
        dao.insertSource(
            EpgSourceEntity(
                id = source.id,
                name = source.name,
                providerSourceId = source.providerSourceId,
                accessRef = source.accessRef,
                defaultZoneId = source.defaultZoneId,
            ),
        )
    }

    override suspend fun nextRevisionNumber(sourceId: String): Long =
        dao.nextRevisionNumber(sourceId)

    override suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    ) {
        dao.insertRevision(
            EpgRevisionEntity(
                sourceId = sourceId,
                revisionNumber = revisionNumber,
                status = EpgRevisionEntity.STATUS_STAGING,
                startedAtEpochMillis = startedAtEpochMillis,
            ),
        )
    }

    override suspend fun stageBatch(
        channels: List<EpgChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
    ) {
        dao.stageBatch(channels, programmes)
    }

    override suspend fun activateRevision(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult = dao.activateRevision(
        sourceId = sourceId,
        revisionNumber = revisionNumber,
        activatedAtEpochMillis = activatedAtEpochMillis,
        statistics = statistics,
    )

    override suspend fun discardRevision(sourceId: String, revisionNumber: Long) {
        dao.discardRevision(sourceId, revisionNumber)
    }
}
