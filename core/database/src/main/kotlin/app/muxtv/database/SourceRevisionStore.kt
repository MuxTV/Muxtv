package app.muxtv.database

data class SourceDefinition(
    val id: String,
    val name: String,
    val credentialRef: String? = null,
) {
    init {
        require(id.isNotBlank())
        require(name.isNotBlank())
    }
}

data class StagedCatalogEntry(
    val providerChannelId: String,
    val providerKey: String,
    val rawName: String,
    val canonicalChannelId: String,
    val canonicalDisplayName: String,
    val streamVariantId: String,
    val locator: String,
    val tvgId: String? = null,
    val tvgName: String? = null,
    val logoUrl: String? = null,
    val groupTitle: String? = null,
    val channelNumber: String? = null,
    val catchupMode: String? = null,
    val catchupSource: String? = null,
    val catchupDays: Int? = null,
    val catchupCorrection: String? = null,
    val userAgent: String? = null,
    val referrer: String? = null,
) {
    init {
        require(providerChannelId.isNotBlank())
        require(providerKey.isNotBlank())
        require(rawName.isNotBlank())
        require(canonicalChannelId.isNotBlank())
        require(canonicalDisplayName.isNotBlank())
        require(streamVariantId.isNotBlank())
        require(locator.isNotBlank())
    }
}

data class SourceRevisionStatistics(
    val parsedEntries: Int,
    val skippedEntries: Int,
    val warningCount: Int,
) {
    init {
        require(parsedEntries >= 0)
        require(skippedEntries >= 0)
        require(warningCount >= 0)
    }
}

sealed interface SourceRevisionActivationResult {
    data class Activated(
        val revisionNumber: Long,
        val previousRevisionNumber: Long,
        val entryCount: Int,
    ) : SourceRevisionActivationResult

    data object EmptyRevisionRejected : SourceRevisionActivationResult
}

interface SourceRevisionStore {
    suspend fun upsertSource(source: SourceDefinition)

    suspend fun nextRevisionNumber(sourceId: String): Long

    suspend fun beginRevision(
        sourceId: String,
        revisionNumber: Long,
        startedAtEpochMillis: Long,
    )

    suspend fun stageBatch(
        sourceId: String,
        revisionNumber: Long,
        entries: List<StagedCatalogEntry>,
    )

    suspend fun activate(
        sourceId: String,
        revisionNumber: Long,
        activatedAtEpochMillis: Long,
        statistics: SourceRevisionStatistics,
    ): SourceRevisionActivationResult

    suspend fun discard(
        sourceId: String,
        revisionNumber: Long,
    )
}
