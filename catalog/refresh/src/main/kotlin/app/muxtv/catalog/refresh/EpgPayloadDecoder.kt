package app.muxtv.catalog.refresh

import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

enum class EpgPayloadFormat {
    Plain,
    Gzip,
    Zip,
}

data class EpgPayloadHints(
    val contentEncoding: String? = null,
    val contentType: String? = null,
) {
    override fun toString(): String =
        "EpgPayloadHints(contentEncodingPresent=${!contentEncoding.isNullOrBlank()}, " +
            "contentTypePresent=${!contentType.isNullOrBlank()})"
}

data class EpgPayloadDecodeLimits(
    val maxDecodedBytes: Long = DEFAULT_MAX_DECODED_BYTES,
    val maxLeadingZipEntries: Int = DEFAULT_MAX_LEADING_ZIP_ENTRIES,
    val maxZipEntryNameChars: Int = DEFAULT_MAX_ZIP_ENTRY_NAME_CHARS,
) {
    init {
        require(maxDecodedBytes in 1..MAX_DECODED_BYTES)
        require(maxLeadingZipEntries in 1..MAX_LEADING_ZIP_ENTRIES)
        require(maxZipEntryNameChars in 1..MAX_ZIP_ENTRY_NAME_CHARS)
    }

    private companion object {
        const val DEFAULT_MAX_DECODED_BYTES = 64L * 1024 * 1024
        const val MAX_DECODED_BYTES = 512L * 1024 * 1024
        const val DEFAULT_MAX_LEADING_ZIP_ENTRIES = 8
        const val MAX_LEADING_ZIP_ENTRIES = 64
        const val DEFAULT_MAX_ZIP_ENTRY_NAME_CHARS = 256
        const val MAX_ZIP_ENTRY_NAME_CHARS = 1_024
    }
}

enum class EpgPayloadRejectionReason {
    EmptyPayload,
    UnsupportedContentEncoding,
    DecodedSizeExceeded,
    MalformedGzip,
    MalformedZip,
    ZipPayloadEntryMissing,
    ZipLeadingEntryLimitExceeded,
    ZipEntryNameTooLong,
}

sealed interface EpgPayloadDecodeResult<out T> {
    data class Decoded<T>(
        val format: EpgPayloadFormat,
        val value: T,
    ) : EpgPayloadDecodeResult<T> {
        override fun toString(): String = "Decoded(format=$format)"
    }

    data class Rejected(
        val reason: EpgPayloadRejectionReason,
    ) : EpgPayloadDecodeResult<Nothing>
}

class EpgPayloadDecoder {
    suspend fun <T> decode(
        input: InputStream,
        hints: EpgPayloadHints = EpgPayloadHints(),
        limits: EpgPayloadDecodeLimits = EpgPayloadDecodeLimits(),
        consume: suspend (InputStream) -> T,
    ): EpgPayloadDecodeResult<T> {
        val source = PushbackInputStream(input, MAGIC_BYTE_COUNT)
        return try {
            val sniffed = ByteArray(MAGIC_BYTE_COUNT)
            val sniffedCount = readPrefix(source, sniffed)
            if (sniffedCount == 0) {
                return EpgPayloadDecodeResult.Rejected(EpgPayloadRejectionReason.EmptyPayload)
            }
            source.unread(sniffed, 0, sniffedCount)

            val emptyZipArchiveMagic = isEmptyZipArchiveMagic(sniffed, sniffedCount)
            when (val selection = selectFormat(sniffed, sniffedCount, hints)) {
                is FormatSelection.Rejected -> EpgPayloadDecodeResult.Rejected(selection.reason)

                is FormatSelection.Selected -> when (selection.format) {
                    EpgPayloadFormat.Plain -> decodePlain(source, limits, consume)
                    EpgPayloadFormat.Gzip -> decodeGzip(source, limits, consume)
                    EpgPayloadFormat.Zip -> decodeZip(
                        input = source,
                        limits = limits,
                        emptyArchiveMagic = emptyZipArchiveMagic,
                        consume = consume,
                    )
                }
            }
        } catch (failure: DecoderFailure) {
            EpgPayloadDecodeResult.Rejected(failure.reason)
        } finally {
            source.closeQuietly()
        }
    }

    private suspend fun <T> decodePlain(
        input: InputStream,
        limits: EpgPayloadDecodeLimits,
        consume: suspend (InputStream) -> T,
    ): EpgPayloadDecodeResult<T> = try {
        val bounded = DecodedByteLimitInputStream(input, limits.maxDecodedBytes)
        val value = consume(bounded)
        bounded.throwIfLimitExceeded()
        EpgPayloadDecodeResult.Decoded(EpgPayloadFormat.Plain, value)
    } finally {
        input.closeQuietly()
    }

