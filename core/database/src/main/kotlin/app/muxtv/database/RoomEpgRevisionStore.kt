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

    override suspend fun beginRevision(
        sourceId: String,
        startedAtEpochMillis: Long,
    ): Long = dao.beginNextRevision(
        sourceId = sourceId,
        startedAtEpochMillis = startedAtEpochMillis,
    )

    override suspend fun stageBatch(
        channels: List<EpgChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
    ) {
        validateEpgStageBatch(channels, programmes)
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

    override suspend fun activateRevisionIfAccessMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedAccessRef: String,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult = dao.activateRevisionIfAccessMatches(
        sourceId = sourceId,
        revisionNumber = revisionNumber,
        expectedAccessRef = expectedAccessRef,
        activatedAtEpochMillis = activatedAtEpochMillis,
        statistics = statistics,
    )

    override suspend fun activateRevisionIfRefreshOwnerMatches(
        sourceId: String,
        revisionNumber: Long,
        expectedAccessRef: String,
        expectedRunToken: String,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult = dao.activateRevisionIfRefreshOwnerMatches(
        sourceId = sourceId,
        revisionNumber = revisionNumber,
        expectedAccessRef = expectedAccessRef,
        expectedRunToken = expectedRunToken,
        activatedAtEpochMillis = activatedAtEpochMillis,
        statistics = statistics,
    )

    override suspend fun discardRevision(sourceId: String, revisionNumber: Long) {
        dao.discardRevision(sourceId, revisionNumber)
    }
}
