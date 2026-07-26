package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers.Companion.headersOf
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureRedirectInterceptorTest {
    @Test
    fun `same-origin redirect preserves authorization`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = headersOf("Location", "/next.m3u"),
                ),
            )
            server.enqueue(MockResponse(body = "playlist"))

            val responseBody = client().newCall(
                Request.Builder()
                    .url(server.url("/start.m3u"))
                    .header("Authorization", "Bearer secret")
                    .tag(
                        SourceRequestContext::class,
                        SourceRequestContext(insecureHttpApproved = true),
                    )
                    .build(),
            ).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                response.body.string()
            }

            assertThat(responseBody).isEqualTo("playlist")
            assertThat(server.takeRequest().url.encodedPath).isEqualTo("/start.m3u")
            val redirected = server.takeRequest()
            assertThat(redirected.url.encodedPath).isEqualTo("/next.m3u")
            assertThat(redirected.headers["Authorization"]).isEqualTo("Bearer secret")
        }
    }

    @Test
    fun `cleartext approval does not authorize another redirect origin`() {
        MockWebServer().use { sourceServer ->
            MockWebServer().use { targetServer ->
                sourceServer.start()
                targetServer.start()
                sourceServer.enqueue(
                    MockResponse(
                        code = 302,
                        headers = headersOf("Location", targetServer.url("/next.m3u").toString()),
                    ),
                )

                val error = assertThrows(RedirectRejectedException::class.java) {
                    client().newCall(
                        Request.Builder()
                            .url(sourceServer.url("/start.m3u"))
                            .header("Authorization", "Bearer secret")
                            .header("Cookie", "session=secret")
                            .tag(
                                SourceRequestContext::class,
                                SourceRequestContext(insecureHttpApproved = true),
                            )
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

    @Test
    fun `unapproved insecure redirect is rejected before second request`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = headersOf("Location", "/next.m3u"),
                ),
            )

            val error = assertThrows(RedirectRejectedException::class.java) {
                client().newCall(
                    Request.Builder()
                        .url(server.url("/start.m3u"))
                        .tag(
                            SourceRequestContext::class,
                            SourceRequestContext(insecureHttpApproved = false),
                        )
                        .build(),
                ).execute()
            }

            assertThat(error.reason).isEqualTo(RedirectRejectionReason.InsecureTransportNotApproved)
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    private fun client(): OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(SecureRedirectInterceptor())
        .build()
}
