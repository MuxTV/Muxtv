package app.muxtv.backup

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object PortableBackupCodec {
    fun encode(snapshot: PortableBackupSnapshot): ByteArray {
        val unsigned = canonicalUnsignedJson(snapshot).toByteArray(Charsets.UTF_8)
        val digest = sha256(unsigned)
        val json = buildString {
            append(canonicalUnsignedJson(snapshot).dropLast(1))
            append(",\"integrity\":{")
            append("\"algorithm\":")
            appendQuoted(PortableBackupIntegrity.SHA_256_ALGORITHM)
            append(",\"documentSha256\":")
            appendQuoted(digest.toLowerHex())
            append("}}")
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.size > PortableBackupLimits.MAX_DOCUMENT_BYTES) {
            throw PortableBackupValidationException(PortableBackupRejectReason.LIMIT_EXCEEDED)
        }
        return bytes
    }

    fun decode(bytes: ByteArray): PortableBackupDecodeResult {
        if (bytes.size > PortableBackupLimits.MAX_DOCUMENT_BYTES) {
            return PortableBackupDecodeResult.Rejected(PortableBackupRejectReason.OVERSIZED)
        }

        return try {
            val text = decodeUtf8Strict(bytes)
            val root = Json.parseToJsonElement(text).asObject()
            root.requireExactFields(ROOT_FIELDS)

            val formatVersion = root.requiredInt("formatVersion")
            if (formatVersion != PortableBackupDocument.CURRENT_FORMAT_VERSION) {
                return PortableBackupDecodeResult.Rejected(
                    PortableBackupRejectReason.UNSUPPORTED_VERSION,
                )
            }

            val snapshot = PortableBackupSnapshot(
                createdAtEpochMillis = root.requiredLong("createdAtEpochMillis"),
                dataSchemaVersion = root.requiredInt("dataSchemaVersion"),
                payload = parsePayload(root.requiredObject("payload")),
            )
            val integrity = parseIntegrity(root.requiredObject("integrity"))
            val canonicalUnsignedBytes = canonicalUnsignedJson(snapshot).toByteArray(Charsets.UTF_8)
            val actualDigest = sha256(canonicalUnsignedBytes)
            val expectedDigest = integrity.documentSha256.hexToBytes()
            if (!MessageDigest.isEqual(expectedDigest, actualDigest)) {
                PortableBackupDecodeResult.Rejected(PortableBackupRejectReason.INTEGRITY_MISMATCH)
            } else {
                PortableBackupDecodeResult.Success(
                    PortableBackupDocument(
                        formatVersion = formatVersion,
                        snapshot = snapshot,
                        integrity = integrity,
                    ),
                )
            }
        } catch (error: PortableBackupValidationException) {
            PortableBackupDecodeResult.Rejected(error.reason)
        } catch (_: UnknownBackupFieldException) {
            PortableBackupDecodeResult.Rejected(PortableBackupRejectReason.UNKNOWN_FIELD)
        } catch (_: MalformedBackupException) {
            PortableBackupDecodeResult.Rejected(PortableBackupRejectReason.MALFORMED)
        } catch (_: CharacterCodingException) {
            PortableBackupDecodeResult.Rejected(PortableBackupRejectReason.MALFORMED)
        } catch (_: SerializationException) {
            PortableBackupDecodeResult.Rejected(PortableBackupRejectReason.MALFORMED)
        }
    }

    private fun parsePayload(value: JsonObject): PortableBackupPayload {
        value.requireExactFields(PAYLOAD_FIELDS)
        return PortableBackupPayload(
            profiles = value.requiredArray("profiles").map(::parseProfile),
            sources = value.requiredArray("sources").map(::parseSource),
            channelOverlays = value.requiredArray("channelOverlays").map(::parseOverlay),
            recentChannels = value.requiredArray("recentChannels").map(::parseRecent),
        )
    }

    private fun parseProfile(value: JsonElement): PortableBackupProfile {
        val objectValue = value.asObject()
        objectValue.requireExactFields(PROFILE_FIELDS)
        return PortableBackupProfile(
            id = objectValue.requiredString("id"),
            name = objectValue.requiredString("name"),
            isPrimary = objectValue.requiredBoolean("isPrimary"),
            archivedAtEpochMillis = objectValue.optionalLong("archivedAtEpochMillis"),
        )
    }

    private fun parseSource(value: JsonElement): PortableBackupSource {
        val objectValue = value.asObject()
        objectValue.requireExactFields(SOURCE_FIELDS)
        val recoveryState = objectValue.requiredString("recoveryState")
        if (recoveryState != PortableSourceRecoveryState.REAUTH_REQUIRED.name) {
            throw PortableBackupValidationException(PortableBackupRejectReason.INVALID_DATA)
        }
        return PortableBackupSource(
            id = objectValue.requiredString("id"),
            name = objectValue.requiredString("name"),
            recoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
        )
    }

    private fun parseOverlay(value: JsonElement): PortableChannelOverlay {
        val objectValue = value.asObject()
        objectValue.requireExactFields(OVERLAY_FIELDS)
        return PortableChannelOverlay(
            profileId = objectValue.requiredString("profileId"),
            canonicalChannelId = objectValue.requiredString("canonicalChannelId"),
            isFavorite = objectValue.requiredBoolean("isFavorite"),
            customName = objectValue.optionalString("customName"),
            channelNumber = objectValue.optionalInt("channelNumber"),
            isHidden = objectValue.requiredBoolean("isHidden"),
        )
    }

    private fun parseRecent(value: JsonElement): PortableRecentChannel {
        val objectValue = value.asObject()
        objectValue.requireExactFields(RECENT_FIELDS)
        return PortableRecentChannel(
            profileId = objectValue.requiredString("profileId"),
            canonicalChannelId = objectValue.requiredString("canonicalChannelId"),
            lastSuccessfulPlaybackAtEpochMillis =
                objectValue.requiredLong("lastSuccessfulPlaybackAtEpochMillis"),
        )
    }

    private fun parseIntegrity(value: JsonObject): PortableBackupIntegrity {
        value.requireExactFields(INTEGRITY_FIELDS)
        return PortableBackupIntegrity(
            algorithm = value.requiredString("algorithm"),
            documentSha256 = value.requiredString("documentSha256"),
        )
    }

    private fun canonicalUnsignedJson(snapshot: PortableBackupSnapshot): String = buildString {
        append('{')
        append("\"formatVersion\":")
        append(PortableBackupDocument.CURRENT_FORMAT_VERSION)
        append(",\"createdAtEpochMillis\":")
        append(snapshot.createdAtEpochMillis)
        append(",\"dataSchemaVersion\":")
        append(snapshot.dataSchemaVersion)
        append(",\"payload\":")
        appendPayload(snapshot.payload)
        append('}')
    }

    private fun StringBuilder.appendPayload(payload: PortableBackupPayload) {
        append('{')
        append("\"profiles\":[")
        payload.profiles.forEachIndexed { index, profile ->
            if (index > 0) append(',')
            appendProfile(profile)
        }
        append("],\"sources\":[")
        payload.sources.forEachIndexed { index, source ->
            if (index > 0) append(',')
            appendSource(source)
        }
        append("],\"channelOverlays\":[")
        payload.channelOverlays.forEachIndexed { index, overlay ->
            if (index > 0) append(',')
            appendOverlay(overlay)
        }
        append("],\"recentChannels\":[")
        payload.recentChannels.forEachIndexed { index, recent ->
            if (index > 0) append(',')
            appendRecent(recent)
        }
        append("]}")
    }

    private fun StringBuilder.appendProfile(value: PortableBackupProfile) {
        append("{\"id\":")
        appendQuoted(value.id)
        append(",\"name\":")
        appendQuoted(value.name)
        append(",\"isPrimary\":")
        append(value.isPrimary)
        append(",\"archivedAtEpochMillis\":")
        appendNullable(value.archivedAtEpochMillis)
        append('}')
    }

    private fun StringBuilder.appendSource(value: PortableBackupSource) {
        append("{\"id\":")
        appendQuoted(value.id)
        append(",\"name\":")
        appendQuoted(value.name)
        append(",\"recoveryState\":")
        appendQuoted(PortableSourceRecoveryState.REAUTH_REQUIRED.name)
        append('}')
    }

    private fun StringBuilder.appendOverlay(value: PortableChannelOverlay) {
        append("{\"profileId\":")
        appendQuoted(value.profileId)
        append(",\"canonicalChannelId\":")
        appendQuoted(value.canonicalChannelId)
        append(",\"isFavorite\":")
        append(value.isFavorite)
        append(",\"customName\":")
        appendNullableQuoted(value.customName)
        append(",\"channelNumber\":")
        appendNullable(value.channelNumber)
        append(",\"isHidden\":")
        append(value.isHidden)
        append('}')
    }

    private fun StringBuilder.appendRecent(value: PortableRecentChannel) {
        append("{\"profileId\":")
        appendQuoted(value.profileId)
        append(",\"canonicalChannelId\":")
        appendQuoted(value.canonicalChannelId)
        append(",\"lastSuccessfulPlaybackAtEpochMillis\":")
        append(value.lastSuccessfulPlaybackAtEpochMillis)
        append('}')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append(JsonPrimitive(value).toString())
    }

    private fun StringBuilder.appendNullableQuoted(value: String?) {
        if (value == null) append("null") else appendQuoted(value)
    }

    private fun StringBuilder.appendNullable(value: Number?) {
        if (value == null) append("null") else append(value)
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance(PortableBackupIntegrity.SHA_256_ALGORITHM).digest(bytes)

    private fun ByteArray.toLowerHex(): String = buildString(size * 2) {
        for (byte in this@toLowerHex) {
            val value = byte.toInt() and 0xff
            append(HEX[value ushr 4])
            append(HEX[value and 0x0f])
        }
    }

    private fun String.hexToBytes(): ByteArray {
        if (!matches(SHA_256_PATTERN)) {
            throw PortableBackupValidationException(PortableBackupRejectReason.INTEGRITY_MISMATCH)
        }
        return ByteArray(length / 2) { index ->
            val offset = index * 2
            ((this[offset].digitToInt(16) shl 4) or this[offset + 1].digitToInt(16)).toByte()
        }
    }

    private val ROOT_FIELDS = setOf(
        "formatVersion",
        "createdAtEpochMillis",
        "dataSchemaVersion",
        "payload",
        "integrity",
    )
    private val PAYLOAD_FIELDS = setOf(
        "profiles",
        "sources",
        "channelOverlays",
        "recentChannels",
    )
    private val PROFILE_FIELDS = setOf("id", "name", "isPrimary", "archivedAtEpochMillis")
    private val SOURCE_FIELDS = setOf("id", "name", "recoveryState")
    private val OVERLAY_FIELDS = setOf(
        "profileId",
        "canonicalChannelId",
        "isFavorite",
        "customName",
        "channelNumber",
        "isHidden",
    )
    private val RECENT_FIELDS = setOf(
        "profileId",
        "canonicalChannelId",
        "lastSuccessfulPlaybackAtEpochMillis",
    )
    private val INTEGRITY_FIELDS = setOf("algorithm", "documentSha256")
    private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
    private const val HEX = "0123456789abcdef"
}

private fun JsonElement.asObject(): JsonObject =
    this as? JsonObject ?: throw MalformedBackupException()

private fun JsonObject.requireExactFields(expected: Set<String>) {
    if ((keys - expected).isNotEmpty()) {
        throw UnknownBackupFieldException()
    }
    if ((expected - keys).isNotEmpty()) {
        throw MalformedBackupException()
    }
}

private fun JsonObject.requiredObject(name: String): JsonObject =
    this[name]?.asObject() ?: throw MalformedBackupException()

private fun JsonObject.requiredArray(name: String): JsonArray =
    this[name] as? JsonArray ?: throw MalformedBackupException()

private fun JsonObject.requiredString(name: String): String =
    primitive(name).contentOrNull ?: throw MalformedBackupException()

private fun JsonObject.requiredInt(name: String): Int =
    primitive(name).intOrNull ?: throw MalformedBackupException()

private fun JsonObject.requiredLong(name: String): Long =
    primitive(name).longOrNull ?: throw MalformedBackupException()

private fun JsonObject.requiredBoolean(name: String): Boolean =
    primitive(name).booleanOrNull ?: throw MalformedBackupException()

private fun JsonObject.optionalString(name: String): String? {
    val value = this[name] ?: throw MalformedBackupException()
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.contentOrNull ?: throw MalformedBackupException()
}

private fun JsonObject.optionalInt(name: String): Int? {
    val value = this[name] ?: throw MalformedBackupException()
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.intOrNull ?: throw MalformedBackupException()
}

private fun JsonObject.optionalLong(name: String): Long? {
    val value = this[name] ?: throw MalformedBackupException()
    if (value === JsonNull) return null
    return (value as? JsonPrimitive)?.longOrNull ?: throw MalformedBackupException()
}

private fun JsonObject.primitive(name: String): JsonPrimitive =
    this[name] as? JsonPrimitive ?: throw MalformedBackupException()

private class UnknownBackupFieldException : RuntimeException()
private class MalformedBackupException : RuntimeException()
