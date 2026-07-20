package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
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
    fun `cross-origin redirect strips sensitive headers`() {
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
                targetServer.enqueue(MockResponse(body = "playlist"))

                client().newCall(
                    Request.Builder()
                        .url(sourceServer.url("/start.m3u"))
                        .header("Authorization", "Bearer secret")
                        .header("Cookie", "session=secret")
                        .header("Referer", "http://provider.example/setup")
                        .header("Origin", "http://provider.example")
                        .header("X-Api-Key", "api-secret")
                        .header("User-Agent", "MuxTV/1")
                        .tag(
                            SourceRequestContext::class,
                            SourceRequestContext(insecureHttpApproved = true),
                        )
                        .build(),
                ).execute().use { response ->
                    assertThat(response.code).isEqualTo(200)
                    assertThat(response.body.string()).isEqualTo("playlist")
                }

                sourceServer.takeRequest()
                val redirected = targetServer.takeRequest(5, TimeUnit.SECONDS)
                assertThat(redirected).isNotNull()
                assertThat(redirected!!.headers["Authorization"]).isNull()
                assertThat(redirected.headers["Cookie"]).isNull()
                assertThat(redirected.headers["Referer"]).isNull()
                assertThat(redirected.headers["Origin"]).isNull()
                assertThat(redirected.headers["X-Api-Key"]).isNull()
                assertThat(redirected.headers["User-Agent"]).isEqualTo("MuxTV/1")
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
