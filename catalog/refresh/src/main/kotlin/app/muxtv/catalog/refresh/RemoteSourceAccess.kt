package app.muxtv.catalog.refresh

import app.muxtv.credentials.SecretBytes
import app.muxtv.network.ExactHttpOrigin
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashSet
import java.util.Locale

class PlaybackApprovalCapacityExceededException : IllegalArgumentException(
    "Playback approval capacity is exceeded.",
)

class RemoteSourceAccess(
    val url: String,
    val insecureHttpApproved: Boolean = false,
    val userAgent: String? = null,
    val referrer: String? = null,
    sensitiveHeaders: Map<String, String> = emptyMap(),
    approvedPlaybackOrigins: Set<ExactHttpOrigin> = emptySet(),
) {
    val sensitiveHeaders: Map<String, String> = normalizeSensitiveHeaders(sensitiveHeaders)
    val approvedPlaybackOrigins: Set<ExactHttpOrigin> = normalizeApprovedPlaybackOrigins(
        approvedPlaybackOrigins,
    )

    init {
        require(url.isNotBlank()) { "Remote source URL must not be blank." }
        require(url.length <= MAX_URL_CHARACTERS) { "Remote source URL is too long." }
        validateOptionalHeaderValue("User-Agent", userAgent)
        validateOptionalHeaderValue("Referer", referrer)
    }

    fun approvesPlayback(playbackUrl: String): Boolean =
        ExactHttpOrigin.fromUrl(playbackUrl)?.let(approvedPlaybackOrigins::contains) == true

    fun withApprovedPlaybackOrigin(origin: ExactHttpOrigin): RemoteSourceAccess {
        if (origin in approvedPlaybackOrigins) return this
        if (approvedPlaybackOrigins.size >= MAX_APPROVED_PLAYBACK_ORIGINS) {
            throw PlaybackApprovalCapacityExceededException()
        }
        return copyWith(approvedPlaybackOrigins = approvedPlaybackOrigins + origin)
    }

    fun withoutApprovedPlaybackOrigin(origin: ExactHttpOrigin): RemoteSourceAccess {
        if (origin !in approvedPlaybackOrigins) return this
        return copyWith(approvedPlaybackOrigins = approvedPlaybackOrigins - origin)
    }

    fun withoutPlaybackApprovals(): RemoteSourceAccess =
        if (approvedPlaybackOrigins.isEmpty()) this else copyWith(approvedPlaybackOrigins = emptySet())

    fun withSourceUrl(
        url: String,
        insecureHttpApproved: Boolean,
    ): RemoteSourceAccess = RemoteSourceAccess(
        url = url,
        insecureHttpApproved = insecureHttpApproved,
        userAgent = userAgent,
        referrer = referrer,
        sensitiveHeaders = sensitiveHeaders,
        approvedPlaybackOrigins = if (insecureHttpApproved) {
            ExactHttpOrigin.fromUrl(url)?.let(::setOf).orEmpty()
        } else {
            emptySet()
        },
    )

    override fun toString(): String =
        "RemoteSourceAccess(url=<redacted>, insecureHttpApproved=$insecureHttpApproved, " +
            "hasUserAgent=${userAgent != null}, hasReferrer=${referrer != null}, " +
            "sensitiveHeaderNames=${sensitiveHeaders.keys}, " +
            "approvedPlaybackOriginCount=${approvedPlaybackOrigins.size})"

    private fun copyWith(
        approvedPlaybackOrigins: Set<ExactHttpOrigin> = this.approvedPlaybackOrigins,
    ): RemoteSourceAccess = RemoteSourceAccess(
        url = url,
        insecureHttpApproved = insecureHttpApproved,
        userAgent = userAgent,
        referrer = referrer,
        sensitiveHeaders = sensitiveHeaders,
        approvedPlaybackOrigins = approvedPlaybackOrigins,
    )

    companion object {
        internal const val MAX_URL_CHARACTERS = 8 * 1024
        internal const val MAX_HEADER_VALUE_CHARACTERS = 8 * 1024
        internal const val MAX_SENSITIVE_HEADERS = 5
        internal const val MAX_APPROVED_PLAYBACK_ORIGINS = 16
        internal const val MAX_APPROVED_ORIGIN_CHARACTERS = 512
    }
}

