package app.muxtv.testing.http

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class RangeMediaServerTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private fun media(size: Int = 100_000): ByteArray = ByteArray(size) { (it % 251).toByte() }

    @Test
    fun rangeRequestReturnsExactSliceWithContentRange() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=100-199")
                    .build(),
            ).execute()

            assertThat(response.code).isEqualTo(206)
            assertThat(response.header("Content-Range")).isEqualTo("bytes 100-199/100000")
            assertThat(response.header("Accept-Ranges")).isEqualTo("bytes")
            assertThat(response.body.bytes()).hasLength(100)
            assertThat(server.rangeRequestCount()).isEqualTo(1)
            assertThat(server.nonRangeRequestCount()).isEqualTo(0)
            assertThat(server.headRequestCount()).isEqualTo(0)
        }
    }

    @Test
    fun openEndedRangeIsServedToTheEndOfMedia() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=99900-")
                    .build(),
            ).execute()

            assertThat(response.code).isEqualTo(206)
            assertThat(response.header("Content-Range")).isEqualTo("bytes 99900-99999/100000")
            assertThat(response.body.bytes()).hasLength(100)
        }
    }

    @Test
    fun requestWithoutRangeReturnsFullBodyAndCountsAsNonRange() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val response = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).build(),
            ).execute()

            assertThat(response.code).isEqualTo(200)
            assertThat(response.body.bytes()).hasLength(100_000)
            assertThat(server.nonRangeRequestCount()).isEqualTo(1)
            assertThat(server.rangeRequestCount()).isEqualTo(0)
        }
    }

    @Test
    fun outOfBoundsRangeIsAnsweredWith416() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=100000-")
                    .build(),
            ).execute()

            assertThat(response.code).isEqualTo(416)
            assertThat(response.header("Content-Range")).isEqualTo("bytes */100000")
            assertThat(server.outOfBoundsRequestCount()).isEqualTo(1)
        }
    }

    @Test
    fun reversedRangeIsAnsweredWith416NotAnException() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=10-5")
                    .build(),
            ).execute()

            assertThat(response.code).isEqualTo(416)
            assertThat(response.header("Content-Range")).isEqualTo("bytes */100000")
            assertThat(server.outOfBoundsRequestCount()).isEqualTo(1)
            assertThat(server.rangeRequestCount()).isEqualTo(0)
        }
    }

    @Test
    fun multiRangeAndSuffixRangeFallBackToFullBody() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val multi = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=0-9,100-109")
                    .build(),
            ).execute()
            assertThat(multi.code).isEqualTo(200)
            assertThat(multi.body.bytes()).hasLength(100_000)

            val suffix = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=-500")
                    .build(),
            ).execute()
            assertThat(suffix.code).isEqualTo(200)
            assertThat(suffix.body.bytes()).hasLength(100_000)

            assertThat(server.nonRangeRequestCount()).isEqualTo(2)
            assertThat(server.rangeRequestCount()).isEqualTo(0)
        }
    }

    @Test
    fun headAdvertisesRangeSupportConsistentlyWithGet() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val supported = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).head().build(),
            ).execute()
            assertThat(supported.header("Accept-Ranges")).isEqualTo("bytes")
        }

        RangeMediaServer.start(
            RangeMediaServer.Config(media = media(), supportRanges = false),
        ).use { server ->
            val unsupported = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).head().build(),
            ).execute()
            assertThat(unsupported.header("Accept-Ranges")).isEqualTo("none")
        }
    }

    @Test
    fun etagIsConsistentAcrossHeadFullBodyAndRangeResponses() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val head = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).head().build(),
            ).execute()
            assertThat(head.header("ETag")).isEqualTo("\"muxtv-media-fixture\"")

            val full = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).build(),
            ).execute()
            assertThat(full.header("ETag")).isEqualTo("\"muxtv-media-fixture\"")

            val ranged = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=0-99")
                    .build(),
            ).execute()
            assertThat(ranged.header("ETag")).isEqualTo("\"muxtv-media-fixture\"")
        }
    }

    @Test
    fun headRequestsAreRecordedSeparately() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val response = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).head().build(),
            ).execute()

            assertThat(response.code).isEqualTo(200)
            assertThat(server.headRequestCount()).isEqualTo(1)
            assertThat(server.getRequestCount()).isEqualTo(0)
        }
    }

    @Test
    fun injectedFailureHitsOnlyTheConfiguredRequestIndex() {
        RangeMediaServer.start(
            RangeMediaServer.Config(media = media(), failures = mapOf(0 to 503)),
        ).use { server ->
            val failed = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).build(),
            ).execute()
            assertThat(failed.code).isEqualTo(503)

            val recovered = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).build(),
            ).execute()
            assertThat(recovered.code).isEqualTo(200)
            assertThat(server.failureServedCount()).isEqualTo(1)
            assertThat(server.requestCount()).isEqualTo(2)
        }
    }

    @Test
    fun configuredBodyDelaySlowsOnlyTheMatchingRequest() {
        RangeMediaServer.start(
            RangeMediaServer.Config(
                media = media(size = 20_000),
                requestDelaysMillis = mapOf(1 to 800L),
            ),
        ).use { server ->
            val firstStart = System.nanoTime()
            client.newCall(Request.Builder().url(server.url("/media.mp4")).build())
                .execute().use { it.body.bytes() }
            val firstElapsed = System.nanoTime() - firstStart

            val secondStart = System.nanoTime()
            client.newCall(Request.Builder().url(server.url("/media.mp4")).build())
                .execute().use { it.body.bytes() }
            val secondElapsed = System.nanoTime() - secondStart

            assertThat(secondElapsed).isAtLeast(TimeUnit.MILLISECONDS.toNanos(600))
            assertThat(firstElapsed).isLessThan(TimeUnit.MILLISECONDS.toNanos(600))
        }
    }

    @Test
    fun withoutRangeSupportFullBodyIsServedEvenForRangeRequests() {
        RangeMediaServer.start(
            RangeMediaServer.Config(media = media(), supportRanges = false),
        ).use { server ->
            val response = client.newCall(
                Request.Builder()
                    .url(server.url("/media.mp4"))
                    .header("Range", "bytes=0-99")
                    .build(),
            ).execute()

            assertThat(response.code).isEqualTo(200)
            assertThat(response.header("Accept-Ranges")).isEqualTo("none")
            assertThat(response.body.bytes()).hasLength(100_000)
            assertThat(server.nonRangeRequestCount()).isEqualTo(1)
            assertThat(server.rangeRequestCount()).isEqualTo(0)
        }
    }

    @Test
    fun restartOnSamePortKeepsServingAndAccumulatesCounters() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val port = server.port()
            val first = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).build(),
            ).execute()
            first.close()

            server.restartOnSamePort()
            assertThat(server.port()).isEqualTo(port)

            val second = client.newCall(
                Request.Builder().url(server.url("/media.mp4")).build(),
            ).execute()
            second.close()

            assertThat(second.code).isEqualTo(200)
            assertThat(server.requestCount()).isEqualTo(2)
        }
    }

    @Test
    fun restartAfterCloseKeepsServingTheSameOrigin() {
        RangeMediaServer.start(RangeMediaServer.Config(media = media())).use { server ->
            val locator = server.url("/media.mp4")
            client.newCall(Request.Builder().url(locator).build()).execute().close()

            server.close()
            server.restartOnSamePort()

            val recovered = client.newCall(Request.Builder().url(locator).build()).execute()
            recovered.close()
            assertThat(recovered.code).isEqualTo(200)
            assertThat(server.requestCount()).isEqualTo(2)
        }
    }
}
