package app.muxtv.testing.http

import java.io.Closeable
import java.net.BindException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer

/**
 * Local HTTP origin with deterministic progressive-media Range semantics for playback evidence.
 *
 * The server serves one fixed byte payload (a media file) with optional `Range` support, request
 * counters, injected failures and per-request body delays. It is used by JVM tests and by device
 * instrumentation tests as a stand-in for a TorrServer-style byte-range HTTP origin:
 *
 * - range request: `GET` with a single first-byte-pos range (`bytes=start-end` or `bytes=start-`)
 *   answered with `206` + `Content-Range` + `Accept-Ranges: bytes` + `ETag`;
 * - no preflight: the server records every request, so tests can prove MuxTV never issues `HEAD`;
 * - failure injection: the N-th request can be answered with an arbitrary status code;
 * - stall injection: the N-th request body can be delayed before bytes are written.
 *
 * Range policy (explicit, deterministic):
 * - `bytes=start-end` with `start > end` or `start >= media size` is unsatisfiable -> `416` with
 *   a `Content-Range` header carrying the `bytes` unit and total size (counted as out-of-bounds);
 * - suffix-only (`bytes=-N`), multi-range (`bytes=a-b,c-d`) and malformed specs are not supported:
 *   the full body is answered with `200` and the request is counted as non-range (RFC 7233 allows
 *   ignoring an unsupported `Range` header);
 * - `Accept-Ranges`/`ETag` are representation headers: present on every response path (`HEAD`,
 *   `200`, `206`), `Accept-Ranges: none` when [Config.supportRanges] is false.
 *
 * Counters are thread-safe. Recorded requests are snapshots for header assertions. The payload
 * itself is the only state the server shares with clients; test locators never carry secrets.
 */
