package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackRequestPolicyInterceptorTest {
    @Test
    fun `same-origin media request preserves playback headers`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "segment"))

            val rootUrl = server.url("/master.m3u8")
            MuxTvHttpClients().playbackFor(
                rootUrl = rootUrl,
                insecureHttpApproved = true,
            ).newCall(
                playbackRequest(server.url("/segment.ts").toString()),
            ).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.string()).isEqualTo("segment")
            }

            val recorded = server.takeRequest()
            assertThat(recorded.headers["Authorization"]).isEqualTo("Bearer media-secret")
            assertThat(recorded.headers["Cookie"]).isEqualTo("session=media-secret")
            assertThat(recorded.headers["Referer"]).isEqualTo("https://portal.example/channel")
            assertThat(recorded.headers["X-Api-Key"]).isEqualTo("media-secret")
            assertThat(recorded.headers["User-Agent"]).isEqualTo("MuxTV-Playback/1")
        }
    }

    @Test
    fun `http approval does not authorize a direct subresource on another host`() {
        MockWebServer().use { rootServer ->
            MockWebServer().use { mediaServer ->
                rootServer.start()
                mediaServer.start()
                val rootUrl = rootServer.url("/master.m3u8")

                val error = assertThrows(PlaybackRequestRejectedException::class.java) {
                    MuxTvHttpClients().playbackFor(
                        rootUrl = rootUrl,
                        insecureHttpApproved = true,
                    ).newCall(
                        playbackRequest(mediaServer.url("/segment.ts").toString()),
                    ).execute()
                }

                assertThat(error.reason)
                    .isEqualTo(PlaybackRequestRejectionReason.InsecureTransportNotApproved)
                assertThat(mediaServer.requestCount).isEqualTo(0)
            }
        }
    }

    @Test
    fun `direct cross-origin https request strips sensitive headers`() {
        var capturedRequest: Request? = null
        val rootUrl = "https://provider.example/master.m3u8".toHttpUrl()
        val client = OkHttpClient.Builder()
            .addInterceptor(
                PlaybackRequestPolicyInterceptor(
                    rootUrl = rootUrl,
                    insecureHttpApproved = false,
                ),
            )
            .addInterceptor { chain ->
                capturedRequest = chain.request()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("segment".toResponseBody())
                    .build()
            }
            .build()

        client.newCall(playbackRequest("https://cdn.example/segment.ts"))
            .execute()
            .close()

        val captured = checkNotNull(capturedRequest)
        assertThat(captured.headers["Authorization"]).isNull()
        assertThat(captured.headers["Cookie"]).isNull()
        assertThat(captured.headers["Referer"]).isNull()
        assertThat(captured.headers["X-Api-Key"]).isNull()
        assertThat(captured.headers["User-Agent"]).isEqualTo("MuxTV-Playback/1")
    }

    @Test
    fun `https playback root rejects direct http subresource without exposing query`() {
        MockWebServer().use { mediaServer ->
            mediaServer.start()
            val targetUrl = mediaServer.url("/segment.ts?token=direct-secret")
            val client = MuxTvHttpClients().playbackFor(
                rootUrl = "https://provider.example/master.m3u8".toHttpUrl(),
                insecureHttpApproved = false,
            )

            val error = assertThrows(PlaybackRequestRejectedException::class.java) {
                client.newCall(playbackRequest(targetUrl.toString())).execute()
            }

            assertThat(error.reason).isEqualTo(PlaybackRequestRejectionReason.HttpsDowngrade)
            assertThat(error.message).doesNotContain("direct-secret")
            assertThat(mediaServer.requestCount).isEqualTo(0)
        }
    }

    private fun playbackRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer media-secret")
        .header("Cookie", "session=media-secret")
        .header("Referer", "https://portal.example/channel")
        .header("X-Api-Key", "media-secret")
        .header("User-Agent", "MuxTV-Playback/1")
        .build()
}
