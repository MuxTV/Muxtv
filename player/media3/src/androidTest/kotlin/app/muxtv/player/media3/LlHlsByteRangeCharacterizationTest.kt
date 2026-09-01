package app.muxtv.player.media3

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.network.MuxTvHttpClients
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
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
 * Characterization evidence for androidx/media#3350 through MuxTV's production HLS construction.
 *
 * The origin is fully local and contains no provider data. The init fragment and bounded media
 * bytes come from AndroidX Media3's Apache-2.0 CMAF test corpus (`audio_init.mp4` and the first
 * 512 bytes of `audio_2.m4s`). The playlist exposes those 512 bytes as a trailing LL-HLS part.
 *
 * On Media3 versions affected by #3350, FragmentedMp4Extractor consumes the complete bounded
 * DataSpec and asks for more input. HlsMediaChunk stores nextLoadPosition == DataSpec.length and
 * retries the same chunk. The retry calls DataSpec.subrange(length), which attempts to create an
 * illegal zero-length DataSpec and surfaces an unexpected IllegalArgumentException before another
 * HTTP open. This test locks that existing upstream failure signature; it is not a workaround.
 */
@RunWith(AndroidJUnit4::class)
@AndroidXOptIn(UnstableApi::class)
class LlHlsByteRangeCharacterizationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun byteRangeLlHlsPart_retriesIntoIllegalZeroLengthSubrange() {
        LlHlsOrigin.start().use { origin ->
            PlayerHarness(context).use { harness ->
                harness.post {
                    val request = PlaybackSessionRequest(
                        profileId = "profile-ll-hls-evidence",
                        mediaId = "channel-ll-hls-evidence",
                        variantId = "variant-ll-hls-evidence",
                        locator = origin.playlistUrl(),
                        insecureHttpApproved = true,
                        mimeType = "application/x-mpegURL",
                    )
                    harness.player.setMediaSource(
                        PlaybackMediaSourceFactory(context, MuxTvHttpClients()).create(request),
                    )
                    harness.player.prepare()
                    harness.player.play()
                }

                val error = harness.awaitPlayerError(ERROR_TIMEOUT_SECONDS) {
                    "requests=${origin.requests()}"
                }
                val causes = causeChain(error)
                val illegalArgument = causes.filterIsInstance<IllegalArgumentException>()
                    .firstOrNull()

                assertThat(illegalArgument).isNotNull()
                val stack = checkNotNull(illegalArgument).stackTrace
                assertThat(
                    stack.any {
                        it.className == "androidx.media3.datasource.DataSpec" &&
                            it.methodName == "subrange"
                    },
                ).isTrue()
                assertThat(
                    stack.any {
                        it.className == "androidx.media3.exoplayer.hls.HlsMediaChunk" &&
                            it.methodName == "feedDataToExtractor"
                    },
                ).isTrue()

                val partRequests = origin.requests().filter { it.contains("GET /audio_2.m4s") }
                assertThat(partRequests).containsExactly("GET /audio_2.m4s range=bytes=0-511")
            }
        }
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var current: Throwable? = error
        while (current != null && result.size < MAX_CAUSE_DEPTH) {
            result += current
            current = current.cause
        }
        return result
    }

    private class PlayerHarness(context: Context) : Closeable {
        private val thread = HandlerThread("ll-hls-characterization").apply { start() }
        private val handler = Handler(thread.looper)
        private val playerError = AtomicReference<PlaybackException?>()
        val player: ExoPlayer

        init {
            val playerRef = AtomicReference<ExoPlayer>()
            post {
                playerRef.set(
                    ExoPlayer.Builder(context)
                        .setLooper(thread.looper)
                        .build()
                        .also { exoPlayer ->
                            exoPlayer.addListener(
                                object : Player.Listener {
                                    override fun onPlayerError(error: PlaybackException) {
                                        playerError.set(error)
                                    }
                                },
                            )
                        },
                )
            }
            player = checkNotNull(playerRef.get())
        }

        fun post(block: () -> Unit) {
            val latch = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            handler.post {
                try {
                    block()
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                } finally {
                    latch.countDown()
                }
            }
            check(latch.await(OPERATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "player-thread operation timed out"
            }
            failure.get()?.let { throw it }
        }

        fun awaitPlayerError(
            timeoutSeconds: Long,
            diagnostics: () -> String,
        ): PlaybackException {
            val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (System.nanoTime() < deadlineNanos) {
                playerError.get()?.let { return it }
                Thread.sleep(POLL_INTERVAL_MILLIS)
            }
            throw AssertionError(
                "LL-HLS fixture did not produce a player error within the deadline; ${diagnostics()}",
            )
        }

        override fun close() {
            post { player.release() }
            thread.quitSafely()
        }
    }

    private class LlHlsOrigin private constructor() : Closeable {
        private val requests = Collections.synchronizedList(mutableListOf<String>())
        private val initSegment = Base64.decode(INIT_SEGMENT_BASE64, Base64.DEFAULT)
        private val boundedMediaPart = Base64.decode(BOUNDED_MEDIA_PART_BASE64, Base64.DEFAULT)
        private val server = MockWebServer().also { mockServer ->
            mockServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requests += "${request.method} $path range=${request.headers["Range"] ?: "<none>"}"
                    return when (path) {
                        "/fixture.m3u8" -> textResponse(PLAYLIST_CONTENT_TYPE, PLAYLIST)
                        "/audio_init.mp4" -> bytesResponse(INIT_CONTENT_TYPE, initSegment)
                        "/audio_2.m4s" -> mediaPartResponse(request)
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
        }

        private fun mediaPartResponse(request: RecordedRequest): MockResponse {
            val range = request.headers["Range"]
            if (range != EXPECTED_MEDIA_RANGE) {
                return MockResponse.Builder()
                    .code(416)
                    .addHeader("Content-Range", "bytes */$MEDIA_RESOURCE_SIZE")
                    .build()
            }
            check(boundedMediaPart.size == BOUNDED_MEDIA_PART_SIZE)
            return MockResponse.Builder()
                .code(206)
                .addHeader("Content-Type", PART_CONTENT_TYPE)
                .addHeader("Accept-Ranges", "bytes")
                .addHeader(
                    "Content-Range",
                    "bytes 0-${BOUNDED_MEDIA_PART_SIZE - 1}/$MEDIA_RESOURCE_SIZE",
                )
                .addHeader("Content-Length", boundedMediaPart.size.toString())
                .body(Buffer().write(boundedMediaPart))
                .build()
        }

        fun playlistUrl(): String = server.url("/fixture.m3u8").toString()

        fun requests(): List<String> = synchronized(requests) { requests.toList() }

        override fun close() {
            server.close()
        }

        companion object {
            fun start(): LlHlsOrigin = LlHlsOrigin().also { it.server.start() }

            private const val PLAYLIST_CONTENT_TYPE = "application/vnd.apple.mpegurl"
            private const val INIT_CONTENT_TYPE = "audio/mp4"
            private const val PART_CONTENT_TYPE = "audio/mp4"
            private const val BOUNDED_MEDIA_PART_SIZE = 512
            private const val MEDIA_RESOURCE_SIZE = 3811
            private const val EXPECTED_MEDIA_RANGE = "bytes=0-511"

            private val PLAYLIST = """
                #EXTM3U
                #EXT-X-VERSION:9
                #EXT-X-TARGETDURATION:1
                #EXT-X-MEDIA-SEQUENCE:1
                #EXT-X-MAP:URI="audio_init.mp4"
                #EXT-X-PART-INF:PART-TARGET=0.255420
                #EXT-X-PART:URI="audio_2.m4s",DURATION=0.255420,INDEPENDENT=YES,BYTERANGE="512@0"
            """.trimIndent() + "\n"

            // Exact androidx/media 1.10.1 libraries/test_data CMAF audio_init.mp4, blob
            // 4d0cbdc5b0298a19f8eca80a5503e8d14b7c25c6.
            private val INIT_SEGMENT_BASE64 = """
                AAAAJGZ0eXBtcDQxAAAAAGlzbzhpc29tbXA0MWRhc2hjbWZjAAADHW1vb3YA
                AABsbXZoZAAAAADljXm75Y15uwAArEQAAAAAAAEAAAEAAAAAAAAAAAAAAAAB
                AAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAAAAAAA
                AAAAAAAAAAAAAAAAAAAAAAIAAACUbWV0YQAAAAAAAAAgaGRscgAAAAAAAAAA
                SUQzMgAAAAAAAAAAAAAAAAAAAGhJRDMyAAAAABXHSUQzBAAAAAAAUFBSSVYA
                AABGAABodHRwczovL2dpdGh1Yi5jb20vc2hha2EtcHJvamVjdC9zaGFrYS1w
                YWNrYWdlcgB2My40LjItYzgxOWRlYS1yZWxlYXNlAAAB3XRyYWsAAABcdGto
                ZAAAAAfljXm75Y15uwAAAAEAAAAAAAAAAAAAAAAAAAAAAAAAAAEAAAAAAQAA
                AAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAEAAAAAAAAAAAAAAAAAAAVVt
                ZGlhAAAAIG1kaGQAAAAA5Y15u+WNebsAAKxEAAAAAFXEAAAAAAAtaGRscgAA
                AAAAAAAAc291bgAAAAAAAAAAAAAAAFNvdW5kSGFuZGxlcgAAAAEAbWluZgAA
                ACRkaW5mAAAAHGRyZWYAAAAAAAAAAQAAAAx1cmwgAAAAAQAAAMRzdGJsAAAA
                XnN0c2QAAAAAAAAAAQAAAE5tcDRhAAAAAAAAAAEAAAAAAAAAAAABABAAAAAA
                rEQAAAAAACplc2RzAAAAAAMcAAAABBRAFQAAAAAB9AAAAeawBQUSCFblAAYB
                AgAAABBzdHRzAAAAAAAAAAAAAAAQc3RzYwAAAAAAAAAAAAAAFHN0c3oAAAAA
                AAAAAAAAAAAAAAAQc3RjbwAAAAAAAAAAAAAAGnNncGQBAAAAcm9sbAAAAAIA
                AAAB//8AAAAQc21oZAAAAAAAAAAAAAAAJGVkdHMAAAAcZWxzdAAAAAAAAAAB
                AAAAAAAABAAAAQAAAAAAOG12ZXgAAAAQbWVoZAAAAAAAAgjMAAAAIHRyZXgA
                AAAAAAAAAQAAAAEAAAQAAAAAAAAAAAA=
            """.trimIndent()

            // First 512 bytes of androidx/media 1.10.1 CMAF audio_2.m4s, blob
            // 40111ff3c411240cd3ada1a7478f58d164a2011d. Decodes to exactly 512 bytes.
            private val BOUNDED_MEDIA_PART_BASE64 = """
                AAAAJHN0eXBtcDQxAAAAAGlzbzhpc29tbXA0MWRhc2hjbWZzAAAALHNpZHgAAAAAAAAAAQAArEQA
                ACwAAAAAAAAAAAEAAA6TAAAsAJAAAAAAAACobW9vZgAAABBtZmhkAAAAAAAAAAIAAACQdHJhZgAA
                ABx0ZmhkAAIAKgAAAAEAAAABAAAEAAAAAAAAAAAQdGZkdAAAAAAAADAAAAAAQHRydW4AAAIBAAAA
                CwAAALAAAAFhAAABKgAAAVcAAAFPAAABLgAAAU4AAAE8AAABOgAAATgAAAE9AAABSwAAABxzYmdw
                AAAAAHJvbGwAAAABAAAACwAAAAEAAA3rbWRhdAD0OK6tQkKrP/w8f/prWs9p7e06/p7/47+euePX
                ndSib1mudV4sqpA1NK0srSqXqN0kQkoivqShNM+38/Y2fsbSC4lVaVX9IMbOGHOGMkuqrlxJsEij
                y74tgIsfFllniXzFvVJXDnZhEsFxMEWHTUbFEBYg5sH6LvTiDsTHE8dNl+bJTDydSVSRs97Wnq6t
                6FlusoGtt/ZWm7OvRX3TCJgxIBMfCos5yv8svil7031CEHyRWHwxdWzsUa5SNWNYE4UWpGm44qMQ
                AwQ8VkErK2r1gFSQCw0X0OSvCo5pkMVo6rea0I8ixO70HdRWZApG9pwgShQ0ErDicqwRagrIjcI=
            """.trimIndent()

            private fun textResponse(contentType: String, body: String): MockResponse =
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", contentType)
                    .body(Buffer().writeUtf8(body))
                    .build()

            private fun bytesResponse(contentType: String, body: ByteArray): MockResponse =
                MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", contentType)
                    .addHeader("Content-Length", body.size.toString())
                    .addHeader("Accept-Ranges", "bytes")
                    .body(Buffer().write(body))
                    .build()
        }
    }

    private companion object {
        const val ERROR_TIMEOUT_SECONDS = 20L
        const val OPERATION_TIMEOUT_SECONDS = 30L
        const val POLL_INTERVAL_MILLIS = 50L
        const val MAX_CAUSE_DEPTH = 16
    }
}