class RangeMediaServer private constructor(
    private val config: Config,
) : Closeable {
    private val requestCounter = AtomicInteger(0)
    private val headRequests = AtomicInteger(0)
    private val getRequests = AtomicInteger(0)
    private val rangeRequests = AtomicInteger(0)
    private val nonRangeRequests = AtomicInteger(0)
    private val outOfBoundsRequests = AtomicInteger(0)
    private val failureServed = AtomicInteger(0)
    private val recordedRequests: MutableList<RecordedRequest> =
        Collections.synchronizedList(ArrayList())

    private val dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val index = requestCounter.getAndIncrement()
            recordedRequests.add(request)
            config.failures[index]?.let { statusCode ->
                failureServed.incrementAndGet()
                return MockResponse.Builder().code(statusCode).build()
            }
            val bodyDelayMillis = config.requestDelaysMillis[index] ?: 0L
            return when (request.method) {
                "HEAD" -> {
                    headRequests.incrementAndGet()
                    headResponse()
                }

                "GET" -> {
                    getRequests.incrementAndGet()
                    val response = getResponse(request)
                    if (bodyDelayMillis > 0L) {
                        response.newBuilder()
                            .bodyDelay(bodyDelayMillis, TimeUnit.MILLISECONDS)
                            .build()
                    } else {
                        response
                    }
                }

                else -> MockResponse.Builder().code(405).build()
            }
        }
    }

    private var server: MockWebServer = newServer()
    private var boundPort: Int = -1

    private fun headResponse(): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", config.contentType)
        .addHeader("Content-Length", config.media.size.toString())
        .addHeader("Accept-Ranges", if (config.supportRanges) "bytes" else "none")
        .addHeader("ETag", ETAG)
        .build()

    private fun getResponse(request: RecordedRequest): MockResponse {
        if (!config.supportRanges) {
            nonRangeRequests.incrementAndGet()
            return fullBodyResponse()
        }
        val range = parseRange(request.headers["Range"])
        if (range == null) {
            nonRangeRequests.incrementAndGet()
            return fullBodyResponse()
        }
        val start = range.first
        val end = range.second
        if (start >= config.media.size || end < start) {
            outOfBoundsRequests.incrementAndGet()
            return MockResponse.Builder()
                .code(416)
                .addHeader("Content-Range", "bytes */${config.media.size}")
                .build()
        }
        rangeRequests.incrementAndGet()
        val clampedEnd = min(end, config.media.size - 1)
        val length = (clampedEnd - start + 1).toInt()
        return MockResponse.Builder()
            .code(206)
            .addHeader("Content-Type", config.contentType)
            .addHeader("Content-Range", "bytes $start-$clampedEnd/${config.media.size}")
            .addHeader("Content-Length", length.toString())
            .addHeader("Accept-Ranges", "bytes")
            .addHeader("ETag", ETAG)
            .body(Buffer().write(config.media, start, length))
            .build()
    }

    private fun fullBodyResponse(): MockResponse = MockResponse.Builder()
        .code(200)
        .addHeader("Content-Type", config.contentType)
        .addHeader("Content-Length", config.media.size.toString())
        .addHeader("Accept-Ranges", if (config.supportRanges) "bytes" else "none")
        .addHeader("ETag", ETAG)
        .body(Buffer().write(config.media))
        .build()

    /**
     * Parses a single first-byte-pos range spec (`bytes=start-end` / `bytes=start-`).
     * Returns null for missing/malformed headers and for unsupported forms (suffix-only,
     * multi-range, non-numeric bounds): those are served as full-body `200` per the documented
     * policy, because a client with an unsupported range still gets a playable response.
     */
    private fun parseRange(header: String?): Pair<Int, Int>? {
        if (header == null || !header.startsWith("bytes=")) return null
        val spec = header.removePrefix("bytes=").trim()
        if (spec.isEmpty() || spec.contains(',')) return null
        val separator = spec.indexOf('-')
        if (separator < 0) return null
        val startText = spec.substring(0, separator).trim()
        val endText = spec.substring(separator + 1).trim()
        if (startText.isEmpty()) return null
        val start = startText.toIntOrNull() ?: return null
        val end = when {
            endText.isEmpty() -> Int.MAX_VALUE
            else -> endText.toIntOrNull() ?: return null
        }
        return start to end
    }

    /**
     * Restarts the listener on the same port with the same config; counters keep accumulating.
     *
     * Android can transiently retain the listener port after MockWebServer shutdown. Retry only
     * that expected bind race for a short, bounded interval; all other startup failures remain
     * immediate so the fixture cannot hide a real server defect.
     */
    fun restartOnSamePort() {
        val port = boundPort
        check(port > 0) { "server was never started" }
        server.close()

        var lastBindFailure: BindException? = null
        repeat(REBIND_MAX_ATTEMPTS) { attempt ->
            val candidate = newServer()
            try {
                candidate.start(port)
                server = candidate
                return
            } catch (failure: BindException) {
                lastBindFailure = failure
                runCatching { candidate.close() }
                if (attempt < REBIND_MAX_ATTEMPTS - 1) {
                    Thread.sleep(REBIND_RETRY_DELAY_MILLIS)
                }
            }
        }
        throw checkNotNull(lastBindFailure)
    }

    private fun newServer(): MockWebServer = MockWebServer().also { it.dispatcher = dispatcher }

    fun baseUrl(): String = server.url("/").toString().removeSuffix("/")

    fun url(path: String): String = server.url(path).toString()

    fun port(): Int = boundPort

    fun requestCount(): Int = requestCounter.get()

    fun headRequestCount(): Int = headRequests.get()

    fun getRequestCount(): Int = getRequests.get()

    fun rangeRequestCount(): Int = rangeRequests.get()

    fun nonRangeRequestCount(): Int = nonRangeRequests.get()

    fun outOfBoundsRequestCount(): Int = outOfBoundsRequests.get()

    fun failureServedCount(): Int = failureServed.get()

    fun requests(): List<RecordedRequest> = synchronized(recordedRequests) {
        recordedRequests.toList()
    }

    override fun close() {
        server.close()
    }

    data class Config(
        val media: ByteArray,
        val contentType: String = "video/mp4",
        val supportRanges: Boolean = true,
        val failures: Map<Int, Int> = emptyMap(),
        val requestDelaysMillis: Map<Int, Long> = emptyMap(),
    )

    companion object {
        private const val ETAG = "\"muxtv-media-fixture\""
        private const val REBIND_MAX_ATTEMPTS = 40
        private const val REBIND_RETRY_DELAY_MILLIS = 50L

        fun start(config: Config): RangeMediaServer = RangeMediaServer(config).also {
            it.server.start()
            it.boundPort = it.server.port
        }
    }
}
