package app.muxtv.catalog.ingest

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

object XmltvTimestampParser {
    fun parse(raw: String): XmltvTimestamp? {
        val value = raw.trim()
        if (value.length < YEAR_DIGITS) return null

        var digitCount = 0
        while (digitCount < value.length && value[digitCount].isAsciiDigit()) {
            digitCount++
        }

        val precision = when (digitCount) {
            4 -> XmltvTimestampPrecision.Year
            6 -> XmltvTimestampPrecision.Month
            8 -> XmltvTimestampPrecision.Day
            10 -> XmltvTimestampPrecision.Hour
            12 -> XmltvTimestampPrecision.Minute
            14 -> XmltvTimestampPrecision.Second
            else -> return null
        }

        var offsetStart = -1
        if (digitCount < value.length) {
            var index = digitCount
            if (!value[index].isLegacyRegexWhitespace()) return null
            do {
                index++
            } while (index < value.length && value[index].isLegacyRegexWhitespace())

            if (value.length - index != OFFSET_CHARACTERS) return null
            if (value[index] != '+' && value[index] != '-') return null
            for (offsetDigit in index + 1 until value.length) {
                if (!value[offsetDigit].isAsciiDigit()) return null
            }
            offsetStart = index
        }

        val localDateTime = try {
            LocalDateTime.of(
                value.parseAsciiInt(0, 4),
                value.componentOrDefault(digitCount, 4, 2, 1),
                value.componentOrDefault(digitCount, 6, 2, 1),
                value.componentOrDefault(digitCount, 8, 2, 0),
                value.componentOrDefault(digitCount, 10, 2, 0),
                value.componentOrDefault(digitCount, 12, 2, 0),
            )
        } catch (_: DateTimeException) {
            return null
        }

        val inferred = digitCount < SECOND_PRECISION_DIGITS
        if (offsetStart < 0) {
            return XmltvTimestamp.Unresolved(
                localDateTime = localDateTime,
                precision = precision,
                inferredComponents = inferred,
            )
        }

        val offset = parseOffset(value, offsetStart) ?: return null
        return XmltvTimestamp.Resolved(
            instant = localDateTime.toInstant(offset),
            offset = offset,
            precision = precision,
            inferredComponents = inferred,
        )
    }

    private fun parseOffset(value: String, start: Int): ZoneOffset? {
        val hours = value.parseAsciiInt(start + 1, 2)
        val minutes = value.parseAsciiInt(start + 3, 2)
        if (hours > 18 || minutes > 59 || (hours == 18 && minutes != 0)) return null
        val sign = if (value[start] == '-') -1 else 1
        return try {
            ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
        } catch (_: DateTimeException) {
            null
        }
    }

    private fun String.componentOrDefault(
        digitCount: Int,
        start: Int,
        length: Int,
        default: Int,
    ): Int = if (digitCount >= start + length) parseAsciiInt(start, length) else default

    private fun String.parseAsciiInt(start: Int, length: Int): Int {
        var result = 0
        val end = start + length
        for (index in start until end) {
            result = result * 10 + (this[index].code - '0'.code)
        }
        return result
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'

    private fun Char.isLegacyRegexWhitespace(): Boolean = when (this) {
        ' ', '\t', '\n', '\u000B', '\u000C', '\r' -> true
        else -> false
    }

    private const val YEAR_DIGITS = 4
    private const val SECOND_PRECISION_DIGITS = 14
    private const val OFFSET_CHARACTERS = 5
}
