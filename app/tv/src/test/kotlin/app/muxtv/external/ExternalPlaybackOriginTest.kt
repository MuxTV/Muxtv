package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalPlaybackOriginTest {
    @Test
    fun `origin keeps only scheme host and port`() {
        val origin = ExternalPlaybackOrigin.parse("http://192.168.1.10:8090/path?query=1")

        assertThat(origin).isNull()
    }

    @Test
    fun `exact origin round trips`() {
        val origin = ExternalPlaybackOrigin.parse("http://192.168.1.10:8090")

        assertThat(origin?.scheme).isEqualTo("http")
        assertThat(origin?.host).isEqualTo("192.168.1.10")
        assertThat(origin?.port).isEqualTo(8090)
        assertThat(origin?.encoded).isEqualTo("http://192.168.1.10:8090")
    }

    @Test
    fun `default ports are folded`() {
        assertThat(ExternalPlaybackOrigin.parse("http://nas.local:80")?.encoded)
            .isEqualTo("http://nas.local")
        assertThat(ExternalPlaybackOrigin.parse("https://media.example.org:443")?.encoded)
            .isEqualTo("https://media.example.org")
    }

    @Test
    fun `locator derives normalized origin ignoring path and query`() {
        assertThat(
            ExternalPlaybackOrigin.fromLocator(
                "http://192.168.1.10:8090/stream/file.mkv?link=torrent-hash&index=1&play",
            )?.encoded,
        ).isEqualTo("http://192.168.1.10:8090")
    }

    @Test
    fun `case and brackets are normalized`() {
        assertThat(ExternalPlaybackOrigin.parse("HTTP://NAS.LOCAL:8090")?.encoded)
            .isEqualTo("http://nas.local:8090")
    }

    @Test
    fun `userinfo pathless fragments and odd schemes are rejected`() {
        assertThat(ExternalPlaybackOrigin.parse("http://user:pass@host")).isNull()
        assertThat(ExternalPlaybackOrigin.parse("http://host/")).isNull()
        assertThat(ExternalPlaybackOrigin.parse("http://host#fragment")).isNull()
        assertThat(ExternalPlaybackOrigin.parse("ftp://host")).isNull()
        assertThat(ExternalPlaybackOrigin.parse("http://")).isNull()
    }

    @Test
    fun `origin string redacts host`() {
        val origin = ExternalPlaybackOrigin.parse("http://192.168.1.10:8090")

        assertThat(origin.toString()).doesNotContain("192.168.1.10")
    }

    @Test
    fun `ipv6 origins round trip through the encoded form`() {
        for (raw in listOf("http://[fe80::1]:8080", "http://[::1]", "http://[fd00::7]")) {
            val origin = ExternalPlaybackOrigin.parse(raw)!!

            assertThat(origin.encoded).contains("[")
            assertThat(ExternalPlaybackOrigin.parse(origin.encoded)).isEqualTo(origin)
        }
    }
}
