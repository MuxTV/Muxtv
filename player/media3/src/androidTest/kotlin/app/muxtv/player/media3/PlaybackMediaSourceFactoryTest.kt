package app.muxtv.player.media3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.network.MuxTvHttpClients
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@AndroidXOptIn(UnstableApi::class)
class PlaybackMediaSourceFactoryTest {
    @Test
    fun headersAreScopedToTheInstalledPlaybackRequest() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(body = "first"))
            server.enqueue(MockResponse(body = "second"))

            val context = ApplicationProvider.getApplicationContext<Context>()
            val factory = PlaybackMediaSourceFactory(
                context = context,
                callFactory = MuxTvHttpClients().playback,
            )
            val firstRequest = playbackRequest(
                mediaId = "channel-a",
                locator = server.url("/first.ts").toString(),
                headers = mapOf(
                    "Authorization" to "Bearer first-secret",
                    "User-Agent" to "MuxTV-First/1",
                ),
            )
            val secondRequest = playbackRequest(
                mediaId = "channel-b",
                locator = server.url("/second.ts").toString(),
                headers = mapOf(
                    "User-Agent" to "MuxTV-Second/1",
                ),
            )

            assertThat(read(factory, firstRequest)).isEqualTo("first")
            assertThat(read(factory, secondRequest)).isEqualTo("second")

            val first = server.takeRequest()
            val second = server.takeRequest()
            assertThat(first.headers["Authorization"]).isEqualTo("Bearer first-secret")
            assertThat(first.headers["User-Agent"]).isEqualTo("MuxTV-First/1")
            assertThat(second.headers["Authorization"]).isNull()
            assertThat(second.headers["User-Agent"]).isEqualTo("MuxTV-Second/1")
        }
    }

    private fun read(
        factory: PlaybackMediaSourceFactory,
        request: PlaybackSessionRequest,
    ): String {
        val dataSource = factory.createHttpDataSourceFactory(request).createDataSource()
        dataSource.open(DataSpec(Uri.parse(request.locator)))
        return try {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1_024)
            while (true) {
                val count = dataSource.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        } finally {
            dataSource.close()
        }
    }

    private fun playbackRequest(
        mediaId: String,
        locator: String,
        headers: Map<String, String>,
    ) = PlaybackSessionRequest(
        mediaId = mediaId,
        variantId = "variant-$mediaId",
        locator = locator,
        requestHeaders = headers,
    )
}
