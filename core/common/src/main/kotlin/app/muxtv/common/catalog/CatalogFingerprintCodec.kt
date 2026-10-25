package app.muxtv.common.catalog

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class CatalogPayloadFingerprintInput(
    val providerKey: String,
    val canonicalChannelId: String,
    val rawName: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val catchupMode: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val catchupCorrection: String?,
    val locator: String,
    val userAgent: String?,
    val referrer: String?,
) {
    init {
        require(providerKey.isNotBlank())
        require(canonicalChannelId.isNotBlank())
        require(rawName.isNotBlank())
        require(locator.isNotBlank())
    }

    override fun toString(): String = "CatalogPayloadFingerprintInput(<redacted>)"
}

data class CatalogPayloadFingerprint(
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
 * Canonical production codec for C03 logical identity and immutable catalog/search fingerprints.
 *
 * One codec instance is intentionally not thread-safe because [MessageDigest] is mutable. Scope it
 * to one importer or one migration/backfill worker. Raw payload fields never appear in the result.
 */
class CatalogFingerprintCodec(
    private val logicalDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
    private val contentDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
    private val searchDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
    private val payloadAddressDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
    private val searchPayloadAddressDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
) {
    fun logicalChannelId(
        sourceId: String,
        providerKey: String,
    ): String {
        require(sourceId.isNotBlank())
        require(providerKey.isNotBlank())

        logicalDigest.reset()
        logicalDigest.updateFrame(LOGICAL_CHANNEL_ID_DOMAIN)
        logicalDigest.updateFrame(sourceId)
        logicalDigest.updateFrame(providerKey)
        return logicalDigest.digest().toLowerHex()
    }

    fun payloadId(
        sourceId: String,
        logicalChannelId: String,
        contentHashVersion: Int,
        contentHash: String,
    ): String {
        require(sourceId.isNotBlank())
        require(logicalChannelId.isNotBlank())
        require(contentHashVersion > 0)
        require(contentHash.isNotBlank())

        payloadAddressDigest.reset()
        payloadAddressDigest.updateFrame(PAYLOAD_ID_DOMAIN)
        payloadAddressDigest.updateFrame(sourceId)
        payloadAddressDigest.updateFrame(logicalChannelId)
        payloadAddressDigest.updateFrame(contentHashVersion.toString())
        payloadAddressDigest.updateFrame(contentHash)
        return payloadAddressDigest.digest().toLowerHex()
    }

    fun searchPayloadId(
        searchHashVersion: Int,
        searchContentHash: String,
    ): String {
        require(searchHashVersion > 0)
        require(searchContentHash.isNotBlank())

        searchPayloadAddressDigest.reset()
        searchPayloadAddressDigest.updateFrame(SEARCH_PAYLOAD_ID_DOMAIN)
        searchPayloadAddressDigest.updateFrame(searchHashVersion.toString())
        searchPayloadAddressDigest.updateFrame(searchContentHash)
        return searchPayloadAddressDigest.digest().toLowerHex()
    }

    fun fingerprint(input: CatalogPayloadFingerprintInput): CatalogPayloadFingerprint =
        CatalogPayloadFingerprint(
            contentHash = contentDigest.fingerprint(CONTENT_DOMAIN) {
                field("providerKey", input.providerKey)
                field("canonicalChannelId", input.canonicalChannelId)
                field("rawName", input.rawName)
                field("tvgId", input.tvgId)
                field("tvgName", input.tvgName)
                field("logoUrl", input.logoUrl)
                field("groupTitle", input.groupTitle)
                field("channelNumber", input.channelNumber)
                field("catchupMode", input.catchupMode)
                field("catchupSource", input.catchupSource)
                field("catchupDays", input.catchupDays)
                field("catchupCorrection", input.catchupCorrection)
                field("locator", input.locator)
                field("userAgent", input.userAgent)
                field("referrer", input.referrer)
            },
            searchContentHash = searchDigest.fingerprint(SEARCH_DOMAIN) {
                field("canonicalChannelId", input.canonicalChannelId)
                field("rawName", input.rawName)
                field("groupTitle", input.groupTitle)
                field("channelNumber", input.channelNumber)
            },
        )

    private companion object {
        const val SHA_256 = "SHA-256"
        const val LOGICAL_CHANNEL_ID_DOMAIN = "catalog-logical-v1"
        const val CONTENT_DOMAIN = "catalog-content-v1"
        const val SEARCH_DOMAIN = "catalog-search-content-v1"
        const val PAYLOAD_ID_DOMAIN = "catalog-payload-id-v1"
        const val SEARCH_PAYLOAD_ID_DOMAIN = "catalog-search-payload-id-v1"
    }
}

private class DigestFrameWriter(
    private val digest: MessageDigest,
) {
    fun field(
        tag: String,
        value: String?,
    ) {
        digest.updateFrame(tag)
        if (value == null) {
            digest.update(NULL_MARKER)
        } else {
            digest.update(VALUE_MARKER)
            digest.updateFrame(value)
        }
    }

    fun field(
        tag: String,
        value: Int?,
    ) = field(tag, value?.toString())

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
    return digest().toLowerHex()
}

private fun MessageDigest.updateFrame(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update((bytes.size ushr 24).toByte())
    update((bytes.size ushr 16).toByte())
    update((bytes.size ushr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

private fun ByteArray.toLowerHex(): String {
    val output = CharArray(size * 2)
    var outputIndex = 0

    forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        output[outputIndex++] = HEX[unsigned ushr 4]
        output[outputIndex++] = HEX[unsigned and 0x0f]
    }

    return output.concatToString()
}

private val HEX = "0123456789abcdef".toCharArray()
