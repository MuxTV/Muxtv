package app.muxtv.player.media3

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

internal object PlayerProxyRequestProfileDigest {
    fun sha256(request: PlaybackSessionRequest): String = sha256(listOf(request))

    fun sha256(requests: List<PlaybackSessionRequest>): String {
        require(requests.isNotEmpty())
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateInt(requests.size)
        requests.forEach { request ->
            digest.updateField(request.mediaId)
            digest.updateField(request.variantId)
            digest.updateField(request.locator)
            digest.updateField(request.displayName)
            digest.updateField(request.artworkUri)
            digest.update(if (request.insecureHttpApproved) TRUE_MARKER else FALSE_MARKER)

            val headers = request.requestHeaders.entries.sortedBy(Map.Entry<String, String>::key)
            digest.updateInt(headers.size)
            headers.forEach { (name, value) ->
                digest.updateField(name)
                digest.updateField(value)
            }
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
    private const val FALSE_MARKER: Byte = 2
    private const val TRUE_MARKER: Byte = 3
}
