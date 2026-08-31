package app.muxtv.player.media3

import android.content.Context
import android.util.Base64
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.network.MuxTvHttpClients
import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression evidence for androidx/media#3350 through MuxTV's real HLS construction path.
 *
 * The corpus is local and deterministic. The init fragment and the start of the media fragment are
 * derived from AndroidX Media3's Apache-2.0 CMAF test data (`audio_init.mp4` / `audio_2.m4s`). The
 * media fragment is intentionally truncated at 512 bytes and advertised as a bounded LL-HLS part.
 * That makes FragmentedMp4Extractor consume the complete DataSpec and ask for more input: buggy
 * Media3 retries the same exhausted HlsMediaChunk and reaches `DataSpec.subrange(length)` with a
 * zero-length result, surfacing a fatal unexpected IllegalArgumentException.
 *
 * The fixed upstream behavior classifies the fully-consumed EOF as ParserException instead of
 * re-entering the exhausted byte range. No live provider URL or credential is required.
 */
@RunWith(AndroidJUnit4::class)
@AndroidXOptIn(UnstableApi::class)
class LlHlsByteRangeRegressionTest {
    @Test
    fun exhaustedByteRangePart_doesNotSurfaceUnexpectedIllegalArgumentException() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()

        LlHlsOrigin().use { origin ->
            val playbackError = AtomicReference<PlaybackException?>()
            val errorObserved = CountDownLatch(1)
            val playerRef = AtomicReference<ExoPlayer?>()

            instrumentation.runOnMainSync {
                val player = ExoPlayer.Builder(context).build()
                player.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            playbackError.set(error)
                            errorObserved.countDown()
                        }
                    },
                )
                val request = PlaybackSessionRequest(
                    profileId = "profile-ll-hls",
                    mediaId = "media-ll-hls",
                    variantId = "variant-ll-hls",
                    locator = origin.url("/live.m3u8"),
                    insecureHttpApproved = true,
                    mimeType = "application/x-mpegURL",
                )
                player.setMediaSource(
                    PlaybackMediaSourceFactory(context, MuxTvHttpClients()).create(request),
                )
                player.prepare()
                playerRef.set(player)
            }

            try {
                val observed = errorObserved.await(ERROR_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertThat(observed)
                    .named("playback error observed; requests=${origin.requests()}")
                    .isTrue()

                val error = checkNotNull(playbackError.get())
                assertThat(origin.requests().any { request ->
                    request.contains("GET /audio_2.m4s") && request.contains("bytes=0-511")
                }).named("bounded LL-HLS part request; requests=${origin.requests()}").isTrue()

                assertThat(findCause<IllegalArgumentException>(error))
                    .named("unexpected loader failure: ${error.stackTraceToString()}")
                    .isNull()
                assertThat(findCause<ParserException>(error))
                    .named("fully-consumed bounded chunk classification")
                    .isNotNull()
            } finally {
                instrumentation.runOnMainSync {
                    playerRef.getAndSet(null)?.release()
                }
            }
        }
    }

    private inline fun <reified T : Throwable> findCause(error: Throwable): T? {
        var current: Throwable? = error
        while (current != null) {
            if (current is T) return current
            current = current.cause
        }
        return null
    }

    private class LlHlsOrigin : AutoCloseable {
        private val server = MockWebServer()
        private val requests = Collections.synchronizedList(mutableListOf<String>())

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requests += "${request.method} $path range=${request.headers["Range"] ?: "<none>"}"
                    return when (path) {
                        "/live.m3u8" -> playlistResponse()
                        "/audio_init.mp4" -> bytesResponse(request, INIT_FRAGMENT, "audio/mp4")
                        "/audio_2.m4s" -> bytesResponse(request, TRUNCATED_MEDIA_PART, "audio/mp4")
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
            server.start()
        }

        fun url(path: String): String = server.url(path).toString()

        fun requests(): List<String> = synchronized(requests) { requests.toList() }

        override fun close() {
            server.close()
        }

        private fun playlistResponse(): MockResponse = MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/vnd.apple.mpegurl")
            .body(Buffer().writeUtf8(PLAYLIST))
            .build()

        private fun bytesResponse(
            request: RecordedRequest,
            bytes: ByteArray,
            contentType: String,
        ): MockResponse {
            val range = request.headers["Range"]
            if (range == null) {
                return MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", contentType)
                    .addHeader("Content-Length", bytes.size.toString())
                    .addHeader("Accept-Ranges", "bytes")
                    .body(Buffer().write(bytes))
                    .build()
            }

            val match = RANGE.matchEntire(range)
                ?: return MockResponse.Builder().code(400).build()
            val start = match.groupValues[1].toInt()
            val requestedEnd = match.groupValues[2].takeIf(String::isNotEmpty)?.toInt()
                ?: bytes.lastIndex
            if (start > bytes.lastIndex || requestedEnd < start) {
                return MockResponse.Builder()
                    .code(416)
                    .addHeader("Content-Range", "bytes */${bytes.size}")
                    .build()
            }

            val end = minOf(requestedEnd, bytes.lastIndex)
            val length = end - start + 1
            return MockResponse.Builder()
                .code(206)
                .addHeader("Content-Type", contentType)
                .addHeader("Content-Range", "bytes $start-$end/${bytes.size}")
                .addHeader("Content-Length", length.toString())
                .addHeader("Accept-Ranges", "bytes")
                .body(Buffer().write(bytes, start, length))
                .build()
        }
    }

    private companion object {
        const val ERROR_TIMEOUT_SECONDS = 20L
        val RANGE = Regex("bytes=(\\d+)-(\\d*)")

        val PLAYLIST = """
            #EXTM3U
            #EXT-X-VERSION:9
            #EXT-X-TARGETDURATION:1
            #EXT-X-MEDIA-SEQUENCE:1
            #EXT-X-MAP:URI="audio_init.mp4"
            #EXT-X-PART-INF:PART-TARGET=0.255420
            #EXT-X-PART:URI="audio_2.m4s",DURATION=0.255420,INDEPENDENT=YES,BYTERANGE="512@0"
        """.trimIndent() + "\n"

        val INIT_FRAGMENT = decodeFixture(
            """
            AAAAJGZ0eXBtcDQxAAAAAGlzbzhpc29tbXA0MWRhc2hjbWZjAAADHW1vb3YAAABsbXZoZAAAAADl
            jXm75Y15uwAArEQAAAAAAAEAAAEAAAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAA
            AAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAIAAACUbWV0YQAAAAAAAAAgaGRs
            cgAAAAAAAAAASUQzMgAAAAAAAAAAAAAAAAAAAGhJRDMyAAAAABXHSUQzBAAAAAAAUFBSSVYAAABG
            AABodHRwczovL2dpdGh1Yi5jb20vc2hha2EtcHJvamVjdC9zaGFrYS1wYWNrYWdlcgB2My40LjIt
            YzgxOWRlYS1yZWxlYXNlAAAB3XRyYWsAAABcdGtoZAAAAAfljXm75Y15uwAAAAEAAAAAAAAAAAAA
            AAAAAAAAAAAAAAEAAAAAAQAAAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAA
            AAAAAVVtZGlhAAAAIG1kaGQAAAAA5Y15u+WNebsAAKxEAAAAAFXEAAAAAAAtaGRscgAAAAAAAAAA
            c291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAAAAEAbWluZgAAACRkaW5mAAAAHGRyZWYAAAAA
            AAAAAQAAAAx1cmwgAAAAAQAAAMRzdGJsAAAAXnN0c2QAAAAAAAAAAQAAAE5tcDRhAAAAAAAAAAEA
            AAAAAAAAAAABABAAAAAArEQAAAAAACplc2RzAAAAAAMcAAAABBRAFQAAAAAB9AAAAeawBQUSCFbl
            AAYBAgAAABBzdHRzAAAAAAAAAAAAAAAQc3RzYwAAAAAAAAAAAAAAFHN0c3oAAAAAAAAAAAAAAAAA
            AAAQc3RjbwAAAAAAAAAAAAAAGnNncGQBAAAAcm9sbAAAAAIAAAAB//8AAAAQc21oZAAAAAAAAAAA
            AAAAJGVkdHMAAAAcZWxzdAAAAAAAAAABAAAAAAAABAAAAQAAAAAAOG12ZXgAAAAQbWVoZAAAAAAA
            AgjMAAAAIHRyZXgAAAAAAAAAAQAAAAEAAAQAAAAAAAAAAAA=
            """,
        )

        val TRUNCATED_MEDIA_PART = decodeFixture(
            """
            AAAAJHN0eXBtcDQxAAAAAGlzbzhpc29tbXA0MWRhc2hjbWZzAAAALHNpZHgAAAAAAAAAAQAArEQA
            ACwAAAAAAAAAAAEAAA6TAAAsAJAAAAAAAACobW9vZgAAABBtZmhkAAAAAAAAAAIAAACQdHJhZgAA
            ABx0ZmhkAAIAKgAAAAEAAAABAAAEAAAAAAAAAAAQdGZkdAAAAAAAADAAAAAAQHRydW4AAAIBAAAA
            CwAAALAAAAFhAAABKgAAAVcAAAFPAAABLgAAAU4AAAE8AAABOgAAATgAAAE9AAABSwAAABxzYmdw
            AAAAAHJvbGwAAAABAAAACwAAAAEAAA3rbWRhdAD0OK6tQkKrP/w8f/prWs9p7e06/p7/47+euePX
            ndSib1mudV4sqpA1NK0srSqXqN0kQkoivqShNM+38/Y2fsbSC4lVaVX9IMbOGHOGMkuqrlxJsEij
            y74tgIsfFllniXzFvVJXDnZhEsFxMEWHTUbFEBYg5sH6LvTiDsTHE8dNl+bJTDydSVSRs97Wnq6t
            6FlusoGtt/ZWm7OvRX3TCJgxIBMfCos5yv8svil7031CEHyRWHwxdWzsUa5SNWNYE4UWpGm44qMQ
            AwQ8VkErK2r1gFSQCw0X0OSvCo5pkMVo6rea0I8ixO70HdRWZApG9pwgShQ0ErDicqwRagrIjcI=
            """,
        )

        fun decodeFixture(value: String): ByteArray =
            Base64.decode(value.trimIndent(), Base64.DEFAULT)
    }
}
