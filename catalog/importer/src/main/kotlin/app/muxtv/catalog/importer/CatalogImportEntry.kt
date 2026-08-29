package app.muxtv.catalog.importer

class CatalogRevisionImportRequest(
    val sourceId: String,
    val sourceName: String,
    val credentialRef: String? = null,
    val refreshRunToken: String? = null,
    val sourceOwnership: CatalogImportSourceOwnership = CatalogImportSourceOwnership.UPSERT_METADATA,
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceName.isNotBlank())
        require(credentialRef == null || credentialRef.isNotBlank())
        require(refreshRunToken == null || refreshRunToken.isNotBlank())
        if (sourceOwnership == CatalogImportSourceOwnership.EXISTING_REMOTE_BINDING) {
            require(!credentialRef.isNullOrBlank()) {
                "Existing remote catalog imports require an opaque credential binding."
            }
        }
    }
}

class CatalogImportEntry(
    val providerStableId: String?,
    val displayName: String,
    val playbackReference: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val catchupMode: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val catchupCorrection: String?,
    val userAgent: String?,
    val referrer: String?,
) {
    init {
        require(providerStableId == null || providerStableId.isNotBlank())
        require(displayName.isNotBlank())
        require(playbackReference.isNotBlank())
    }
}

interface CatalogImportEntrySink {
    suspend fun onEntry(entry: CatalogImportEntry)
}

interface CatalogImportFeed {
    suspend fun streamTo(sink: CatalogImportEntrySink): CatalogImportFeedReport
}

data class CatalogImportFeedReport(
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
