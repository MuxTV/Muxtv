package app.muxtv.catalog.ingest

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset

object XmltvTimestampParser {
    private val pattern = Regex("^(\\d{4}(?:\\d{2}){0,5})(?:\\s+([+-]\\d{4}))?$")

    fun parse(raw: String): XmltvTimestamp? {
        val match = pattern.matchEntire(raw.trim()) ?: return null
        val digits = match.groupValues[1]
        val precision = when (digits.length) {
            4 -> XmltvTimestampPrecision.Year
            6 -> XmltvTimestampPrecision.Month
            8 -> XmltvTimestampPrecision.Day
            10 -> XmltvTimestampPrecision.Hour
            12 -> XmltvTimestampPrecision.Minute
            14 -> XmltvTimestampPrecision.Second
            else -> return null
        }
        val localDateTime = try {
            LocalDateTime.of(
                digits.substring(0, 4).toInt(),
                digits.componentOrDefault(4, 2, 1),
                digits.componentOrDefault(6, 2, 1),
                digits.componentOrDefault(8, 2, 0),
                digits.componentOrDefault(10, 2, 0),
                digits.componentOrDefault(12, 2, 0),
            )
        } catch (_: DateTimeException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
        val inferred = digits.length < 14
        val offsetText = match.groupValues[2]
        if (offsetText.isEmpty()) {
            return XmltvTimestamp.Unresolved(
                localDateTime = localDateTime,
                precision = precision,
                inferredComponents = inferred,
            )
        }
        val offset = parseOffset(offsetText) ?: return null
        return XmltvTimestamp.Resolved(
            instant = localDateTime.toInstant(offset),
            offset = offset,
            precision = precision,
            inferredComponents = inferred,
        )
    }

    private fun parseOffset(value: String): ZoneOffset? {
        if (value.length != 5 || value[0] !in charArrayOf('+', '-')) return null
        val hours = value.substring(1, 3).toIntOrNull() ?: return null
        val minutes = value.substring(3, 5).toIntOrNull() ?: return null
        if (hours > 18 || minutes > 59 || (hours == 18 && minutes != 0)) return null
        val sign = if (value[0] == '-') -1 else 1
        return try {
            ZoneOffset.ofTotalSeconds(sign * (hours * 3600 + minutes * 60))
        } catch (_: DateTimeException) {
            null
        }
    }
}

private fun String.componentOrDefault(start: Int, length: Int, default: Int): Int =
    if (this.length >= start + length) substring(start, start + length).toInt() else default