enum class RemoteSourceAccessFormatReason {
    InvalidMagic,
    UnsupportedVersion,
    Truncated,
    TrailingData,
    InvalidField,
    TooManyHeaders,
    InvalidHeader,
    TooManyOrigins,
    InvalidOrigin,
}

class RemoteSourceAccessFormatException(
    val reason: RemoteSourceAccessFormatReason,
    cause: Throwable? = null,
) : IllegalArgumentException("Remote source access record is invalid.", cause)

object RemoteSourceAccessCodec {
    fun encode(access: RemoteSourceAccess): SecretBytes {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeByte(CURRENT_VERSION)
            data.writeBoolean(access.insecureHttpApproved)
            data.writeString(access.url)
            data.writeNullableString(access.userAgent)
            data.writeNullableString(access.referrer)
            data.writeByte(access.sensitiveHeaders.size)
            access.sensitiveHeaders.forEach { (name, value) ->
                data.writeString(name)
                data.writeString(value)
            }
            data.writeByte(access.approvedPlaybackOrigins.size)
            access.approvedPlaybackOrigins.forEach { origin ->
                data.writeString(origin.encoded())
            }
        }

        val bytes = output.toByteArray()
        return try {
            SecretBytes.copyOf(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun decode(secret: SecretBytes): RemoteSourceAccess = secret.useBytes(::decode)

    internal fun decode(encoded: ByteArray): RemoteSourceAccess {
        try {
            val input = ByteArrayInputStream(encoded)
            val data = DataInputStream(input)
            val magic = ByteArray(MAGIC.size)
            if (data.read(magic) != MAGIC.size) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.Truncated)
            }
            if (!magic.contentEquals(MAGIC)) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidMagic)
            }
            val version = data.readUnsignedByte()
            if (version != LEGACY_VERSION && version != CURRENT_VERSION) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.UnsupportedVersion)
            }

            val insecureHttpApproved = data.readBoolean()
            val url = data.readBoundedString(RemoteSourceAccess.MAX_URL_CHARACTERS)
            val userAgent = data.readNullableBoundedString(RemoteSourceAccess.MAX_HEADER_VALUE_CHARACTERS)
            val referrer = data.readNullableBoundedString(RemoteSourceAccess.MAX_HEADER_VALUE_CHARACTERS)
            val headerCount = data.readUnsignedByte()
            if (headerCount > RemoteSourceAccess.MAX_SENSITIVE_HEADERS) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.TooManyHeaders)
            }

            val headers = linkedMapOf<String, String>()
            repeat(headerCount) {
                val name = data.readBoundedString(MAX_HEADER_NAME_CHARACTERS)
                val value = data.readBoundedString(RemoteSourceAccess.MAX_HEADER_VALUE_CHARACTERS)
                if (headers.put(name, value) != null) {
                    throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidHeader)
                }
            }

            val approvedOrigins = if (version == CURRENT_VERSION) {
                data.readApprovedOrigins()
            } else if (insecureHttpApproved) {
                ExactHttpOrigin.fromUrl(url)?.let(::setOf).orEmpty()
            } else {
                emptySet()
            }
            if (input.available() != 0) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.TrailingData)
            }

            return RemoteSourceAccess(
                url = url,
                insecureHttpApproved = insecureHttpApproved,
                userAgent = userAgent,
                referrer = referrer,
                sensitiveHeaders = headers,
                approvedPlaybackOrigins = approvedOrigins,
            )
        } catch (error: RemoteSourceAccessFormatException) {
            throw error
        } catch (error: PlaybackApprovalCapacityExceededException) {
            throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.TooManyOrigins, error)
        } catch (error: Exception) {
            throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.Truncated, error)
        }
    }

    private fun DataInputStream.readApprovedOrigins(): Set<ExactHttpOrigin> {
        val count = readUnsignedByte()
        if (count > RemoteSourceAccess.MAX_APPROVED_PLAYBACK_ORIGINS) {
            throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.TooManyOrigins)
        }
        val origins = linkedSetOf<ExactHttpOrigin>()
        repeat(count) {
            val encoded = readBoundedString(RemoteSourceAccess.MAX_APPROVED_ORIGIN_CHARACTERS)
            val origin = ExactHttpOrigin.parse(encoded)
                ?: throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidOrigin)
            if (!origins.add(origin)) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidOrigin)
            }
        }
        return origins
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        try {
            writeInt(bytes.size)
            write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        if (value == null) {
            writeInt(-1)
        } else {
            writeString(value)
        }
    }

    private fun DataInputStream.readBoundedString(maxCharacters: Int): String {
        val byteLength = readInt()
        if (byteLength < 0 || byteLength > MAX_ENCODED_FIELD_BYTES) {
            throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidField)
        }
        val bytes = ByteArray(byteLength)
        readFully(bytes)
        return try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
            if (decoded.length > maxCharacters) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidField)
            }
            decoded
        } finally {
            bytes.fill(0)
        }
    }

    private fun DataInputStream.readNullableBoundedString(maxCharacters: Int): String? {
        val byteLength = readInt()
        if (byteLength == -1) return null
        if (byteLength < 0 || byteLength > MAX_ENCODED_FIELD_BYTES) {
            throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidField)
        }
        val bytes = ByteArray(byteLength)
        readFully(bytes)
        return try {
            val decoded = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
            if (decoded.length > maxCharacters) {
                throw RemoteSourceAccessFormatException(RemoteSourceAccessFormatReason.InvalidField)
            }
            decoded
        } finally {
            bytes.fill(0)
        }
    }

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 'S'.code.toByte(), 'A'.code.toByte())
    private const val LEGACY_VERSION = 1
    private const val CURRENT_VERSION = 2
    private const val MAX_ENCODED_FIELD_BYTES = 32 * 1024
    private const val MAX_HEADER_NAME_CHARACTERS = 64
}

