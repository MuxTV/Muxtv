package app.muxtv.database.measurement

import app.muxtv.database.StagedCatalogEntry
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object CatalogDatabaseFixtureDigest {
    fun sha256(entries: List<StagedCatalogEntry>): String {
        require(entries.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateInt(entries.size)
        entries.forEach { entry ->
            digest.updateField(entry.providerChannelId)
            digest.updateField(entry.providerKey)
            digest.updateField(entry.rawName)
            digest.updateField(entry.canonicalChannelId)
            digest.updateField(entry.canonicalDisplayName)
            digest.updateField(entry.streamVariantId)
            digest.updateField(entry.locator)
            digest.updateField(entry.tvgId)
            digest.updateField(entry.tvgName)
            digest.updateField(entry.logoUrl)
            digest.updateField(entry.groupTitle)
            digest.updateField(entry.channelNumber)
            digest.updateField(entry.userAgent)
            digest.updateField(entry.referrer)
        }
        return digest.digest().toHex()
    }

    private fun MessageDigest.updateField(value: String?) {
        if (value == null) {
            update(NULL_FIELD_MARKER)
            return
        }
        update(PRESENT_FIELD_MARKER)
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        updateInt(bytes.size)
        update(bytes)
    }

    private fun MessageDigest.updateInt(value: Int) {
        require(value >= 0)
        update((value ushr 24).toByte())
        update((value ushr 16).toByte())
        update((value ushr 8).toByte())
        update(value.toByte())
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private const val NULL_FIELD_MARKER: Byte = 0
    private const val PRESENT_FIELD_MARKER: Byte = 1
}
