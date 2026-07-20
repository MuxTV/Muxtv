package app.muxtv.network

import java.io.IOException
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

data class ResponseSizeLimits(
    val maxCompressedBytes: Long = DEFAULT_MAX_COMPRESSED_BYTES,
    val maxDecodedBytes: Long = DEFAULT_MAX_DECODED_BYTES,
) {
    init {
        require(maxCompressedBytes > 0) { "maxCompressedBytes must be positive." }
        require(maxDecodedBytes > 0) { "maxDecodedBytes must be positive." }
    }

    companion object {
        const val DEFAULT_MAX_COMPRESSED_BYTES: Long = 32L * 1024L * 1024L
        const val DEFAULT_MAX_DECODED_BYTES: Long = 128L * 1024L * 1024L
    }
}

enum class ResponseSizeKind {
    Compressed,
    Decoded,
}

class ResponseTooLargeException(
    val kind: ResponseSizeKind,
    val limitBytes: Long,
    val declaredBytes: Long?,
) : IOException(
    buildString {
        append(kind.name)
        append(" response body exceeds ")
        append(limitBytes)
        append(" bytes")
        declaredBytes?.let {
            append("; declared=")
            append(it)
        }
    },
)

class ResponseSizeLimitInterceptor(
    private val kind: ResponseSizeKind,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val limits = chain.request()
            .tag(SourceRequestContext::class)
            ?.responseSizeLimits
            ?: ResponseSizeLimits()
        val limitBytes = when (kind) {
            ResponseSizeKind.Compressed -> limits.maxCompressedBytes
            ResponseSizeKind.Decoded -> limits.maxDecodedBytes
        }
        val body = response.body
        val declaredBytes = body.contentLength().takeIf { it >= 0L }

        if (declaredBytes != null && declaredBytes > limitBytes) {
            response.close()
            throw ResponseTooLargeException(
                kind = kind,
                limitBytes = limitBytes,
                declaredBytes = declaredBytes,
            )
        }

        return response.newBuilder()
            .body(
                LimitedResponseBody(
                    delegate = body,
                    kind = kind,
                    limitBytes = limitBytes,
                ),
            )
            .build()
    }
}

private class LimitedResponseBody(
    private val delegate: ResponseBody,
    kind: ResponseSizeKind,
    limitBytes: Long,
) : ResponseBody() {
    private val limitedSource: BufferedSource = LimitedSource(
        delegate = delegate.source(),
        kind = kind,
        limitBytes = limitBytes,
    ).buffer()

    override fun contentType(): MediaType? = delegate.contentType()

    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource = limitedSource
}

private class LimitedSource(
    delegate: Source,
    private val kind: ResponseSizeKind,
    private val limitBytes: Long,
) : ForwardingSource(delegate) {
    private var totalBytesRead = 0L

    override fun read(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        if (byteCount == 0L) {
            return 0L
        }

        val remainingBytes = limitBytes - totalBytesRead
        val probeByteCount = minOf(byteCount, remainingBytes + 1L)
        val read = super.read(sink, probeByteCount)
        if (read == -1L) {
            return -1L
        }

        totalBytesRead += read
        if (totalBytesRead > limitBytes) {
            throw ResponseTooLargeException(
                kind = kind,
                limitBytes = limitBytes,
                declaredBytes = null,
            )
        }
        return read
    }
}
