package app.muxtv.catalog.ingest

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Hard bounds applied before untrusted playlist content can accumulate in memory. */
data class M3uParseLimits(
    val maxLineBytes: Int = 64 * 1024,
    val maxEntries: Int = 100_000,
    val maxAttributesPerRecord: Int = 64,
    val maxAttributeCharactersPerRecord: Int = 32 * 1024,
    val maxReportedWarnings: Int = 1_000,
) {
    init {
        require(maxLineBytes in 256..(1024 * 1024))
        require(maxEntries > 0)
        require(maxAttributesPerRecord > 0)
        require(maxAttributeCharactersPerRecord > 0)
        require(maxReportedWarnings >= 0)
    }
}

data class M3uParseOptions(
    val charset: Charset = Charsets.UTF_8,
    val acceptBareLocators: Boolean = true,
)

class M3uPlaylistHeader(
    attributes: Map<String, String>,
    epgUrls: List<String>,
) {
    val attributes: Map<String, String> = attributes.toMap()
    val epgUrls: List<String> = epgUrls.toList()

    override fun toString(): String =
        "M3uPlaylistHeader(attributeCount=${attributes.size}, epgUrlCount=${epgUrls.size})"
}

class M3uEntry(
    val displayName: String,
    val locator: String,
    val durationSeconds: Long?,
    val tvgId: String?,
    val tvgName: String?,
    val tvgLogo: String?,
    val groupTitle: String?,
    val channelNumber: String?,
    val catchupMode: String?,
    val catchupSource: String?,
    val catchupDays: Int?,
    val catchupCorrection: String?,
    val userAgent: String?,
    val referrer: String?,
    attributes: Map<String, String>,
) {
    val attributes: Map<String, String> = attributes.toMap()

    init {
        require(displayName.isNotBlank())
        require(locator.isNotBlank())
    }

    override fun toString(): String =
        "M3uEntry(displayName=<redacted>, locator=<redacted>, " +
            "tvgId=${tvgId != null}, tvgLogo=${tvgLogo != null}, " +
            "groupTitle=${groupTitle != null}, userAgent=${userAgent != null}, " +
            "referrer=${referrer != null}, attributeCount=${attributes.size})"
}

enum class M3uWarningKind {
    ExtInfReplacedBeforeLocator,
    MalformedExtInf,
    MissingLocatorAtEnd,
    BareLocator,
    DirectiveWithoutExtInf,
}

data class M3uWarning(
    val kind: M3uWarningKind,
    val lineNumber: Long,
)

data class M3uParseReport(
    val hadExtendedHeader: Boolean,
    val parsedEntries: Int,
    val skippedEntries: Int,
    val warningCount: Int,
    val consumedLines: Long,
)

interface M3uParseSink {
    suspend fun onHeader(header: M3uPlaylistHeader) = Unit

    suspend fun onEntry(entry: M3uEntry)

    suspend fun onWarning(warning: M3uWarning) = Unit
}

enum class M3uLimitReason {
    LineTooLong,
    EntryCountExceeded,
    AttributeCountExceeded,
    AttributeCharactersExceeded,
}

class M3uLimitExceededException(
    val reason: M3uLimitReason,
    val lineNumber: Long,
    val limit: Int,
) : IllegalArgumentException("M3U input exceeded a configured parser limit.")

class M3uEncodingException(
    val lineNumber: Long,
    cause: Throwable,
) : IllegalArgumentException("M3U input contains malformed text encoding.", cause)

/**
 * Streaming, allocation-bounded M3U/M3U8 parser.
 *
 * The parser never materializes the playlist as a list. It emits one entry at a time and leaves
 * locator validation, credential application and persistence to later boundaries.
 */
