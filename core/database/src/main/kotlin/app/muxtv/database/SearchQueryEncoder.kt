package app.muxtv.database

import app.muxtv.catalog.ChannelSearchQuery

data class SearchQueryToken internal constructor(
    val value: String,
) {
    init {
        require(value.isNotEmpty())
        require(value.codePoints().allMatch { codePoint -> Character.isLetterOrDigit(codePoint) })
    }

    // A single quoted phrase containing one term-prefix keeps words such as OR, AND and NEAR
    // in term position instead of exposing FTS4 operator grammar. User punctuation never reaches
    // this string because the encoder accepts Unicode letters/numbers only.
    val ftsExpression: String = "\"$value*\""

    override fun toString(): String = "SearchQueryToken(length=${value.codePointCount(0, value.length)})"
}

internal object SearchQueryEncoder {
    fun encode(text: String): List<SearchQueryToken> {
        if (text.isBlank()) return emptyList()

        val result = ArrayList<SearchQueryToken>(ChannelSearchQuery.MAX_TOKENS)
        val current = StringBuilder()
        var offset = 0

        fun flushToken() {
            if (current.isEmpty() || result.size >= ChannelSearchQuery.MAX_TOKENS) {
                current.setLength(0)
                return
            }
            result += SearchQueryToken(current.toString())
            current.setLength(0)
        }

        while (offset < text.length && result.size < ChannelSearchQuery.MAX_TOKENS) {
            val codePoint = text.codePointAt(offset)
            if (Character.isLetterOrDigit(codePoint)) {
                current.appendCodePoint(codePoint)
            } else {
                flushToken()
            }
            offset += Character.charCount(codePoint)
        }
        flushToken()

        return result
    }

    fun hasTokenOverflow(text: String): Boolean {
        var tokenCount = 0
        var insideToken = false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            val isTokenCodePoint = Character.isLetterOrDigit(codePoint)
            if (isTokenCodePoint && !insideToken) {
                tokenCount += 1
                if (tokenCount > ChannelSearchQuery.MAX_TOKENS) return true
            }
            insideToken = isTokenCodePoint
            offset += Character.charCount(codePoint)
        }
        return false
    }
}