private fun normalizeSensitiveHeaders(headers: Map<String, String>): Map<String, String> {
    require(headers.size <= RemoteSourceAccess.MAX_SENSITIVE_HEADERS) {
        "Too many sensitive source headers."
    }

    return buildMap {
        headers.forEach { (rawName, value) ->
            val normalized = rawName.trim().lowercase(Locale.ROOT)
            val canonicalName = ALLOWED_SENSITIVE_HEADERS[normalized]
                ?: throw IllegalArgumentException("Unsupported sensitive source header.")
            validateOptionalHeaderValue(canonicalName, value)
            put(canonicalName, value)
        }
    }
}

private fun normalizeApprovedPlaybackOrigins(
    origins: Set<ExactHttpOrigin>,
): Set<ExactHttpOrigin> {
    val normalized = LinkedHashSet<ExactHttpOrigin>()
    origins.sortedBy(ExactHttpOrigin::encoded).forEach(normalized::add)
    if (normalized.size > RemoteSourceAccess.MAX_APPROVED_PLAYBACK_ORIGINS) {
        throw PlaybackApprovalCapacityExceededException()
    }
    return Collections.unmodifiableSet(normalized)
}

private fun validateOptionalHeaderValue(
    name: String,
    value: String?,
) {
    if (value == null) return
    require(value.isNotEmpty()) { "$name must not be empty." }
    require(value.length <= RemoteSourceAccess.MAX_HEADER_VALUE_CHARACTERS) {
        "$name is too long."
    }
    require(value.none { character -> character == '\r' || character == '\n' || character == '\u0000' }) {
        "$name contains a prohibited control character."
    }
}

private val ALLOWED_SENSITIVE_HEADERS = mapOf(
    "authorization" to "Authorization",
    "cookie" to "Cookie",
    "x-api-key" to "X-Api-Key",
    "x-auth-token" to "X-Auth-Token",
    "x-access-token" to "X-Access-Token",
)
