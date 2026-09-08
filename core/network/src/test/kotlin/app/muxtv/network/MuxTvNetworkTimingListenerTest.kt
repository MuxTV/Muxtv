package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertThrows
import org.junit.Test

class MuxTvNetworkTimingListenerTest {
    @Test
    fun `source success emits one bounded complete timing set`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "playlist"))
            val observations = CopyOnWriteArrayList<MuxTvNetworkTimingObservation>()
            val clients = MuxTvHttpClients(
                sourceTimingSink = MuxTvNetworkTimingSink { observation ->
                    observations += observation
                },
            )

            clients.source.newCall(
                Request.Builder()
                    .url(server.url("/playlist.m3u?token=super-secret-token"))
                    .header("Authorization", "Bearer super-secret-authorization")
                    .header("Cookie", "session=super-secret-cookie")
                    .header("X-Provider-Token", "super-secret-provider-token")
                    .build(),
            ).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.string()).isEqualTo("playlist")
            }

            assertCompleteSet(
                observations = observations,
                outcome = MuxTvNetworkOutcome.SUCCEEDED,
            )
            assertThat(observations.joinToString()).doesNotContain("super-secret")
        }
    }

    @Test
    fun `source redirect remains one bounded timing set`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse(
                    code = 302,
                    headers = headersOf("Location", "/next.m3u"),
                ),
            )
            server.enqueue(MockResponse(body = "playlist"))
            val observations = CopyOnWriteArrayList<MuxTvNetworkTimingObservation>()
            val clients = MuxTvHttpClients(
                sourceTimingSink = MuxTvNetworkTimingSink { observation ->
                    observations += observation
                },
            )

            clients.source.newCall(
                Request.Builder()
                    .url(server.url("/redirect.m3u"))
                    .build(),
            ).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.string()).isEqualTo("playlist")
            }

            assertCompleteSet(
                observations = observations,
                outcome = MuxTvNetworkOutcome.SUCCEEDED,
            )
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    @Test
    fun `source failure emits one bounded failed timing set`() {
        val server = MockWebServer()
        server.start()
        val url = server.url("/unreachable.m3u")
        server.close()
        val observations = CopyOnWriteArrayList<MuxTvNetworkTimingObservation>()
        val clients = MuxTvHttpClients(
            sourceTimingSink = MuxTvNetworkTimingSink { observation ->
                observations += observation
            },
        )

        assertThrows(IOException::class.java) {
            clients.source.newCall(
                Request.Builder()
                    .url(url)
                    .build(),
            ).execute().use { response ->
                response.body.string()
            }
        }

        assertCompleteSet(
            observations = observations,
            outcome = MuxTvNetworkOutcome.FAILED,
            requireResponseBody = false,
        )
    }

    @Test
    fun `throwing timing sink cannot replace successful http result`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "playlist"))
            val clients = MuxTvHttpClients(
                sourceTimingSink = MuxTvNetworkTimingSink {
                    throw IllegalStateException("test sink failure")
                },
            )

            clients.source.newCall(
                Request.Builder()
                    .url(server.url("/playlist.m3u"))
                    .build(),
            ).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.string()).isEqualTo("playlist")
            }
        }
    }

    @Test
    fun `playback client stays uninstrumented by source timing sink`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "segment"))
            val observations = CopyOnWriteArrayList<MuxTvNetworkTimingObservation>()
            val clients = MuxTvHttpClients(
                sourceTimingSink = MuxTvNetworkTimingSink { observation ->
                    observations += observation
                },
            )

            clients.playback.newCall(
                Request.Builder()
                    .url(server.url("/segment.ts"))
                    .build(),
            ).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.string()).isEqualTo("segment")
            }

            assertThat(observations).isEmpty()
        }
    }

    private fun assertCompleteSet(
        observations: List<MuxTvNetworkTimingObservation>,
        outcome: MuxTvNetworkOutcome,
        requireResponseBody: Boolean = true,
    ) {
        assertThat(observations).hasSize(MuxTvNetworkPhase.entries.size)
        assertThat(observations.map { it.phase })
            .containsExactlyElementsIn(MuxTvNetworkPhase.entries)
            .inOrder()
        assertThat(observations.map { it.clientKind }.distinct())
            .containsExactly(MuxTvNetworkClientKind.SOURCE)
        assertThat(observations.map { it.outcome }.distinct())
            .containsExactly(outcome)
        observations.mapNotNull { it.durationNanos }.forEach { durationNanos ->
            assertThat(durationNanos).isAtLeast(0L)
        }
        assertThat(observations.single { it.phase == MuxTvNetworkPhase.TOTAL }.durationNanos)
            .isNotNull()
        if (requireResponseBody) {
            val connectionAcquireDuration = requireNotNull(
                observations.single { it.phase == MuxTvNetworkPhase.CONNECTION_ACQUIRE }.durationNanos,
            )
            assertThat(connectionAcquireDuration).isGreaterThan(0L)
            assertThat(observations.single { it.phase == MuxTvNetworkPhase.REQUEST }.durationNanos)
                .isNotNull()
            assertThat(observations.single { it.phase == MuxTvNetworkPhase.TTFB }.durationNanos)
                .isNotNull()
            assertThat(observations.single { it.phase == MuxTvNetworkPhase.RESPONSE_BODY }.durationNanos)
                .isNotNull()
        }
    }
}
