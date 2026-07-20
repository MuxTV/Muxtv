package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.Request
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.Assert.assertThrows
import org.junit.Test

class ResponseSizeLimitInterceptorTest {
    @Test
    fun `declared decoded body larger than limit is rejected before consumption`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "123456"))

            val error = assertThrows(ResponseTooLargeException::class.java) {
                MuxTvHttpClients().source.newCall(
                    request(
                        url = server.url("/playlist.m3u").toString(),
                        limits = ResponseSizeLimits(
                            maxCompressedBytes = 100,
                            maxDecodedBytes = 5,
                        ),
                    ),
                ).execute()
            }

            assertThat(error.kind).isEqualTo(ResponseSizeKind.Decoded)
            assertThat(error.limitBytes).isEqualTo(5)
            assertThat(error.declaredBytes).isEqualTo(6)
        }
    }

    @Test
    fun `chunked decoded body is stopped while streaming past limit`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .chunkedBody("123456", maxChunkSize = 2)
                    .build(),
            )

            val error = assertThrows(ResponseTooLargeException::class.java) {
                MuxTvHttpClients().source.newCall(
                    request(
                        url = server.url("/playlist.m3u").toString(),
                        limits = ResponseSizeLimits(
                            maxCompressedBytes = 100,
                            maxDecodedBytes = 5,
                        ),
                    ),
                ).execute().use { response ->
                    response.body.string()
                }
            }

            assertThat(error.kind).isEqualTo(ResponseSizeKind.Decoded)
            assertThat(error.limitBytes).isEqualTo(5)
            assertThat(error.declaredBytes).isNull()
        }
    }

    @Test
    fun `compressed body is limited before transparent gzip decoding`() {
        MockWebServer().use { server ->
            server.start()
            val compressed = gzip("a".repeat(4_096))
            val compressedSize = compressed.size
            server.enqueue(
                MockResponse.Builder()
                    .headers(headersOf("Content-Encoding", "gzip"))
                    .body(compressed)
                    .build(),
            )

            val error = assertThrows(ResponseTooLargeException::class.java) {
                MuxTvHttpClients().source.newCall(
                    request(
                        url = server.url("/playlist.m3u").toString(),
                        limits = ResponseSizeLimits(
                            maxCompressedBytes = compressedSize - 1,
                            maxDecodedBytes = 8_192,
                        ),
                    ),
                ).execute()
            }

            assertThat(error.kind).isEqualTo(ResponseSizeKind.Compressed)
            assertThat(error.limitBytes).isEqualTo(compressedSize - 1)
            assertThat(error.declaredBytes).isEqualTo(compressedSize)
        }
    }

    private fun request(
        url: String,
        limits: ResponseSizeLimits,
    ): Request = Request.Builder()
        .url(url)
        .tag(
            SourceRequestContext::class,
            SourceRequestContext(
                insecureHttpApproved = true,
                responseSizeLimits = limits,
            ),
        )
        .build()

    private fun gzip(value: String): Buffer {
        val compressed = Buffer()
        GzipSink(compressed).buffer().use { sink ->
            sink.writeUtf8(value)
        }
        return compressed
    }
}
