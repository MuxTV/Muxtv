package app.muxtv.catalog.ingest

import app.muxtv.model.ProviderRequestHeaderDecision
import app.muxtv.model.ProviderRequestHeaderPolicy
import app.muxtv.model.ProviderRequestMetadata
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

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
    val requestMetadata: ProviderRequestMetadata = ProviderRequestMetadata.EMPTY,
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
    ForbiddenRequestHeader,
    UnsupportedRequestHeader,
    MalformedRequestMetadata,
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
    RequestMetadataCountExceeded,
    RequestMetadataValueExceeded,
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
        var inheritedGroupTitle: String? = null

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
                    pending = parseExtInf(trimmed, limits, lineNumber)?.also { current ->
                        if ("group-title" in current.attributes) {
                            inheritedGroupTitle = null
                        } else {
                            current.groupOverride = inheritedGroupTitle
                        }
                    }
                    if (pending == null) {
                        skippedEntries += 1
                        warn(M3uWarningKind.MalformedExtInf, lineNumber)
                    }
                }

                trimmed.startsWith(EXTGRP_PREFIX, ignoreCase = true) -> {
                    val groupTitle = trimmed.substringAfter(':', "").nullIfBlank()
                    val current = pending
                    if (current == null) {
                        inheritedGroupTitle = groupTitle
                    } else {
                        current.groupOverride = groupTitle
                    }
                }

                trimmed.startsWith(EXTVLCOPT_PREFIX, ignoreCase = true) ||
                    trimmed.startsWith(EXTHTTP_PREFIX, ignoreCase = true) ||
                    trimmed.startsWith(KODIPROP_PREFIX, ignoreCase = true) -> {
                    val current = pending
                    if (current == null) {
                        warn(M3uWarningKind.DirectiveWithoutExtInf, lineNumber)
                    } else {
                        applyRequestDirective(
                            pending = current,
                            line = trimmed,
                            lineNumber = lineNumber,
                        ).forEach { kind -> warn(kind, lineNumber) }
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

                    val entryState = current ?: PendingEntry()
                    val normalizedLocator = normalizeLocator(
                        pending = entryState,
                        locator = trimmed,
                        lineNumber = lineNumber,
                    )
                    normalizedLocator.warnings.forEach { kind -> warn(kind, lineNumber) }

                    val entry = entryState.toEntry(normalizedLocator.locator)
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
        val pending = PendingEntry(
            declaredName = declaredName.ifEmpty { null },
            durationSeconds = duration,
            attributes = attributes,
        )

        firstNonBlank(attributes["http-user-agent"], attributes["user-agent"])
            ?.let { value ->
                pending.requestMetadata.admit(
                    scope = RequestHeaderScope.DEFAULT,
                    rawName = "User-Agent",
                    rawValue = value,
                    lineNumber = lineNumber,
                )
            }
        firstNonBlank(
            attributes["http-referrer"],
            attributes["http-referer"],
            attributes["referrer"],
            attributes["referer"],
        )?.let { value ->
            pending.requestMetadata.admit(
                scope = RequestHeaderScope.DEFAULT,
                rawName = "Referer",
                rawValue = value,
                lineNumber = lineNumber,
            )
        }

        return pending
    }

    private fun applyRequestDirective(
        pending: PendingEntry,
        line: String,
        lineNumber: Long,
    ): List<M3uWarningKind> = when {
        line.startsWith(EXTHTTP_PREFIX, ignoreCase = true) ->
            applyExtHttpDirective(pending, line, lineNumber)

        line.startsWith(EXTVLCOPT_PREFIX, ignoreCase = true) ->
            applyVlcDirective(pending, line, lineNumber)

        line.startsWith(KODIPROP_PREFIX, ignoreCase = true) ->
            applyKodiDirective(pending, line, lineNumber)

        else -> emptyList()
    }

    private fun applyVlcDirective(
        pending: PendingEntry,
        line: String,
        lineNumber: Long,
    ): List<M3uWarningKind> {
        val body = line.substring(EXTVLCOPT_PREFIX.length).trim()
        val separator = body.indexOf('=')
        if (separator <= 0) return emptyList()

        val rawKey = body.substring(0, separator).trim()
        val key = rawKey.lowercase(Locale.ROOT)
        val rawValue = body.substring(separator + 1).trim()

        val rawHeaderName = when {
            key.startsWith("http-") -> rawKey.substring(5)
            key in LEGACY_DIRECT_HEADER_KEYS -> rawKey
            else -> return emptyList()
        }

        return listOfNotNull(
            pending.requestMetadata.admit(
                scope = RequestHeaderScope.DEFAULT,
                rawName = rawHeaderName,
                rawValue = rawValue,
                lineNumber = lineNumber,
            ),
        )
    }

    private fun applyExtHttpDirective(
        pending: PendingEntry,
        line: String,
        lineNumber: Long,
    ): List<M3uWarningKind> {
        val body = line.substring(EXTHTTP_PREFIX.length).trim()
        val jsonObject = runCatching { Json.parseToJsonElement(body) as? JsonObject }
            .getOrNull()
            ?: return listOf(M3uWarningKind.MalformedRequestMetadata)

        val warnings = mutableListOf<M3uWarningKind>()
        jsonObject.forEach { (rawName, element) ->
            val primitive = element as? JsonPrimitive
            if (primitive == null || !primitive.isString) {
                warnings += M3uWarningKind.MalformedRequestMetadata
                return@forEach
            }
            pending.requestMetadata.admit(
                scope = RequestHeaderScope.DEFAULT,
                rawName = rawName,
                rawValue = primitive.content,
                lineNumber = lineNumber,
            )?.let(warnings::add)
        }
        return warnings
    }

    private fun applyKodiDirective(
        pending: PendingEntry,
        line: String,
        lineNumber: Long,
    ): List<M3uWarningKind> {
        val body = line.substring(KODIPROP_PREFIX.length).trim()
        val separator = body.indexOf('=')
        if (separator <= 0) return emptyList()

        val rawKey = body.substring(0, separator).trim()
        val key = rawKey.lowercase(Locale.ROOT)
        val rawValue = body.substring(separator + 1).trim()

        if (key.startsWith("http-")) {
            return listOfNotNull(
                pending.requestMetadata.admit(
                    scope = RequestHeaderScope.DEFAULT,
                    rawName = rawKey.substring(5),
                    rawValue = rawValue,
                    lineNumber = lineNumber,
                ),
            )
        }
        if (key in LEGACY_DIRECT_HEADER_KEYS) {
            return listOfNotNull(
                pending.requestMetadata.admit(
                    scope = RequestHeaderScope.DEFAULT,
                    rawName = rawKey,
                    rawValue = rawValue,
                    lineNumber = lineNumber,
                ),
            )
        }

        val scope = when {
            key.endsWith(".manifest_headers") -> RequestHeaderScope.MANIFEST
            key.endsWith(".stream_headers") || key.endsWith(".stream_header") ->
                RequestHeaderScope.SEGMENT
            else -> return emptyList()
        }
        val parsed = parseAmpersandHeaderAssignments(rawValue)
        return applyAssignments(
            pending = pending,
            scope = scope,
            parsed = parsed,
            lineNumber = lineNumber,
        ).warnings
    }

    private fun normalizeLocator(
        pending: PendingEntry,
        locator: String,
        lineNumber: Long,
    ): LocatorNormalizationResult {
        val pipe = locator.lastIndexOf('|')
        if (pipe <= 0 || pipe == locator.lastIndex) {
            return LocatorNormalizationResult(locator = locator)
        }

        val parsed = parseAmpersandHeaderAssignments(locator.substring(pipe + 1))
        val recognized = parsed.assignments.any { assignment ->
            when (ProviderRequestHeaderPolicy.canonicalize(assignment.rawName)) {
                is ProviderRequestHeaderDecision.Accepted -> true
                ProviderRequestHeaderDecision.Forbidden -> true
                ProviderRequestHeaderDecision.Malformed,
                ProviderRequestHeaderDecision.Unsupported,
                -> false
            }
        }
        if (!recognized) {
            return LocatorNormalizationResult(locator = locator)
        }

        val applied = applyAssignments(
            pending = pending,
            scope = RequestHeaderScope.DEFAULT,
            parsed = parsed,
            lineNumber = lineNumber,
        )
        return LocatorNormalizationResult(
            locator = locator.substring(0, pipe),
            warnings = applied.warnings,
        )
    }

    private fun applyAssignments(
        pending: PendingEntry,
        scope: RequestHeaderScope,
        parsed: ParsedAssignments,
        lineNumber: Long,
    ): AppliedAssignments {
        val warnings = ArrayList<M3uWarningKind>(parsed.malformedCount + parsed.assignments.size)
        repeat(parsed.malformedCount) {
            warnings += M3uWarningKind.MalformedRequestMetadata
        }
        parsed.assignments.forEach { assignment ->
            val warning = pending.requestMetadata.admit(
                scope = scope,
                rawName = assignment.rawName,
                rawValue = assignment.value,
                lineNumber = lineNumber,
            )
            if (warning == null) {
            } else {
                warnings += warning
            }
        }
        return AppliedAssignments(warnings = warnings)
    }

    private fun parseAmpersandHeaderAssignments(raw: String): ParsedAssignments {
        val assignments = mutableListOf<HeaderAssignment>()
        var malformedCount = 0
        raw.split('&').forEach { pair ->
            val separator = pair.indexOf('=')
            if (separator <= 0) {
                if (pair.isNotBlank()) malformedCount += 1
                return@forEach
            }
            val rawName = pair.substring(0, separator).trim()
            val encodedValue = pair.substring(separator + 1)
            val decodedValue = decodeUrlComponent(encodedValue)
            if (rawName.isEmpty() || decodedValue == null) {
                malformedCount += 1
                return@forEach
            }
            assignments += HeaderAssignment(
                rawName = rawName,
                value = decodedValue.trim(),
            )
        }
        return ParsedAssignments(
            assignments = assignments,
            malformedCount = malformedCount,
        )
    }

    private fun decodeUrlComponent(value: String): String? =
        if ('%' !in value && '+' !in value) {
            value
        } else {
            runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrNull()
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
        val requestMetadata: PendingRequestMetadata = PendingRequestMetadata()

        fun toEntry(locator: String): M3uEntry {
            val tvgName = attributes["tvg-name"].nullIfBlank()
            val name = declaredName.nullIfBlank()
                ?: tvgName
                ?: inferName(locator)
            val metadata = requestMetadata.toMetadata()

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
                userAgent = metadata.defaultHeaders["User-Agent"],
                referrer = metadata.defaultHeaders["Referer"],
                attributes = attributes,
                requestMetadata = metadata,
            )
        }

        private fun inferName(locator: String): String {
            val withoutFragment = locator.substringBefore('#')
            val withoutQuery = withoutFragment.substringBefore('?')
            return withoutQuery.substringAfterLast('/')
                .trim()
                .ifEmpty { "Unnamed channel" }
        }
    }

    private class PendingRequestMetadata {
        private val defaultHeaders = linkedMapOf<String, String>()
        private val manifestHeaders = linkedMapOf<String, String>()
        private val segmentHeaders = linkedMapOf<String, String>()

        fun admit(
            scope: RequestHeaderScope,
            rawName: String,
            rawValue: String,
            lineNumber: Long,
        ): M3uWarningKind? {
            val canonicalName = when (
                val decision = ProviderRequestHeaderPolicy.canonicalize(rawName)
            ) {
                is ProviderRequestHeaderDecision.Accepted -> decision.canonicalName
                ProviderRequestHeaderDecision.Forbidden -> return M3uWarningKind.ForbiddenRequestHeader
                ProviderRequestHeaderDecision.Unsupported -> return M3uWarningKind.UnsupportedRequestHeader
                ProviderRequestHeaderDecision.Malformed -> return M3uWarningKind.MalformedRequestMetadata
            }

            val value = rawValue.trim()
            if (value.isEmpty() || value.any { character ->
                    character == '\r' || character == '\n' || character == '\u0000'
                }
            ) {
                return M3uWarningKind.MalformedRequestMetadata
            }
            if (value.length > ProviderRequestHeaderPolicy.MAX_HEADER_VALUE_CHARACTERS) {
                throw M3uLimitExceededException(
                    reason = M3uLimitReason.RequestMetadataValueExceeded,
                    lineNumber = lineNumber,
                    limit = ProviderRequestHeaderPolicy.MAX_HEADER_VALUE_CHARACTERS,
                )
            }

            val target = mapFor(scope)
            if (canonicalName !in target && totalHeaderCount() >= ProviderRequestMetadata.MAX_ACCEPTED_HEADERS) {
                throw M3uLimitExceededException(
                    reason = M3uLimitReason.RequestMetadataCountExceeded,
                    lineNumber = lineNumber,
                    limit = ProviderRequestMetadata.MAX_ACCEPTED_HEADERS,
                )
            }
            target[canonicalName] = value
            return null
        }

        fun toMetadata(): ProviderRequestMetadata =
            if (defaultHeaders.isEmpty() && manifestHeaders.isEmpty() && segmentHeaders.isEmpty()) {
                ProviderRequestMetadata.EMPTY
            } else {
                ProviderRequestMetadata(
                    defaultHeaders = defaultHeaders,
                    manifestHeaders = manifestHeaders,
                    segmentHeaders = segmentHeaders,
                )
            }

        private fun mapFor(scope: RequestHeaderScope): LinkedHashMap<String, String> = when (scope) {
            RequestHeaderScope.DEFAULT -> defaultHeaders
            RequestHeaderScope.MANIFEST -> manifestHeaders
            RequestHeaderScope.SEGMENT -> segmentHeaders
        }

        private fun totalHeaderCount(): Int =
            defaultHeaders.size + manifestHeaders.size + segmentHeaders.size
    }

    private class BoundedTextLineReader(
        input: InputStream,
        private val charset: Charset,
        private val maxLineBytes: Int,
    ) {
        private val input = if (input is BufferedInputStream) input else BufferedInputStream(input)

        fun readLine(lineNumber: Long): String? {
            val bytes = ByteArrayOutputStream(minOf(512, maxLineBytes))
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
                charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray()))
                    .toString()
            } catch (error: CharacterCodingException) {
                throw M3uEncodingException(lineNumber, error)
            }
        }
    }

    private data class HeaderAssignment(
        val rawName: String,
        val value: String,
    )

    private data class ParsedAssignments(
        val assignments: List<HeaderAssignment>,
        val malformedCount: Int,
    )

    private data class AppliedAssignments(
        val warnings: List<M3uWarningKind>,
    )

    private data class LocatorNormalizationResult(
        val locator: String,
        val warnings: List<M3uWarningKind> = emptyList(),
    )

    private enum class RequestHeaderScope {
        DEFAULT,
        MANIFEST,
        SEGMENT,
    }

    private companion object {
        const val EXTENDED_HEADER = "#EXTM3U"
        const val EXTINF_PREFIX = "#EXTINF:"
        const val EXTGRP_PREFIX = "#EXTGRP:"
        const val EXTVLCOPT_PREFIX = "#EXTVLCOPT:"
        const val EXTHTTP_PREFIX = "#EXTHTTP:"
        const val KODIPROP_PREFIX = "#KODIPROP:"

        val EPG_ATTRIBUTE_KEYS = listOf("url-tvg", "x-tvg-url", "tvg-url")
        val LEGACY_DIRECT_HEADER_KEYS = setOf(
            "user-agent",
            "referer",
            "referrer",
        )
    }
}

private fun firstNonBlank(vararg values: String?): String? =
    values.firstNotNullOfOrNull { it.nullIfBlank() }

private fun String?.nullIfBlank(): String? = this?.trim()?.takeIf(String::isNotEmpty)