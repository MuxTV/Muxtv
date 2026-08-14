package app.muxtv.external

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ExternalPlaybackIntentParserTest {
    @Test
    fun `torserve style intent is accepted`() {
        val result = ExternalPlaybackIntentParser.parse(
            action = ExternalPlaybackIntentParser.ACTION_VIEW,
            uriString = "http://192.168.1.10:8090/stream/file.mkv?link=torrent-hash&index=1&play",
            mimeType = "video/x-matroska",
            displayTitle = "Фильм (2026) 4K",
            sourcePackage = "ru.yourok.torrserve",
        )

        assertThat(result).isInstanceOf(ExternalPlaybackIntentResult.Accepted::class.java)
        val accepted = result as ExternalPlaybackIntentResult.Accepted
        assertThat(accepted.locator).contains("torrent-hash")
        assertThat(accepted.mimeType).isEqualTo("video/x-matroska")
        assertThat(accepted.displayTitle).isEqualTo("Фильм (2026) 4K")
        assertThat(accepted.sourcePackage).isEqualTo("ru.yourok.torrserve")
    }

    @Test
    fun `https uri without mime is accepted`() {
        val result = ExternalPlaybackIntentParser.parse(
            action = ExternalPlaybackIntentParser.ACTION_VIEW,
            uriString = "https://media.example.org/movie.mp4",
            mimeType = null,
            displayTitle = null,
            sourcePackage = null,
        )

        assertThat(result).isInstanceOf(ExternalPlaybackIntentResult.Accepted::class.java)
    }

    @Test
    fun `wrong action is rejected`() {
        val result = ExternalPlaybackIntentParser.parse(
            action = "android.intent.action.MAIN",
            uriString = "http://192.168.1.10:8090/stream/file.mkv",
            mimeType = "video/x-matroska",
            displayTitle = null,
            sourcePackage = null,
        )

        assertThat(result).isEqualTo(
            ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.WrongAction,
            ),
        )
    }

    @Test
    fun `missing or malformed uri is rejected`() {
        assertThat(
            ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = null,
                mimeType = null,
                displayTitle = null,
                sourcePackage = null,
            ),
        ).isEqualTo(
            ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.MissingUri,
            ),
        )
        assertThat(
            ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = "::not a uri::",
                mimeType = null,
                displayTitle = null,
                sourcePackage = null,
            ),
        ).isEqualTo(
            ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.MissingUri,
            ),
        )
    }

    @Test
    fun `non http schemes are rejected in v1`() {
        for (uri in listOf(
            "ftp://host/file.mkv",
            "file:///sdcard/movie.mp4",
            "content://media/external/video/media/1",
            "rtsp://camera/live",
        )) {
            assertThat(
                ExternalPlaybackIntentParser.parse(
                    action = ExternalPlaybackIntentParser.ACTION_VIEW,
                    uriString = uri,
                    mimeType = "video/*",
                    displayTitle = null,
                    sourcePackage = null,
                ),
            ).isEqualTo(
                ExternalPlaybackIntentResult.Rejected(
                    ExternalPlaybackIntentRejection.UnsupportedScheme,
                ),
            )
        }
    }

    @Test
    fun `embedded credentials are rejected`() {
        assertThat(
            ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = "http://user:secret@192.168.1.10:8090/stream/file.mkv",
                mimeType = "video/x-matroska",
                displayTitle = null,
                sourcePackage = null,
            ),
        ).isEqualTo(
            ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.EmbeddedCredentials,
            ),
        )
    }

    @Test
    fun `non video mime is rejected`() {
        for (mime in listOf("audio/mpeg", "application/octet-stream", "text/html")) {
            assertThat(
                ExternalPlaybackIntentParser.parse(
                    action = ExternalPlaybackIntentParser.ACTION_VIEW,
                    uriString = "http://192.168.1.10:8090/stream/file.bin",
                    mimeType = mime,
                    displayTitle = null,
                    sourcePackage = null,
                ),
            ).isEqualTo(
                ExternalPlaybackIntentResult.Rejected(
                    ExternalPlaybackIntentRejection.UnsupportedMimeType,
                ),
            )
        }
    }

    @Test
    fun `oversized fields are rejected`() {
        assertThat(
            ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = "http://h.local/" + "a".repeat(9_000),
                mimeType = null,
                displayTitle = null,
                sourcePackage = null,
            ),
        ).isEqualTo(
            ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.UriTooLong,
            ),
        )
        assertThat(
            ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = "http://h.local/file.mkv",
                mimeType = "video/" + "a".repeat(300),
                displayTitle = null,
                sourcePackage = null,
            ),
        ).isEqualTo(
            ExternalPlaybackIntentResult.Rejected(
                ExternalPlaybackIntentRejection.InvalidMetadata,
            ),
        )
    }

    @Test
    fun `titles are sanitized without rejecting playback`() {
        val result = ExternalPlaybackIntentParser.parse(
            action = ExternalPlaybackIntentParser.ACTION_VIEW,
            uriString = "http://192.168.1.10:8090/stream/file.mkv",
            mimeType = "video/x-matroska",
            displayTitle = "  Линия\u202E подмена\u0007  \r\n ",
            sourcePackage = null,
        )

        assertThat(result).isInstanceOf(ExternalPlaybackIntentResult.Accepted::class.java)
        val accepted = result as ExternalPlaybackIntentResult.Accepted
        assertThat(accepted.displayTitle).isEqualTo("Линия подмена")
    }

    @Test
    fun `oversized or blank titles fall back to null`() {
        assertThat(
            (ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = "http://h.local/file.mkv",
                mimeType = null,
                displayTitle = "t".repeat(600),
                sourcePackage = null,
            ) as ExternalPlaybackIntentResult.Accepted).displayTitle,
        ).isNull()
        assertThat(
            (ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = "http://h.local/file.mkv",
                mimeType = null,
                displayTitle = "  \u202E  ",
                sourcePackage = null,
            ) as ExternalPlaybackIntentResult.Accepted).displayTitle,
        ).isNull()
    }

    @Test
    fun `invalid source packages are dropped`() {
        assertThat(
            (ExternalPlaybackIntentParser.parse(
                action = ExternalPlaybackIntentParser.ACTION_VIEW,
                uriString = "http://h.local/file.mkv",
                mimeType = null,
                displayTitle = null,
                sourcePackage = "bad package!",
            ) as ExternalPlaybackIntentResult.Accepted).sourcePackage,
        ).isNull()
    }
}
