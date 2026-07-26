package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.Request
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackRedirectPolicyTest {
    @Test
    fun `unapproved http playback is rejected before first request`() {
        MockWebServer().use { server ->
            server.start()
            val rootUrl = server.url("/master.m3u8")
            val client = MuxTvHttpClients().playbackFor(
                rootUrl = rootUrl,
                insecureHttpApproved = false,
            )

            val error = assertThrows(PlaybackRequestRejectedException::class.java) {
                client.newCall(Request.Builder().url(rootUrl).build()).execute()
            }

            assertThat(error.reason)
                .isEqualTo(PlaybackRequestRejectionReason.InsecureTransportNotApproved)
            assertThat(server.requestCount).isEqualTo(0)
        }
    }

    @Test
    fun `approved http playback may follow same-origin redirect`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = headersOf("Location", "/segment.ts"),
                ),
            )
            server.enqueue(MockResponse(body = "segment"))
            val rootUrl = server.url("/master.m3u8")

            MuxTvHttpClients().playbackFor(
                rootUrl = rootUrl,
                insecureHttpApproved = true,
            ).newCall(
                Request.Builder()
                    .url(rootUrl)
                    .header("Authorization", "Bearer playback-secret")
                    .build(),
            ).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.string()).isEqualTo("segment")
            }

            server.takeRequest()
            val redirected = server.takeRequest(5, TimeUnit.SECONDS)
            assertThat(redirected).isNotNull()
            assertThat(redirected!!.url.encodedPath).isEqualTo("/segment.ts")
            assertThat(redirected.headers["Authorization"])
                .isEqualTo("Bearer playback-secret")
        }
    }

    @Test
    fun `http approval does not authorize a different redirect host`() {
        MockWebServer().use { sourceServer ->
            MockWebServer().use { targetServer ->
                sourceServer.start()
                targetServer.start()
                sourceServer.enqueue(
                    MockResponse(
                        code = 302,
                        headers = headersOf(
                            "Location",
                            targetServer.url("/segment.ts").toString(),
                        ),
                    ),
                )
                val rootUrl = sourceServer.url("/master.m3u8")

                val error = assertThrows(RedirectRejectedException::class.java) {
                    MuxTvHttpClients().playbackFor(
                        rootUrl = rootUrl,
                        insecureHttpApproved = true,
                    ).newCall(
                        Request.Builder()
                            .url(rootUrl)
                            .header("Authorization", "Bearer playback-secret")
                            .build(),
                    ).execute()
                }

                assertThat(error.reason)
                    .isEqualTo(RedirectRejectionReason.InsecureTransportNotApproved)
                assertThat(sourceServer.requestCount).isEqualTo(1)
                assertThat(targetServer.requestCount).isEqualTo(0)
            }
        }
    }
}