class StreamingM3uParser {
    suspend fun parse(
        input: InputStream,
        sink: M3uParseSink,
        limits: M3uParseLimits = M3uParseLimits(),
        options: M3uParseOptions = M3uParseOptions(),
    ): M3uParseReport {
        val reader = BoundedTextLineReader(
            input = input,
            charset = options.charset,
            maxLineBytes = limits.maxLineBytes,
        )

        var lineNumber = 0L
        var parsedEntries = 0
        var skippedEntries = 0
        var warningCount = 0
        var hadExtendedHeader = false
        var pending: PendingEntry? = null

        suspend fun warn(kind: M3uWarningKind, line: Long) {
            warningCount += 1
            if (warningCount <= limits.maxReportedWarnings) {
                sink.onWarning(M3uWarning(kind = kind, lineNumber = line))
            }
        }

        while (true) {
            coroutineContext.ensureActive()
            val nextLineNumber = lineNumber + 1
            val rawLine = reader.readLine(nextLineNumber) ?: break
            lineNumber = nextLineNumber

            val line = if (lineNumber == 1L) rawLine.removePrefix("\uFEFF") else rawLine
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith(EXTENDED_HEADER, ignoreCase = true) -> {
                    val attributes = parseAttributes(
                        input = trimmed.substring(EXTENDED_HEADER.length),
                        limits = limits,
                        lineNumber = lineNumber,
                    )
                    val epgUrls = EPG_ATTRIBUTE_KEYS
                        .asSequence()
                        .mapNotNull(attributes::get)
                        .flatMap { value -> value.split(',').asSequence() }
                        .map(String::trim)
                        .filter(String::isNotEmpty)
                        .distinct()
                        .toList()
                    hadExtendedHeader = true
                    sink.onHeader(M3uPlaylistHeader(attributes, epgUrls))
                }

                trimmed.startsWith(EXTINF_PREFIX, ignoreCase = true) -> {
                    if (pending != null) {
                        skippedEntries += 1
                        warn(M3uWarningKind.ExtInfReplacedBeforeLocator, lineNumber)
                    }
                    pending = parseExtInf(trimmed, limits, lineNumber)
                    if (pending == null) {
                        skippedEntries += 1
                        warn(M3uWarningKind.MalformedExtInf, lineNumber)
                    }
                }

                trimmed.startsWith(EXTGRP_PREFIX, ignoreCase = true) -> {
                    val current = pending
                    if (current == null) {
                        warn(M3uWarningKind.DirectiveWithoutExtInf, lineNumber)
                    } else {
                        current.groupOverride = trimmed.substringAfter(':', "").trim().ifEmpty { null }
                    }
                }

                trimmed.startsWith(EXTVLCOPT_PREFIX, ignoreCase = true) ||
                    trimmed.startsWith(KODIPROP_PREFIX, ignoreCase = true) -> {
                    val current = pending
                    if (current == null) {
                        warn(M3uWarningKind.DirectiveWithoutExtInf, lineNumber)
                    } else {
                        applyOptionDirective(current, trimmed)
                    }
                }

                trimmed.startsWith('#') -> Unit

                else -> {
                    if (parsedEntries >= limits.maxEntries) {
                        throw M3uLimitExceededException(
                            reason = M3uLimitReason.EntryCountExceeded,
                            lineNumber = lineNumber,
                            limit = limits.maxEntries,
                        )
                    }

                    val current = pending
                    if (current == null && !options.acceptBareLocators) {
                        skippedEntries += 1
                        continue
                    }
                    if (current == null) {
                        warn(M3uWarningKind.BareLocator, lineNumber)
                    }

                    val entry = (current ?: PendingEntry()).toEntry(trimmed)
                    sink.onEntry(entry)
                    parsedEntries += 1
                    pending = null
                }
            }
        }

        if (pending != null) {
            skippedEntries += 1
            warn(M3uWarningKind.MissingLocatorAtEnd, lineNumber)
        }

