package app.muxtv.catalog.importer

import app.muxtv.common.catalog.CatalogFingerprintCodec
import app.muxtv.common.catalog.CatalogPayloadFingerprintInput

internal data class CatalogEntryPayloadFingerprint(
    val contentHash: String,
    val searchContentHash: String,
    val contentHashVersion: Int = CONTENT_HASH_VERSION,
    val searchHashVersion: Int = SEARCH_HASH_VERSION,
) {
    init {
        require(contentHash.isNotBlank())
        require(searchContentHash.isNotBlank())
        require(contentHashVersion > 0)
        require(searchHashVersion > 0)
    }

    companion object {
        const val CONTENT_HASH_VERSION = 1
        const val SEARCH_HASH_VERSION = 1
    }
}

/**
 * Maps importer-specific entries into the canonical production fingerprint contract.
 *
 * [CatalogFingerprintCodec] owns the byte-level domains/framing shared with database migration.
 * This adapter deliberately keeps importer types out of the common contract.
 */
internal class CatalogEntryPayloadFingerprinter(
    private val codec: CatalogFingerprintCodec = CatalogFingerprintCodec(),
) {
    fun fingerprint(
        entry: CatalogImportEntry,
        identity: CatalogEntryIdentity,
    ): CatalogEntryPayloadFingerprint {
        val fingerprint = codec.fingerprint(
            CatalogPayloadFingerprintInput(
                providerKey = identity.providerKey,
                canonicalChannelId = identity.canonicalChannelId,
                rawName = entry.displayName,
                tvgId = entry.tvgId,
                tvgName = entry.tvgName,
                logoUrl = entry.logoUrl,
                groupTitle = entry.groupTitle,
                channelNumber = entry.channelNumber,
                catchupMode = entry.catchupMode,
                catchupSource = entry.catchupSource,
                catchupDays = entry.catchupDays,
                catchupCorrection = entry.catchupCorrection,
                locator = entry.playbackReference,
                userAgent = entry.userAgent,
                referrer = entry.referrer,
            ),
        )

        return CatalogEntryPayloadFingerprint(
            contentHash = fingerprint.contentHash,
            searchContentHash = fingerprint.searchContentHash,
            contentHashVersion = fingerprint.contentHashVersion,
            searchHashVersion = fingerprint.searchHashVersion,
        )
    }
}
