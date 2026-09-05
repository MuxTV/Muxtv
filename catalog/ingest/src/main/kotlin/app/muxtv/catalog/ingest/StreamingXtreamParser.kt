package app.muxtv.catalog.ingest

import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeToSequence

private const val DEFAULT_MAX_XTREAM_ITEMS = 100_000
private const val DEFAULT_MAX_XTREAM_FIELD_CHARACTERS = 32 * 1024
private const val DEFAULT_MAX_XTREAM_AUTH_BYTES = 256 * 1024
private const val XTREAM_READ_BUFFER_BYTES = 8 * 1024

data class XtreamParseLimits(
    val maxItems: Int = DEFAULT_MAX_XTREAM_ITEMS,
    val maxFieldCharacters: Int = DEFAULT_MAX_XTREAM_FIELD_CHARACTERS,
    val maxAuthBytes: Int = DEFAULT_MAX_XTREAM_AUTH_BYTES,
) {
    init {
        require(maxItems > 0)
        require(maxFieldCharacters > 0)
        require(maxAuthBytes > 0)
    }
}

enum class XtreamLimitReason {
    ItemCountExceeded,
    FieldCharactersExceeded,
    AuthBytesExceeded,
}

class XtreamLimitExceededException(
    val reason: XtreamLimitReason,
) : IllegalArgumentException("Xtream input exceeded a configured parser limit.")

class XtreamFormatException : IllegalArgumentException("Xtream input has an invalid JSON structure.")

sealed interface XtreamAuthResult {
    class Authenticated(
        allowedOutputFormats: List<String>,
        val serverTimeZoneId: String? = null,
    ) : XtreamAuthResult {
        val allowedOutputFormats: List<String> = allowedOutputFormats.distinct().toList()

        override fun toString(): String =
            "XtreamAuthResult.Authenticated(outputFormatCount=${allowedOutputFormats.size}, " +
                "serverTimeZonePresent=${serverTimeZoneId != null})"
    }

    data object Rejected : XtreamAuthResult
}

class XtreamLiveEntry(
    val streamId: Long,
    val name: String,
    val channelNumber: Int?,
    val streamIcon: String?,
    val epgChannelId: String?,
    val categoryId: String?,
    val archiveAvailable: Boolean?,
    val archiveDurationDays: Int?,
) {
    init {
        require(streamId > 0)
        require(name.isNotBlank())
    }

    override fun toString(): String =
        "XtreamLiveEntry(streamId=<redacted>, name=<redacted>, " +
            "hasChannelNumber=${channelNumber != null}, hasStreamIcon=${streamIcon != null}, " +
            "hasEpgChannelId=${epgChannelId != null}, hasCategoryId=${categoryId != null}, " +
            "archiveMetadataPresent=${archiveAvailable != null || archiveDurationDays != null})"
}

enum class XtreamWarningKind {
    InvalidIdentity,
    NonLiveItem,
    InvalidOptionalField,
}

data class XtreamWarning(
    val kind: XtreamWarningKind,
    val itemIndex: Int,
) {
    init {
        require(itemIndex >= 0)
    }
}

interface XtreamLiveSink {
    suspend fun onEntry(entry: XtreamLiveEntry)

    suspend fun onWarning(warning: XtreamWarning) = Unit
}

data class XtreamLiveParseReport(
    val parsedEntries: Int,
    val skippedEntries: Int,
    val warningCount: Int,
) {
    init {
        require(parsedEntries >= 0)
        require(skippedEntries >= 0)
        require(warningCount >= 0)
    }
}