        return M3uParseReport(
            hadExtendedHeader = hadExtendedHeader,
            parsedEntries = parsedEntries,
            skippedEntries = skippedEntries,
            warningCount = warningCount,
            consumedLines = lineNumber,
        )
    }

    private fun parseExtInf(
        line: String,
        limits: M3uParseLimits,
        lineNumber: Long,
    ): PendingEntry? {
        val body = line.substring(EXTINF_PREFIX.length)
        val comma = findUnquotedComma(body)
        if (comma < 0) return null

        val metadata = body.substring(0, comma).trim()
        val declaredName = body.substring(comma + 1).trim()
        val firstWhitespace = metadata.indexOfFirst(Char::isWhitespace)
        val durationToken = if (firstWhitespace < 0) metadata else metadata.substring(0, firstWhitespace)
        val duration = durationToken.toLongOrNull()
        val attributeText = when {
            firstWhitespace >= 0 -> metadata.substring(firstWhitespace + 1)
            duration != null -> ""
            else -> metadata
        }
        val attributes = parseAttributes(attributeText, limits, lineNumber)

        return PendingEntry(
            declaredName = declaredName.ifEmpty { null },
            durationSeconds = duration,
            attributes = attributes,
        )
    }

    private fun applyOptionDirective(
        pending: PendingEntry,
        line: String,
    ) {
        val body = line.substringAfter(':', "").trim()
        val separator = body.indexOf('=')
        if (separator <= 0) return

        val key = body.substring(0, separator)
            .trim()
            .lowercase(Locale.ROOT)
        val value = body.substring(separator + 1).trim()
        if (value.isEmpty()) return

        when (key) {
            "http-user-agent", "user-agent" -> pending.userAgentOverride = value
            "http-referrer", "http-referer", "referer", "referrer" ->
                pending.referrerOverride = value
        }
    }

    private fun parseAttributes(
        input: String,
        limits: M3uParseLimits,
        lineNumber: Long,
    ): Map<String, String> {
        if (input.isBlank()) return emptyMap()

        val attributes = linkedMapOf<String, String>()
        var attributeCharacters = 0
        var index = 0

        while (index < input.length) {
            while (index < input.length && input[index].isWhitespace()) index += 1
            if (index >= input.length) break

            val keyStart = index
            while (
                index < input.length &&
                !input[index].isWhitespace() &&
                input[index] != '='
            ) {
                index += 1
            }
            val key = input.substring(keyStart, index)
                .trim()
                .lowercase(Locale.ROOT)
            while (index < input.length && input[index].isWhitespace()) index += 1

            if (key.isEmpty() || index >= input.length || input[index] != '=') {
                while (index < input.length && !input[index].isWhitespace()) index += 1
                continue
            }
            index += 1
            while (index < input.length && input[index].isWhitespace()) index += 1

            val value = if (index < input.length && input[index] == '"') {
                index += 1
                val result = StringBuilder()
                var escaped = false
                while (index < input.length) {
                    val character = input[index++]
                    when {
                        escaped -> {
                            result.append(character)
                            escaped = false
                        }

                        character == '\\' -> escaped = true
                        character == '"' -> break
                        else -> result.append(character)
                    }
                }
                result.toString()
            } else {
                val valueStart = index
                while (index < input.length && !input[index].isWhitespace()) index += 1
                input.substring(valueStart, index)
            }

            if (attributes.size >= limits.maxAttributesPerRecord && key !in attributes) {
                throw M3uLimitExceededException(
                    reason = M3uLimitReason.AttributeCountExceeded,
                    lineNumber = lineNumber,
                    limit = limits.maxAttributesPerRecord,
                )
            }
            attributeCharacters += key.length + value.length
            if (attributeCharacters > limits.maxAttributeCharactersPerRecord) {
                throw M3uLimitExceededException(
                    reason = M3uLimitReason.AttributeCharactersExceeded,
                    lineNumber = lineNumber,
                    limit = limits.maxAttributeCharactersPerRecord,
                )
            }
            attributes[key] = value
        }

        return attributes
    }

    private fun findUnquotedComma(value: String): Int {
        var quoted = false
        var escaped = false
        value.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' && quoted -> escaped = true
                character == '"' -> quoted = !quoted
                character == ',' && !quoted -> return index
            }
        }
        return -1
    }

    private class PendingEntry(
        val declaredName: String? = null,
        val durationSeconds: Long? = null,
        val attributes: Map<String, String> = emptyMap(),
    ) {
        var groupOverride: String? = null
        var userAgentOverride: String? = null
        var referrerOverride: String? = null

        fun toEntry(locator: String): M3uEntry {
            val tvgName = attributes["tvg-name"].nullIfBlank()
            val name = declaredName.nullIfBlank()
                ?: tvgName
                ?: inferName(locator)

            return M3uEntry(
                displayName = name,
                locator = locator,
                durationSeconds = durationSeconds,
                tvgId = attributes["tvg-id"].nullIfBlank(),
                tvgName = tvgName,
                tvgLogo = attributes["tvg-logo"].nullIfBlank(),
                groupTitle = groupOverride
                    ?: attributes["group-title"].nullIfBlank(),
                channelNumber = firstNonBlank(
                    attributes["tvg-chno"],
                    attributes["tvg-num"],
                    attributes["channel-number"],
                ),
                catchupMode = attributes["catchup"].nullIfBlank(),
                catchupSource = attributes["catchup-source"].nullIfBlank(),
                catchupDays = attributes["catchup-days"]?.toIntOrNull(),
                catchupCorrection = attributes["catchup-correction"].nullIfBlank(),
                userAgent = userAgentOverride
                    ?: firstNonBlank(attributes["http-user-agent"], attributes["user-agent"]),
                referrer = referrerOverride
                    ?: firstNonBlank(
                        attributes["http-referrer"],
                        attributes["http-referer"],
                        attributes["referrer"],
                        attributes["referer"],
                    ),
                attributes = attributes,
            )
        }

        private fun inferName(locator: String): String {
            val withoutFragment = locator.substringBefore('#')
            val withoutQuery = withoutFragment.substringBefore('?')
            return withoutQuery.substringAfterLast('/')
                .trim()
                .ifEmpty { "Unnamed channel" }
        }

        private fun firstNonBlank(first: String?, second: String?): String? =
            first.nullIfBlank() ?: second.nullIfBlank()

        private fun firstNonBlank(first: String?, second: String?, third: String?): String? =
            first.nullIfBlank() ?: second.nullIfBlank() ?: third.nullIfBlank()

        private fun firstNonBlank(
            first: String?,
            second: String?,
            third: String?,
            fourth: String?,
        ): String? =
            first.nullIfBlank() ?: second.nullIfBlank() ?: third.nullIfBlank() ?: fourth.nullIfBlank()

        private fun String?.nullIfBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)
    }

    private class BoundedTextLineReader(
        input: InputStream,
        charset: Charset,
        private val maxLineBytes: Int,
    ) {
        private val input = if (input is BufferedInputStream) input else BufferedInputStream(input)
        private val bytes = ReusableByteArrayOutputStream(minOf(512, maxLineBytes))
        private val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)

        fun readLine(lineNumber: Long): String? {
            bytes.reset()
            var sawInput = false

            while (true) {
                val value = input.read()
                if (value == -1) break
                sawInput = true
                if (value == '\n'.code) break
                if (value == '\r'.code) continue

                if (bytes.size() >= maxLineBytes) {
                    throw M3uLimitExceededException(
                        reason = M3uLimitReason.LineTooLong,
                        lineNumber = lineNumber,
                        limit = maxLineBytes,
                    )
                }
                bytes.write(value)
            }

            if (!sawInput && bytes.size() == 0) return null

            return try {
                decoder.reset()
                decoder.decode(bytes.asByteBuffer()).toString()
            } catch (error: CharacterCodingException) {
                throw M3uEncodingException(lineNumber, error)
            }
        }
    }

    private class ReusableByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
        fun asByteBuffer(): ByteBuffer = ByteBuffer.wrap(buf, 0, count)
    }

    private companion object {
        const val EXTENDED_HEADER = "#EXTM3U"
        const val EXTINF_PREFIX = "#EXTINF:"
        const val EXTGRP_PREFIX = "#EXTGRP:"
        const val EXTVLCOPT_PREFIX = "#EXTVLCOPT:"
        const val KODIPROP_PREFIX = "#KODIPROP:"

        val EPG_ATTRIBUTE_KEYS = listOf("url-tvg", "x-tvg-url", "tvg-url")
    }
}

private fun String?.nullIfBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)