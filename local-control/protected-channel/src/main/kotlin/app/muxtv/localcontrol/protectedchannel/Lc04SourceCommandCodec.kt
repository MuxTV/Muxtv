package app.muxtv.localcontrol.protectedchannel

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Source-command PLAINTEXT schema, NOT a transport or a security boundary.
 *
 * These bytes must NEVER be sent over /pair, raw TCP or persisted. A separately
 * authenticated, reviewed AEAD owner must encrypt and authenticate every
 * request and reply, bind its sequence to the original TV-approved session,
 * and reject transport replays before decoding. The old MXR1 fixed-domain
 * synthetic key owner cannot be reused to protect these records.
 *
 * All source-bearing classes deliberately hide their contents in toString().
 * There is no remote 'approve insecure HTTP' flag or implicit source grant.
 */
sealed class Lc04SourceCommand(val sequence: Long) {
    class PrepareM3u(sequence: Long, val locator: String) : Lc04SourceCommand(sequence)
    class PrepareXtream(
        sequence: Long,
        val endpoint: String,
        val username: String,
        val password: String,
    ) : Lc04SourceCommand(sequence)
    class Activate(sequence: Long, val sourceName: String) : Lc04SourceCommand(sequence)
    class Cancel(sequence: Long) : Lc04SourceCommand(sequence)

    final override fun toString(): String = "Lc04SourceCommand([REDACTED])"
}

/** Fixed, provider-secret-free replies. Numeric IDs are stable on the wire. */
enum class Lc04SourceReplyCode(val wireId: Int) {
    PREPARED(1),
    ACTIVATED(2),
    CANCELED(3),
    EMPTY(4),
    ACCESS_DENIED(5),
    INVALID_INPUT(6),
    ALREADY_PREPARED(7),
    LOCAL_NETWORK_PERMISSION_REQUIRED(8),
    TV_HTTP_APPROVAL_REQUIRED(9),
    CLEANUP_PENDING(10),
    ACTIVATION_OUTCOME_UNKNOWN(11),
    FAILED(12),
    TV_CONFIRMATION_REQUIRED(13),
}

class Lc04SourceReply(val sequence: Long, val code: Lc04SourceReplyCode) {
    override fun toString(): String = "Lc04SourceReply(sequence=[REDACTED], status=" + code.name + ")"
}

/**
 * Minimal canonical binary record schema (no JSON duplicate keys or unknown
 * fields): 4-byte magic, version, opcode, zero flags, zero reserved, u64
 * strictly positive sequence, then u16 length-prefixed strict UTF-8 fields.
 *
 * Request maximum is 6,656 bytes; fields are bounded at TV staging limits.
 * Parse rejects padding/trailing bytes, malformed Unicode, unknown flags,
 * unknown opcodes and partial frames. No authentication is performed here.
 */
object Lc04SourceCommandCodec {
    const val MAX_REQUEST_BYTES = 6_656
    private const val HEADER_BYTES = 16
    private const val VERSION = 1
    private const val M3U = 1
    private const val XTREAM = 2
    private const val ACTIVATE = 3
    private const val CANCEL = 4
    private val REQUEST_MAGIC = byteArrayOf(0x4d, 0x58, 0x53, 0x43) // MXSC
    private val REPLY_MAGIC = byteArrayOf(0x4d, 0x58, 0x53, 0x52) // MXSR

