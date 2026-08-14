package app.muxtv.player

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ExternalPlaybackDescriptorTest {
    @Test
    fun `descriptor preserves validated external media fields`() {
        val descriptor = ExternalPlaybackDescriptor(
            locator = "http://192.168.1.10:8090/stream/file.mkv?link=hash&index=1&play",
            mimeType = "video/x-matroska",
            displayTitle = "Фильм 4K",
            sourcePackage = "ru.yourok.torrserve",
            cleartextApproved = true,
        )

        assertThat(descriptor.locator).contains("192.168.1.10")
        assertThat(descriptor.mimeType).isEqualTo("video/x-matroska")
        assertThat(descriptor.displayTitle).isEqualTo("Фильм 4K")
        assertThat(descriptor.sourcePackage).isEqualTo("ru.yourok.torrserve")
        assertThat(descriptor.cleartextApproved).isTrue()
        assertThat(descriptor.isCleartext).isTrue()
    }

    @Test
    fun `https descriptor is never cleartext`() {
        val descriptor = ExternalPlaybackDescriptor("https://media.example.org/movie.mp4")

        assertThat(descriptor.isCleartext).isFalse()
    }

    @Test
    fun `non http locators are rejected`() {
        for (locator in listOf(
            "ftp://host/file.mkv",
            "file:///sdcard/movie.mp4",
            "content://media/video",
            "rtsp://camera/stream",
            "not-a-uri",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ExternalPlaybackDescriptor(locator)
            }
        }
    }

    @Test
    fun `locators with embedded credentials are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExternalPlaybackDescriptor("http://user:secret@192.168.1.10:8090/stream/file.mkv")
        }
    }

    @Test
    fun `locator without host is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExternalPlaybackDescriptor("http:///path/only.mkv")
        }
    }

    @Test
    fun `control characters are rejected in display fields`() {
        assertThrows(IllegalArgumentException::class.java) {
            ExternalPlaybackDescriptor(
                "http://192.168.1.10:8090/stream/file.mkv",
                displayTitle = "line1\nline2",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExternalPlaybackDescriptor(
                "http://192.168.1.10:8090/stream/file.mkv",
                mimeType = "video/mp4\r",
            )
        }
    }

    @Test
    fun `descriptor is not serializable`() {
        val descriptor = ExternalPlaybackDescriptor("http://192.168.1.10:8090/stream/file.mkv")

        assertThat(java.io.Serializable::class.java.isAssignableFrom(descriptor.javaClass)).isFalse()
    }

    @Test
    fun `toString fully redacts locator title and source package`() {
        val descriptor = ExternalPlaybackDescriptor(
            locator = "http://192.168.1.10:8090/stream/secret.mkv?link=torrent-hash&index=3&play",
            mimeType = "video/x-matroska",
            displayTitle = "Secret Movie",
            sourcePackage = "secret.app",
            cleartextApproved = true,
        )

        val printed = descriptor.toString()
        assertThat(printed).doesNotContain("192.168.1.10")
        assertThat(printed).doesNotContain("torrent-hash")
        assertThat(printed).doesNotContain("secret.mkv")
        assertThat(printed).doesNotContain("Secret Movie")
        assertThat(printed).doesNotContain("secret.app")
        assertThat(printed).contains("locator=<redacted>")
        assertThat(printed).contains("hasMimeType=true")
    }
}