class StreamingXtreamParser(
    private val json: Json = Json,
) {
    fun parseAuth(
        input: InputStream,
        limits: XtreamParseLimits = XtreamParseLimits(),
    ): XtreamAuthResult {
        val root = parseAuthRoot(input, limits)
        val userInfo = root["user_info"] as? JsonObject ?: throw XtreamFormatException()
        val auth = requiredFlexibleLong(userInfo["auth"], limits)
        val status = requiredString(userInfo["status"], limits)

        if (auth != 1L || !status.equals("Active", ignoreCase = true)) {
            return XtreamAuthResult.Rejected
        }

        val allowedOutputFormats = when (val formats = userInfo["allowed_output_formats"]) {
            null, JsonNull -> emptyList()
            is JsonArray -> formats.map { format ->
                requiredString(format, limits).lowercase()
            }
            else -> throw XtreamFormatException()
        }
        val serverTimeZoneId = (root["server_info"] as? JsonObject)
            ?.let { serverInfo -> strictStringOrNull(serverInfo["timezone"], limits) }
            ?.trim()
            ?.takeIf(String::isNotEmpty)

        return XtreamAuthResult.Authenticated(
            allowedOutputFormats = allowedOutputFormats,
            serverTimeZoneId = serverTimeZoneId,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun parseLive(
        input: InputStream,
        sink: XtreamLiveSink,
        limits: XtreamParseLimits = XtreamParseLimits(),
    ): XtreamLiveParseReport {
        val sequence = decodeStructural {
            json.decodeToSequence<JsonElement>(input, DecodeSequenceMode.ARRAY_WRAPPED)
        }
        val iterator = sequence.iterator()
        var parsedEntries = 0
        var skippedEntries = 0
        var warningCount = 0
        var itemIndex = 0

        suspend fun warn(kind: XtreamWarningKind) {
            warningCount += 1
            sink.onWarning(XtreamWarning(kind = kind, itemIndex = itemIndex))
        }

        while (decodeStructural { iterator.hasNext() }) {
            currentCoroutineContext().ensureActive()
            if (itemIndex >= limits.maxItems) {
                throw XtreamLimitExceededException(XtreamLimitReason.ItemCountExceeded)
            }

            val element = decodeStructural { iterator.next() }
            val item = element as? JsonObject
            if (item == null) {
                skippedEntries += 1
                warn(XtreamWarningKind.InvalidIdentity)
                itemIndex += 1
                continue
            }

            when (val streamType = item["stream_type"]) {
                null -> Unit
                JsonNull -> {
                    skippedEntries += 1
                    warn(XtreamWarningKind.InvalidIdentity)
                    itemIndex += 1
                    continue
                }
                is JsonPrimitive -> {
                    if (!streamType.isString) {
                        skippedEntries += 1
                        warn(XtreamWarningKind.InvalidIdentity)
                        itemIndex += 1
                        continue
                    }
                    val normalizedType = boundedContent(streamType, limits).trim()
                    if (!normalizedType.equals("live", ignoreCase = true)) {
                        skippedEntries += 1
                        warn(XtreamWarningKind.NonLiveItem)
                        itemIndex += 1
                        continue
                    }
                }
                else -> {
                    skippedEntries += 1
                    warn(XtreamWarningKind.InvalidIdentity)
                    itemIndex += 1
                    continue
                }
            }

            val streamId = flexibleLongOrNull(item["stream_id"], limits)
            val name = strictStringOrNull(item["name"], limits)?.trim()?.takeIf(String::isNotEmpty)
            if (streamId == null || streamId <= 0 || name == null) {
                skippedEntries += 1
                warn(XtreamWarningKind.InvalidIdentity)
                itemIndex += 1
                continue
            }

            var optionalInvalid = false

            fun optionalInt(field: String): Int? {
                val elementValue = item[field] ?: return null
                if (elementValue === JsonNull) return null
                val value = flexibleLongOrNull(elementValue, limits)
                if (value == null || value !in 0..Int.MAX_VALUE.toLong()) {
                    optionalInvalid = true
                    return null
                }
                return value.toInt()
            }

            fun optionalString(field: String, allowNumber: Boolean): String? {
                val elementValue = item[field] ?: return null
                if (elementValue === JsonNull) return null
                val primitive = elementValue as? JsonPrimitive
                if (primitive == null || (!allowNumber && !primitive.isString)) {
                    optionalInvalid = true
                    return null
                }
                val value = boundedContent(primitive, limits).trim()
                return value.takeIf(String::isNotEmpty)
            }

            fun optionalArchiveFlag(field: String): Boolean? {
                val elementValue = item[field] ?: return null
                if (elementValue === JsonNull) return null
                val primitive = elementValue as? JsonPrimitive
                if (primitive == null) {
                    optionalInvalid = true
                    return null
                }
                return when (boundedContent(primitive, limits).trim().lowercase()) {
                    "1", "true" -> true
                    "0", "false" -> false
                    else -> {
                        optionalInvalid = true
                        null
                    }
                }
            }

            val channelNumber = optionalInt("num")
            val streamIcon = optionalString("stream_icon", allowNumber = false)
            val epgChannelId = optionalString("epg_channel_id", allowNumber = true)
            val categoryId = optionalString("category_id", allowNumber = true)
            val archiveAvailable = optionalArchiveFlag("tv_archive")
            val archiveDurationDays = optionalInt("tv_archive_duration")

            sink.onEntry(
                XtreamLiveEntry(
                    streamId = streamId,
                    name = name,
                    channelNumber = channelNumber,
                    streamIcon = streamIcon,
                    epgChannelId = epgChannelId,
                    categoryId = categoryId,
                    archiveAvailable = archiveAvailable,
                    archiveDurationDays = archiveDurationDays,
                ),
            )
            parsedEntries += 1
            if (optionalInvalid) warn(XtreamWarningKind.InvalidOptionalField)
            itemIndex += 1
        }

        return XtreamLiveParseReport(
            parsedEntries = parsedEntries,
            skippedEntries = skippedEntries,
            warningCount = warningCount,
        )
    }

    private fun parseAuthRoot(
        input: InputStream,
        limits: XtreamParseLimits,
    ): JsonObject {
        val bytes = readBounded(input, limits.maxAuthBytes)
        val element = try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw XtreamFormatException()
        } catch (_: IllegalArgumentException) {
            throw XtreamFormatException()
        }
        return element as? JsonObject ?: throw XtreamFormatException()
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(XTREAM_READ_BUFFER_BYTES)
        val output = java.io.ByteArrayOutputStream(minOf(limit, XTREAM_READ_BUFFER_BYTES))
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) {
                throw XtreamLimitExceededException(XtreamLimitReason.AuthBytesExceeded)
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun requiredString(element: JsonElement?, limits: XtreamParseLimits): String {
        return strictStringOrNull(element, limits)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: throw XtreamFormatException()
    }

    private fun strictStringOrNull(element: JsonElement?, limits: XtreamParseLimits): String? {
        if (element == null || element === JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return boundedContent(primitive, limits)
    }

    private fun requiredFlexibleLong(element: JsonElement?, limits: XtreamParseLimits): Long {
        return flexibleLongOrNull(element, limits) ?: throw XtreamFormatException()
    }

    private fun flexibleLongOrNull(element: JsonElement?, limits: XtreamParseLimits): Long? {
        if (element == null || element === JsonNull) return null
        val primitive = element as? JsonPrimitive ?: return null
        return boundedContent(primitive, limits).trim().toLongOrNull()
    }

    private fun boundedContent(primitive: JsonPrimitive, limits: XtreamParseLimits): String {
        val content = primitive.content
        if (content.length > limits.maxFieldCharacters) {
            throw XtreamLimitExceededException(XtreamLimitReason.FieldCharactersExceeded)
        }
        return content
    }

    private inline fun <T> decodeStructural(block: () -> T): T {
        return try {
            block()
        } catch (failure: XtreamLimitExceededException) {
            throw failure
        } catch (_: SerializationException) {
            throw XtreamFormatException()
        } catch (_: IllegalArgumentException) {
            throw XtreamFormatException()
        }
    }
}