    fun encodeRequest(command: Lc04SourceCommand): ByteArray? {
        if (command.sequence <= 0) return null
        return try {
            val bytes = ByteArrayOutputStream()
            val output = DataOutputStream(bytes)
            output.write(REQUEST_MAGIC)
            output.writeByte(VERSION)
            output.writeByte(when (command) {
                is Lc04SourceCommand.PrepareM3u -> M3U
                is Lc04SourceCommand.PrepareXtream -> XTREAM
                is Lc04SourceCommand.Activate -> ACTIVATE
                is Lc04SourceCommand.Cancel -> CANCEL
            })
            output.writeShort(0) // No unknown, insecure-HTTP or privilege flags.
            output.writeLong(command.sequence)
            when (command) {
                is Lc04SourceCommand.PrepareM3u ->
                    writeField(output, command.locator, 4_096, false)
                is Lc04SourceCommand.PrepareXtream -> {
                    writeField(output, command.endpoint, 4_096, false)
                    writeField(output, command.username, 256, false)
                    writeField(output, command.password, 2_048, false)
                }
                is Lc04SourceCommand.Activate ->
                    writeField(output, command.sourceName, 120, true)
                is Lc04SourceCommand.Cancel -> Unit
            }
            bytes.toByteArray().takeIf { it.size in HEADER_BYTES..MAX_REQUEST_BYTES }
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Returns null on ANY malformed or non-canonical untrusted plaintext. */
    fun decodeRequest(untrusted: ByteArray): Lc04SourceCommand? {
        if (untrusted.size !in HEADER_BYTES..MAX_REQUEST_BYTES) return null
        val frame = untrusted.clone()
        return try {
            val input = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            if (!magic(input, REQUEST_MAGIC) || input.get().toInt() != VERSION) return null
            val opcode = input.get().toInt() and 0xff
            if (input.short.toInt() != 0) return null
            val sequence = input.long
            if (sequence <= 0) return null
            val command = when (opcode) {
                M3U -> Lc04SourceCommand.PrepareM3u(
                    sequence, readField(input, 4_096, false) ?: return null,
                )
                XTREAM -> Lc04SourceCommand.PrepareXtream(
                    sequence,
                    readField(input, 4_096, false) ?: return null,
                    readField(input, 256, false) ?: return null,
                    readField(input, 2_048, false) ?: return null,
                )
                ACTIVATE -> Lc04SourceCommand.Activate(
                    sequence, readField(input, 120, true) ?: return null,
                )
                CANCEL -> Lc04SourceCommand.Cancel(sequence)
                else -> return null
            }
            if (input.hasRemaining()) null else command
        } catch (_: CharacterCodingException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            frame.fill(0)
        }
    }

    fun encodeReply(reply: Lc04SourceReply): ByteArray? {
        if (reply.sequence <= 0) return null
        return ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .put(REPLY_MAGIC).put(VERSION.toByte()).put(reply.code.wireId.toByte())
            .putShort(0).putLong(reply.sequence).array()
    }

    fun decodeReply(untrusted: ByteArray): Lc04SourceReply? {
        if (untrusted.size != HEADER_BYTES) return null
        val input = ByteBuffer.wrap(untrusted).order(ByteOrder.BIG_ENDIAN)
        if (!magic(input, REPLY_MAGIC) || input.get().toInt() != VERSION) return null
        val codeId = input.get().toInt() and 0xff
        if (input.short.toInt() != 0) return null
        val sequence = input.long
        if (sequence <= 0) return null
        val code = Lc04SourceReplyCode.entries.firstOrNull { it.wireId == codeId }
            ?: return null
        return Lc04SourceReply(sequence, code)
    }

    private fun writeField(out: DataOutputStream, text: String, max: Int, name: Boolean) {
        require(text.isNotBlank() && text.length <= max)
        require(!name || (text.trim() == text && text.none { it.isISOControl() }))
        val encoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val buf = encoder.encode(java.nio.CharBuffer.wrap(text))
        require(buf.remaining() in 1..max)
        val bytes = ByteArray(buf.remaining())
        buf.get(bytes)
        try {
            out.writeShort(bytes.size)
            out.write(bytes)
        } finally { bytes.fill(0) }
    }

    private fun readField(input: ByteBuffer, max: Int, name: Boolean): String? {
        if (input.remaining() < 2) return null
        val size = input.short.toInt() and 0xffff
        if (size !in 1..max || size > input.remaining()) return null
        val bytes = ByteArray(size)
        input.get(bytes)
        return try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val result = decoder.decode(ByteBuffer.wrap(bytes)).toString()
            if (result.isBlank() || result.length > max ||
                (name && (result.trim() != result || result.any { it.isISOControl() }))
            ) null else result
        } finally { bytes.fill(0) }
    }

    private fun magic(input: ByteBuffer, expected: ByteArray): Boolean {
        if (input.remaining() < expected.size) return false
        var mismatch = 0
        for (byte in expected) mismatch = mismatch or
            ((input.get().toInt() and 0xff) xor (byte.toInt() and 0xff))
        return mismatch == 0
    }
}