    private suspend fun <T> decodeGzip(
        input: InputStream,
        limits: EpgPayloadDecodeLimits,
        consume: suspend (InputStream) -> T,
    ): EpgPayloadDecodeResult<T> {
        val gzip = try {
            GZIPInputStream(input)
        } catch (_: ZipException) {
            input.closeQuietly()
            throw DecoderFailure(EpgPayloadRejectionReason.MalformedGzip)
        } catch (_: EOFException) {
            input.closeQuietly()
            throw DecoderFailure(EpgPayloadRejectionReason.MalformedGzip)
        }

        return try {
            val normalized = NormalizedCompressedInputStream(
                delegate = gzip,
                malformedReason = EpgPayloadRejectionReason.MalformedGzip,
            )
            val bounded = DecodedByteLimitInputStream(normalized, limits.maxDecodedBytes)
            val value = consume(bounded)
            bounded.throwIfLimitExceeded()
            EpgPayloadDecodeResult.Decoded(EpgPayloadFormat.Gzip, value)
        } finally {
            gzip.closeQuietly()
        }
    }

    private suspend fun <T> decodeZip(
        input: InputStream,
        limits: EpgPayloadDecodeLimits,
        emptyArchiveMagic: Boolean,
        consume: suspend (InputStream) -> T,
    ): EpgPayloadDecodeResult<T> {
        val archive = ZipInputStream(input)
        try {
            var leadingEntries = 0
            var seenEntries = 0
            while (true) {
                val entry = try {
                    archive.nextEntry
                } catch (_: ZipException) {
                    throw DecoderFailure(EpgPayloadRejectionReason.MalformedZip)
                } catch (_: EOFException) {
                    throw DecoderFailure(EpgPayloadRejectionReason.MalformedZip)
                } ?: return EpgPayloadDecodeResult.Rejected(
                    if (seenEntries > 0 || emptyArchiveMagic) {
                        EpgPayloadRejectionReason.ZipPayloadEntryMissing
                    } else {
                        EpgPayloadRejectionReason.MalformedZip
                    },
                )
                seenEntries += 1

                if (entry.name.length > limits.maxZipEntryNameChars) {
                    return EpgPayloadDecodeResult.Rejected(
                        EpgPayloadRejectionReason.ZipEntryNameTooLong,
                    )
                }
                if (entry.isDirectory) {
                    leadingEntries += 1
                    if (leadingEntries > limits.maxLeadingZipEntries) {
                        return EpgPayloadDecodeResult.Rejected(
                            EpgPayloadRejectionReason.ZipLeadingEntryLimitExceeded,
                        )
                    }
                    archive.closeEntry()
                    continue
                }

                val normalized = NormalizedCompressedInputStream(
                    delegate = archive,
                    malformedReason = EpgPayloadRejectionReason.MalformedZip,
                    closeDelegate = false,
                )
                val bounded = DecodedByteLimitInputStream(normalized, limits.maxDecodedBytes)
                val value = consume(bounded)
                bounded.throwIfLimitExceeded()
                return EpgPayloadDecodeResult.Decoded(EpgPayloadFormat.Zip, value)
            }
        } finally {
            archive.closeQuietly()
        }
    }

    private fun selectFormat(
        prefix: ByteArray,
        count: Int,
        hints: EpgPayloadHints,
    ): FormatSelection {
        magicFormat(prefix, count)?.let { return FormatSelection.Selected(it) }

        val encoding = hints.contentEncoding.normalizedHeaderToken()
        when (encoding) {
            null, "", "identity" -> Unit
            "gzip", "x-gzip" -> return FormatSelection.Selected(EpgPayloadFormat.Gzip)
            else -> return FormatSelection.Rejected(
                EpgPayloadRejectionReason.UnsupportedContentEncoding,
            )
        }

        return when (hints.contentType.normalizedMediaType()) {
            in GZIP_MEDIA_TYPES -> FormatSelection.Selected(EpgPayloadFormat.Gzip)
            in ZIP_MEDIA_TYPES -> FormatSelection.Selected(EpgPayloadFormat.Zip)
            else -> FormatSelection.Selected(EpgPayloadFormat.Plain)
        }
    }

    private fun isEmptyZipArchiveMagic(prefix: ByteArray, count: Int): Boolean =
        count >= 4 &&
            unsigned(prefix[0]) == ZIP_MAGIC_0 &&
            unsigned(prefix[1]) == ZIP_MAGIC_1 &&
            unsigned(prefix[2]) == ZIP_EMPTY_0 &&
            unsigned(prefix[3]) == ZIP_EMPTY_1

