package app.muxtv.database

data class EpgSourceDefinition(
    val id: String,
    val name: String,
    val providerSourceId: String?,
    val accessRef: String?,
    val defaultZoneId: String?,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
    }

    override fun toString(): String =
        "EpgSourceDefinition(providerLinked=${providerSourceId != null}, " +
            "accessRefPresent=${accessRef != null}, defaultZonePresent=${defaultZoneId != null})"
}

interface EpgRevisionStore {
    suspend fun upsertSource(source: EpgSourceDefinition)
    suspend fun nextRevisionNumber(sourceId: String): Long
    suspend fun beginRevision(sourceId: String, revisionNumber: Long, startedAtEpochMillis: Long)
    suspend fun stageBatch(
        channels: List<EpgChannelEntity>,
        programmes: List<EpgProgrammeEntity>,
    )
    suspend fun activateRevision(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: EpgRevisionStatistics,
    ): EpgRevisionActivationResult
    suspend fun discardRevision(sourceId: String, revisionNumber: Long)
}
