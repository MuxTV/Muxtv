package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

class XmltvTimestampParserEquivalenceTest {
    @Test
    fun `parser remains equivalent to the legacy regex contract`() {
        val validDigits = listOf(
            "2026",
            "202602",
            "20260228",
            "2026022812",
            "202602281234",
            "20260228123456",
            "20240229115959",
            "19991231235959",
        )
        val separators = listOf(" ", "  ", "\t", "\n", "\r", "\u000B", "\u000C", " \t ")
        val offsets = listOf("+0000", "-0000", "+0100", "-0130", "+0530", "-0800", "+1800", "-1800")
        val outerWhitespace = listOf("", " ", "\t", "\n")

        val cases = linkedSetOf<String>()
        validDigits.forEach { digits ->
            cases += digits
            outerWhitespace.forEach { prefix ->
                outerWhitespace.forEach { suffix ->
                    cases += prefix + digits + suffix
                }
            }
            separators.forEach { separator ->
                offsets.forEach { offset ->
                    cases += digits + separator + offset
                }
            }
        }
        cases += listOf(
            "",
            " ",
            "202",
            "20261",
            "2026011",
            "202601011",
            "20260101121",
            "2026010112345",
            "2026010112345678",
            "202613",
            "20260230",
            "20260228126000",
            "20260228123460",
            "20260228123456+0000",
            "20260228123456 +",
            "20260228123456 +000",
            "20260228123456 +00000",
            "20260228123456 +1900",
            "20260228123456 -1900",
            "20260228123456 +1801",
            "20260228123456 +1860",
            "20260228123456 UTC",
            "20260228123456 +05:30",
            "２０２６０２２８１２３４５６ +0000",
            "20260228123456\u00A0+0000",
            "+20260228123456",
        )

        cases.forEach { raw ->
            assertThat(XmltvTimestampParser.parse(raw).signature())
                .named("timestamp: %s", raw.escapeForName())
                .isEqualTo(legacyParse(raw).signature())
        }
    }

    private fun legacyParse(raw: String): XmltvTimestamp? {
        val match = LEGACY_PATTERN.matchEntire(raw.trim()) ?: return null
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
        val offset = legacyParseOffset(offsetText) ?: return null
        return XmltvTimestamp.Resolved(
            instant = localDateTime.toInstant(offset),
            offset = offset,
            precision = precision,
            inferredComponents = inferred,
        )
    }

    private fun legacyParseOffset(value: String): ZoneOffset? {
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

    private fun XmltvTimestamp?.signature(): TimestampSignature? = when (this) {
        null -> null
        is XmltvTimestamp.Resolved -> TimestampSignature(
            kind = "resolved",
            value = instant.toString(),
            offset = offset.toString(),
            precision = precision,
            inferredComponents = inferredComponents,
        )
        is XmltvTimestamp.Unresolved -> TimestampSignature(
            kind = "unresolved",
            value = localDateTime.toString(),
            offset = null,
            precision = precision,
            inferredComponents = inferredComponents,
        )
    }

    private fun String.escapeForName(): String =
        replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private data class TimestampSignature(
        val kind: String,
        val value: String,
        val offset: String?,
        val precision: XmltvTimestampPrecision,
        val inferredComponents: Boolean,
    )

    private companion object {
        val LEGACY_PATTERN = Regex("^(\\d{4}(?:\\d{2}){0,5})(?:\\s+([+-]\\d{4}))?$")
    }
}

private fun String.componentOrDefault(start: Int, length: Int, default: Int): Int =
    if (this.length >= start + length) substring(start, start + length).toInt() else default
