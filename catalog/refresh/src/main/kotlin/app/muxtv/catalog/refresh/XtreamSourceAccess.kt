package app.muxtv.catalog.refresh

import app.muxtv.credentials.CredentialId
import app.muxtv.credentials.CredentialReadResult
import app.muxtv.credentials.CredentialStore
import app.muxtv.credentials.CredentialUnavailableReason
import app.muxtv.credentials.CredentialWriteResult
import app.muxtv.credentials.SecretBytes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class XtreamSourceAccess(
    val baseUrl: String,
    val username: String,
    val password: String,
    val insecureHttpApproved: Boolean = false,
) {
    init {
        require(baseUrl.isNotBlank()) { "Xtream base URL must not be blank." }
        require(baseUrl.length <= MAX_BASE_URL_CHARACTERS) { "Xtream base URL is too long." }
        require(username.isNotBlank()) { "Xtream username must not be blank." }
        require(username.length <= MAX_USERNAME_CHARACTERS) { "Xtream username is too long." }
        require(password.isNotEmpty()) { "Xtream password must not be empty." }
        require(password.length <= MAX_PASSWORD_CHARACTERS) { "Xtream password is too long." }
        require(username.none(::isProhibitedControl)) { "Xtream username contains a prohibited control character." }
        require(password.none(::isProhibitedControl)) { "Xtream password contains a prohibited control character." }
    }

    override fun toString(): String =
        "XtreamSourceAccess(baseUrl=<redacted>, username=<redacted>, password=<redacted>, " +
            "insecureHttpApproved=$insecureHttpApproved)"

    companion object {
        internal const val MAX_BASE_URL_CHARACTERS = 8 * 1024
        internal const val MAX_USERNAME_CHARACTERS = 4 * 1024
        internal const val MAX_PASSWORD_CHARACTERS = 8 * 1024
    }
}

sealed interface XtreamSourceAccessReadResult {
    data class Found(
        val access: XtreamSourceAccess,
    ) : XtreamSourceAccessReadResult

    data object NotFound : XtreamSourceAccessReadResult
    data object Corrupted : XtreamSourceAccessReadResult

    data class Unavailable(
        val reason: CredentialUnavailableReason,
    ) : XtreamSourceAccessReadResult
}

class XtreamSourceAccessManager(
    private val credentialStore: CredentialStore,
) {
    private val mutex = Mutex()

    suspend fun save(
        id: CredentialId,
        access: XtreamSourceAccess,
    ): CredentialWriteResult = mutex.withLock {
        XtreamSourceAccessCodec.encode(access).use { encoded ->
            credentialStore.put(id, encoded)
        }
    }

    suspend fun read(id: CredentialId): XtreamSourceAccessReadResult = mutex.withLock {
        when (val credential = credentialStore.read(id)) {
            is CredentialReadResult.Found -> credential.secret.use { secret ->
                try {
                    XtreamSourceAccessReadResult.Found(XtreamSourceAccessCodec.decode(secret))
                } catch (_: XtreamSourceAccessFormatException) {
                    XtreamSourceAccessReadResult.Corrupted
                }
            }

            CredentialReadResult.NotFound -> XtreamSourceAccessReadResult.NotFound
            is CredentialReadResult.Unavailable -> XtreamSourceAccessReadResult.Unavailable(credential.reason)
        }
    }
}

private enum class XtreamSourceAccessFormatReason {
    InvalidMagic,
    UnsupportedVersion,
    Truncated,
    TrailingData,
    InvalidField,
}

private class XtreamSourceAccessFormatException(
    val reason: XtreamSourceAccessFormatReason,
    cause: Throwable? = null,
) : IllegalArgumentException("Xtream source access record is invalid.", cause)

private object XtreamSourceAccessCodec {
    fun encode(access: XtreamSourceAccess): SecretBytes {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data ->
            data.write(MAGIC)
            data.writeByte(VERSION)
            data.writeBoolean(access.insecureHttpApproved)
            data.writeString(access.baseUrl)
            data.writeString(access.username)
            data.writeString(access.password)
        }

        val bytes = output.toByteArray()
        return try {
            SecretBytes.copyOf(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    fun decode(secret: SecretBytes): XtreamSourceAccess = secret.useBytes(::decode)

    private fun decode(encoded: ByteArray): XtreamSourceAccess {
        try {
            val input = ByteArrayInputStream(encoded)
            val data = DataInputStream(input)
            val magic = ByteArray(MAGIC.size)
            if (data.read(magic) != MAGIC.size) {
                throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.Truncated)
            }
            if (!magic.contentEquals(MAGIC)) {
                throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.InvalidMagic)
            }
            if (data.readUnsignedByte() != VERSION) {
                throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.UnsupportedVersion)
            }

            val insecureHttpApproved = data.readBoolean()
            val baseUrl = data.readBoundedString(XtreamSourceAccess.MAX_BASE_URL_CHARACTERS)
            val username = data.readBoundedString(XtreamSourceAccess.MAX_USERNAME_CHARACTERS)
            val password = data.readBoundedString(XtreamSourceAccess.MAX_PASSWORD_CHARACTERS)
            if (input.available() != 0) {
                throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.TrailingData)
            }

            return try {
                XtreamSourceAccess(
                    baseUrl = baseUrl,
                    username = username,
                    password = password,
                    insecureHttpApproved = insecureHttpApproved,
                )
            } catch (error: IllegalArgumentException) {
                throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.InvalidField, error)
            }
        } catch (error: XtreamSourceAccessFormatException) {
            throw error
        } catch (error: Exception) {
            throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.Truncated, error)
        }
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

    private fun DataInputStream.readBoundedString(maxCharacters: Int): String {
        val byteLength = readInt()
        if (byteLength < 0 || byteLength > MAX_ENCODED_FIELD_BYTES) {
            throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.InvalidField)
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
                throw XtreamSourceAccessFormatException(XtreamSourceAccessFormatReason.InvalidField)
            }
            decoded
        } finally {
            bytes.fill(0)
        }
    }

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'X'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte())
    private const val VERSION = 1
    private const val MAX_ENCODED_FIELD_BYTES = 32 * 1024
}

private fun isProhibitedControl(character: Char): Boolean =
    character == '\r' || character == '\n' || character == '\u0000'
