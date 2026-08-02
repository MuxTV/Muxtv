package app.muxtv.catalog.importer

import app.muxtv.catalog.ingest.M3uEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

internal data class CatalogEntryIdentity(
    val providerKey: String,
    val providerChannelId: String,
    val canonicalChannelId: String,
    val streamVariantId: String,
)

/**
 * Generates the existing stable catalog identifiers while reusing one digest inside one import.
 *
 * Instances are deliberately scoped to a single import. [MessageDigest] and the reusable output
 * buffers are mutable and must not be shared between concurrent imports.
 */
internal class CatalogEntryIdentityFactory(
    private val messageDigest: MessageDigest = MessageDigest.getInstance(SHA_256),
) {
    private val digestOutput = ByteArray(SHA_256_BYTES)
    private val hexOutput = CharArray(SHA_256_HEX_CHARACTERS)

    fun create(
        entry: M3uEntry,
        sourceId: String,
        revisionNumber: Long,
        ordinal: Long,
    ): CatalogEntryIdentity {
        val providerKey = entry.providerKey()
        val canonicalScope = if (!entry.tvgId.isNullOrBlank()) {
            "global|$providerKey"
        } else {
            "source|$sourceId|$providerKey"
        }

        return CatalogEntryIdentity(
            providerKey = providerKey,
            providerChannelId = stableId("provider|$sourceId|$revisionNumber|$ordinal"),
            canonicalChannelId = stableId("canonical|$canonicalScope"),
            streamVariantId = stableId("stream|$sourceId|$revisionNumber|$ordinal"),
        )
    }

    private fun stableId(value: String): String {
        messageDigest.reset()
        messageDigest.update(value.toByteArray(StandardCharsets.UTF_8))
        val digestSize = messageDigest.digest(digestOutput, 0, digestOutput.size)
        check(digestSize == SHA_256_BYTES) { "Unexpected SHA-256 digest length." }

        var outputIndex = 0
        for (index in 0 until digestSize) {
            val unsigned = digestOutput[index].toInt() and 0xff
            hexOutput[outputIndex++] = HEX[unsigned ushr 4]
            hexOutput[outputIndex++] = HEX[unsigned and 0x0f]
        }

        return hexOutput.concatToString()
    }

    private companion object {
        const val SHA_256 = "SHA-256"
        const val SHA_256_BYTES = 32
        const val SHA_256_HEX_CHARACTERS = SHA_256_BYTES * 2
        val HEX = "0123456789abcdef".toCharArray()
    }
}

private fun M3uEntry.providerKey(): String {
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

private val WHITESPACE = Regex("\\s+")