package app.muxtv.testing.http

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test

class RangeMediaServerDisconnectTest {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Test
    fun disconnectDuringResponseBodyKeepsOriginReusableAfterRestore() {
        val media = ByteArray(100_000) { (it % 251).toByte() }
        RangeMediaServer.start(RangeMediaServer.Config(media = media)).use { server ->
            val locator = server.url("/media.mp4")
            val port = server.port()

            server.disconnectNextResponses(1)
            val failedRead = runCatching {
                client.newCall(Request.Builder().url(locator).build())
                    .execute()
                    .use { response -> response.body.bytes() }
            }

            assertThat(failedRead.isFailure).isTrue()
            assertThat(server.port()).isEqualTo(port)

            server.restoreConnections()
            client.newCall(Request.Builder().url(locator).build()).execute().use { response ->
                assertThat(response.code).isEqualTo(200)
                assertThat(response.body.bytes()).hasLength(media.size)
            }
            assertThat(server.port()).isEqualTo(port)
            assertThat(server.requestCount()).isAtLeast(2)
        }
    }
}
