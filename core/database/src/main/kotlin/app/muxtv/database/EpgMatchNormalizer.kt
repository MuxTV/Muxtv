package app.muxtv.database

import java.text.Normalizer
import java.util.Locale

internal fun normalizeEpgProviderId(value: String?): String? {
    if (value == null) return null
    return Normalizer.normalize(value, Normalizer.Form.NFC)
        .trim(::isEpgMatchWhitespace)
        .takeIf(String::isNotEmpty)
}

internal fun normalizeEpgDisplayName(value: String?): String? {
    if (value == null) return null
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFC)
    val collapsed = buildString(normalized.length) {
        var pendingSpace = false
        normalized.forEach { character ->
            if (isEpgMatchWhitespace(character)) {
                if (isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) append(' ')
                append(character)
                pendingSpace = false
            }
        }
    }
    return collapsed
        .takeIf(String::isNotEmpty)
        ?.lowercase(Locale.ROOT)
}

private fun isEpgMatchWhitespace(character: Char): Boolean =
    character.isWhitespace() || Character.isSpaceChar(character)
