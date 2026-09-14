package app.muxtv.catalog.importer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
 * Produces versioned content-addressing fingerprints for one catalog import entry.
 *
 * Instances are scoped to one import because [MessageDigest] is mutable. The digests contain only
 * hashes; callers must not surface the raw payload fields used to build them in evidence/logging.
 */
internal class CatalogEntryPayloadFingerprinter(
    private val contentDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
    private val searchDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
) {
    fun fingerprint(
        entry: CatalogImportEntry,
        identity: CatalogEntryIdentity,
    ): CatalogEntryPayloadFingerprint = CatalogEntryPayloadFingerprint(
        contentHash = contentDigest.fingerprint(CONTENT_DOMAIN) {
            field("providerKey", identity.providerKey)
            field("canonicalChannelId", identity.canonicalChannelId)
            field("rawName", entry.displayName)
            field("tvgId", entry.tvgId)
            field("tvgName", entry.tvgName)
            field("logoUrl", entry.logoUrl)
            field("groupTitle", entry.groupTitle)
            field("channelNumber", entry.channelNumber)
            field("catchupMode", entry.catchupMode)
            field("catchupSource", entry.catchupSource)
            field("catchupDays", entry.catchupDays)
            field("catchupCorrection", entry.catchupCorrection)
            field("locator", entry.playbackReference)
            field("userAgent", entry.userAgent)
            field("referrer", entry.referrer)
        },
        searchContentHash = searchDigest.fingerprint(SEARCH_DOMAIN) {
            field("canonicalChannelId", identity.canonicalChannelId)
            field("rawName", entry.displayName)
            field("groupTitle", entry.groupTitle)
            field("channelNumber", entry.channelNumber)
        },
    )

    private companion object {
        const val SHA_256 = "SHA-256"
        const val CONTENT_DOMAIN = "catalog-content-v1"
        const val SEARCH_DOMAIN = "catalog-search-content-v1"
    }
}

private class DigestFrameWriter(
    private val digest: MessageDigest,
) {
    fun field(
        tag: String,
        value: String?,
    ) {
        frame(tag)
        if (value == null) {
            digest.update(NULL_MARKER)
        } else {
            digest.update(VALUE_MARKER)
            frame(value)
        }
    }

    fun field(
        tag: String,
        value: Int?,
    ) = field(tag, value?.toString())

    private fun frame(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }

    private companion object {
        const val NULL_MARKER: Byte = 0
        const val VALUE_MARKER: Byte = 1
    }
}

private inline fun MessageDigest.fingerprint(
    domain: String,
    populate: DigestFrameWriter.() -> Unit,
): String {
    reset()
    val writer = DigestFrameWriter(this)
    writer.field("domain", domain)
    writer.populate()
    return digest().toHex()
}
