package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MuxTvHttpResourcesTest {
    @Test
    fun `source and playback clients share dispatcher and connection pool`() {
        val clients = MuxTvHttpClients()

        assertThat(clients.source.dispatcher).isSameInstanceAs(clients.playback.dispatcher)
        assertThat(clients.source.connectionPool).isSameInstanceAs(clients.playback.connectionPool)
    }

    @Test
    fun `source client disables built-in redirects and applies bounded timeouts`() {
        val source = MuxTvHttpClients().source

        assertThat(source.followRedirects).isFalse()
        assertThat(source.followSslRedirects).isFalse()
        assertThat(source.interceptors.any { it is SecureRedirectInterceptor }).isTrue()
        assertThat(source.connectTimeoutMillis).isEqualTo(10_000)
        assertThat(source.readTimeoutMillis).isEqualTo(30_000)
        assertThat(source.writeTimeoutMillis).isEqualTo(30_000)
        assertThat(source.callTimeoutMillis).isEqualTo(120_000)
    }

    @Test
    fun `playback client has no global call deadline and keeps redirects disabled`() {
        val playback = MuxTvHttpClients().playback

        assertThat(playback.followRedirects).isFalse()
        assertThat(playback.followSslRedirects).isFalse()
        assertThat(playback.connectTimeoutMillis).isEqualTo(10_000)
        assertThat(playback.readTimeoutMillis).isEqualTo(60_000)
        assertThat(playback.writeTimeoutMillis).isEqualTo(30_000)
        assertThat(playback.callTimeoutMillis).isEqualTo(0)
    }
}