    private fun magicFormat(prefix: ByteArray, count: Int): EpgPayloadFormat? {
        if (count >= 2 && unsigned(prefix[0]) == GZIP_MAGIC_0 && unsigned(prefix[1]) == GZIP_MAGIC_1) {
            return EpgPayloadFormat.Gzip
        }
        if (count >= 4 && unsigned(prefix[0]) == ZIP_MAGIC_0 && unsigned(prefix[1]) == ZIP_MAGIC_1) {
            val third = unsigned(prefix[2])
            val fourth = unsigned(prefix[3])
            if (
                (third == ZIP_LOCAL_0 && fourth == ZIP_LOCAL_1) ||
                (third == ZIP_EMPTY_0 && fourth == ZIP_EMPTY_1) ||
                (third == ZIP_SPANNED_0 && fourth == ZIP_SPANNED_1)
            ) {
                return EpgPayloadFormat.Zip
            }
        }
        return null
    }

    private fun readPrefix(input: InputStream, destination: ByteArray): Int {
        var count = 0
        while (count < destination.size) {
            val read = input.read(destination, count, destination.size - count)
            if (read < 0) break
            if (read == 0) {
                val single = input.read()
                if (single < 0) break
                destination[count] = single.toByte()
                count += 1
            } else {
                count += read
            }
        }
        return count
    }

    private sealed interface FormatSelection {
        data class Selected(val format: EpgPayloadFormat) : FormatSelection
        data class Rejected(val reason: EpgPayloadRejectionReason) : FormatSelection
    }

    private companion object {
        const val MAGIC_BYTE_COUNT = 4
        const val GZIP_MAGIC_0 = 0x1f
        const val GZIP_MAGIC_1 = 0x8b
        const val ZIP_MAGIC_0 = 0x50
        const val ZIP_MAGIC_1 = 0x4b
        const val ZIP_LOCAL_0 = 0x03
        const val ZIP_LOCAL_1 = 0x04
        const val ZIP_EMPTY_0 = 0x05
        const val ZIP_EMPTY_1 = 0x06
        const val ZIP_SPANNED_0 = 0x07
        const val ZIP_SPANNED_1 = 0x08

        val GZIP_MEDIA_TYPES = setOf(
            "application/gzip",
            "application/x-gzip",
        )
        val ZIP_MEDIA_TYPES = setOf(
            "application/zip",
            "application/x-zip-compressed",
        )
    }
}

private class DecodedByteLimitInputStream(
    input: InputStream,
    private val maxDecodedBytes: Long,
) : FilterInputStream(input) {
    private var decodedBytes = 0L
    private var limitExceeded = false
    private val skipBuffer = ByteArray(DEFAULT_BUFFER_SIZE)

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val remainingWithSentinel = maxDecodedBytes - decodedBytes + 1
        val boundedLength = minOf(length.toLong(), remainingWithSentinel.coerceAtLeast(1)).toInt()
        val count = super.read(buffer, offset, boundedLength)
        if (count > 0) record(count.toLong())
        return count
    }

    override fun skip(byteCount: Long): Long {
        if (byteCount <= 0) return 0
        var remaining = byteCount
        var skipped = 0L
        while (remaining > 0) {
            val requested = minOf(remaining, skipBuffer.size.toLong()).toInt()
            val count = read(skipBuffer, 0, requested)
            if (count < 0) break
            skipped += count
            remaining -= count
        }
        return skipped
    }

    fun throwIfLimitExceeded() {
        if (limitExceeded) {
            throw DecoderFailure(EpgPayloadRejectionReason.DecodedSizeExceeded)
        }
    }

    private fun record(count: Long) {
        decodedBytes += count
        if (decodedBytes > maxDecodedBytes) {
            limitExceeded = true
            throw DecoderFailure(EpgPayloadRejectionReason.DecodedSizeExceeded)
        }
    }
}

private class NormalizedCompressedInputStream(
    delegate: InputStream,
    private val malformedReason: EpgPayloadRejectionReason,
    private val closeDelegate: Boolean = true,
) : FilterInputStream(delegate) {
    override fun read(): Int = normalize { super.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        normalize { super.read(buffer, offset, length) }

    override fun skip(byteCount: Long): Long = normalize { super.skip(byteCount) }

    override fun close() {
        if (closeDelegate) super.close()
    }

    private inline fun <T> normalize(block: () -> T): T = try {
        block()
    } catch (_: ZipException) {
        throw DecoderFailure(malformedReason)
    } catch (_: EOFException) {
        throw DecoderFailure(malformedReason)
    }
}

private class DecoderFailure(
    val reason: EpgPayloadRejectionReason,
) : IOException()

private fun String?.normalizedHeaderToken(): String? =
    this?.trim()?.lowercase(Locale.ROOT)

private fun String?.normalizedMediaType(): String? =
    this?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)

private fun unsigned(value: Byte): Int = value.toInt() and 0xff

private fun InputStream.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // The decoder failure remains authoritative.
    }
}