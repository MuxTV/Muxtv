package app.muxtv.catalog.importer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal data class CatalogEntryIdentity(
    val providerKey: String,
    val logicalChannelId: String,
    val providerChannelId: String,
    val canonicalChannelId: String,
    val streamVariantId: String,
)

/**
 * Generates stable catalog identifiers while reusing one digest inside one import.
 *
 * Instances are deliberately scoped to a single import. [MessageDigest] is mutable and must not be
 * shared between concurrent imports.
 */
internal class CatalogEntryIdentityFactory(
    private val messageDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
) {
    fun create(
        entry: CatalogImportEntry,
        sourceId: String,
        revisionNumber: Long,
        ordinal: Long,
    ): CatalogEntryIdentity {
        val providerKey = entry.providerKey()
        val canonicalScope = when {
            entry.providerStableId != null -> "source|$sourceId|$providerKey"
            !entry.tvgId.isNullOrBlank() -> "global|$providerKey"
            else -> "source|$sourceId|$providerKey"
        }

        return CatalogEntryIdentity(
            providerKey = providerKey,
            logicalChannelId = framedStableId(
                domain = LOGICAL_CHANNEL_ID_DOMAIN,
                sourceId,
                providerKey,
            ),
            providerChannelId = stableId("provider|$sourceId|$revisionNumber|$ordinal"),
            canonicalChannelId = stableId("canonical|$canonicalScope"),
            streamVariantId = stableId("stream|$sourceId|$revisionNumber|$ordinal"),
        )
    }

    private fun framedStableId(
        domain: String,
        vararg values: String,
    ): String {
        messageDigest.reset()
        messageDigest.updateFrame(domain)
        values.forEach(messageDigest::updateFrame)
        return messageDigest.digest().toHex()
    }

    private fun stableId(value: String): String {
        messageDigest.reset()
        return messageDigest.digest(value.toByteArray(StandardCharsets.UTF_8)).toHex()
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val LOGICAL_CHANNEL_ID_DOMAIN = "catalog-logical-v1"
    }
}

private fun MessageDigest.updateFrame(value: String) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    update((bytes.size ushr 24).toByte())
    update((bytes.size ushr 16).toByte())
    update((bytes.size ushr 8).toByte())
    update(bytes.size.toByte())
    update(bytes)
}

internal fun ByteArray.toHex(): String {
    val output = CharArray(size * 2)
    var outputIndex = 0

    forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        output[outputIndex++] = HEX[unsigned ushr 4]
        output[outputIndex++] = HEX[unsigned and 0x0f]
    }

    return output.concatToString()
}

private fun CatalogImportEntry.providerKey(): String {
    val stableProviderId = providerStableId?.trim()
    if (stableProviderId != null) return "provider:$stableProviderId"

    val stableTvgId = tvgId?.normalizeIdentityPart()
    if (!stableTvgId.isNullOrEmpty()) return "tvg:$stableTvgId"

    return buildString {
        append("name:")
        append((tvgName ?: displayName).normalizeIdentityPart())
        append("|group:")
        append(groupTitle.orEmpty().normalizeIdentityPart())
        append("|number:")
        append(channelNumber.orEmpty().normalizeIdentityPart())
    }
}

private fun String.normalizeIdentityPart(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace(WHITESPACE, " ")

private val HEX = "0123456789abcdef".toCharArray()
private val WHITESPACE = Regex("\\s+")
