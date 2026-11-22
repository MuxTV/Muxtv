package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingM3uParserTest {
    @Test
    fun `streams common IPTV metadata without materializing playlist`() = runTest {
        val playlist = "\uFEFF" + """
            #EXTM3U url-tvg="https://epg.example/guide.xml,https://backup.example/guide.xml" refresh="3600"
            #EXTINF:-1 tvg-id="news.world" tvg-name="News, World" tvg-logo="https://img.example/news.png" group-title="Provider" tvg-chno="101" catchup="append" catchup-source="?utc={utc}" catchup-days="7",News, World
            #EXTGRP:News
            #EXTVLCOPT:http-user-agent=MuxTV Test Agent
            #KODIPROP:http-referrer=https://portal.example/
            https://stream.example/live.m3u8?token=secret
            udp://@239.0.0.1:1234
        """.trimIndent()

        val sink = RecordingSink()
        val report = StreamingM3uParser().parse(
            input = ByteArrayInputStream(playlist.toByteArray()),
            sink = sink,
        )

        assertThat(report.hadExtendedHeader).isTrue()
        assertThat(report.parsedEntries).isEqualTo(2)
        assertThat(report.skippedEntries).isEqualTo(0)
        assertThat(report.warningCount).isEqualTo(1)

        val header = sink.headers.single()
        assertThat(header.epgUrls).containsExactly(
            "https://epg.example/guide.xml",
            "https://backup.example/guide.xml",
        ).inOrder()
        assertThat(header.toString()).doesNotContain("url-tvg")
        assertThat(header.toString()).doesNotContain("refresh")
        assertThat(header.toString()).doesNotContain("epg.example")

        val first = sink.entries[0]
        assertThat(first.displayName).isEqualTo("News, World")
        assertThat(first.tvgId).isEqualTo("news.world")
        assertThat(first.groupTitle).isEqualTo("News")
        assertThat(first.channelNumber).isEqualTo("101")
        assertThat(first.catchupMode).isEqualTo("append")
        assertThat(first.catchupDays).isEqualTo(7)
        assertThat(first.userAgent).isEqualTo("MuxTV Test Agent")
        assertThat(first.referrer).isEqualTo("https://portal.example/")
        assertThat(first.locator).contains("token=secret")
        assertThat(first.toString()).doesNotContain("token=secret")
        assertThat(first.toString()).doesNotContain("News, World")
        assertThat(first.toString()).doesNotContain("News")
        assertThat(first.toString()).doesNotContain("news.world")
        assertThat(first.toString()).doesNotContain("MuxTV Test Agent")
        assertThat(first.toString()).doesNotContain("portal.example")
        assertThat(first.toString()).doesNotContain("tvg-id")
        assertThat(first.toString()).doesNotContain("group-title")

        val second = sink.entries[1]
        assertThat(second.displayName).isEqualTo("@239.0.0.1:1234")
        assertThat(sink.warnings.single().kind).isEqualTo(M3uWarningKind.BareLocator)
    }

    @Test
    fun `rejects overlong lines before unbounded allocation`() {
        val parser = StreamingM3uParser()
        val input = ByteArrayInputStream(ByteArray(257) { 'a'.code.toByte() })

        val error = assertThrows(M3uLimitExceededException::class.java) {
            kotlinx.coroutines.test.runTest {
                parser.parse(
                    input = input,
                    sink = RecordingSink(),
                    limits = M3uParseLimits(maxLineBytes = 256),
                )
            }
        }

        assertThat(error.reason).isEqualTo(M3uLimitReason.LineTooLong)
        assertThat(error.lineNumber).isEqualTo(1)
    }

    @Test
    fun `forbidden transport headers cannot be smuggled through supported request syntaxes`() = runTest {
        val secret = "TEST_C21_FORBIDDEN_TRANSPORT"
        val playlist = """
            #EXTM3U
            #EXTINF:-1,C21 VLC Forbidden
            #EXTVLCOPT:http-host=$secret.invalid
            https://streams.invalid/live/vlc.ts
            #EXTINF:-1,C21 EXTHTTP Forbidden
            #EXTHTTP:{"Host":"$secret.invalid"}
            https://streams.invalid/live/ext-http.ts
            #EXTINF:-1,C21 Kodi Forbidden
            #KODIPROP:inputstream.adaptive.stream_headers=Host=$secret.invalid
            https://streams.invalid/live/kodi.ts
            #EXTINF:-1,C21 Pipe Forbidden
            https://streams.invalid/live/pipe.ts|Host=$secret.invalid
        """.trimIndent()

        val sink = RecordingSink()
        val report = StreamingM3uParser().parse(
            input = ByteArrayInputStream(playlist.toByteArray()),
            sink = sink,
        )

        assertThat(sink.entries).hasSize(4)
        sink.entries.forEach { entry ->
            assertThat(entry.requestMetadata.defaultHeaders).isEmpty()
            assertThat(entry.requestMetadata.manifestHeaders).isEmpty()
            assertThat(entry.requestMetadata.segmentHeaders).isEmpty()
        }
        assertThat(sink.entries.last().locator).isEqualTo("https://streams.invalid/live/pipe.ts")
        assertThat(sink.warnings.map { it.kind }).containsExactly(
            M3uWarningKind.ForbiddenRequestHeader,
            M3uWarningKind.ForbiddenRequestHeader,
            M3uWarningKind.ForbiddenRequestHeader,
            M3uWarningKind.ForbiddenRequestHeader,
        ).inOrder()

        val diagnostics = buildString {
            append(report).append('\n')
            sink.entries.forEach { entry ->
                append(entry).append('\n')
                append(entry.requestMetadata).append('\n')
            }
            sink.warnings.forEach { warning -> append(warning).append('\n') }
        }
        assertThat(diagnostics).doesNotContain(secret)
        assertThat(diagnostics).doesNotContain("Host")
    }

    @Test
    fun `recognized pipe header with malformed percent encoding is stripped and redacted`() = runTest {
        val secret = "TEST_C21_BAD_PERCENT"
        val playlist = """
            #EXTM3U
            #EXTINF:-1,C21 Malformed Pipe
            https://streams.invalid/live/malformed.ts|Host=$secret%ZZ
        """.trimIndent()

        val sink = RecordingSink()
        val report = StreamingM3uParser().parse(
            input = ByteArrayInputStream(playlist.toByteArray()),
            sink = sink,
        )

        val entry = sink.entries.single()
        assertThat(entry.locator).isEqualTo("https://streams.invalid/live/malformed.ts")
        assertThat(entry.requestMetadata.defaultHeaders).isEmpty()
        assertThat(sink.warnings.map { it.kind }).containsExactly(
            M3uWarningKind.MalformedRequestMetadata,
        )

        val diagnostics = buildString {
            append(report).append('\n')
            append(entry).append('\n')
            append(entry.requestMetadata).append('\n')
            sink.warnings.forEach { warning -> append(warning).append('\n') }
        }
        assertThat(diagnostics).doesNotContain(secret)
        assertThat(diagnostics).doesNotContain("Host")
    }

    @Test
    fun `unsupported url pipe assignment remains a literal locator suffix`() = runTest {
        val locator = "https://streams.invalid/live/literal.ts|X-Unbounded-Custom=value"
        val playlist = """
            #EXTM3U
            #EXTINF:-1,C21 Literal Unsupported Pipe
            $locator
        """.trimIndent()

        val sink = RecordingSink()
        val report = StreamingM3uParser().parse(
            input = ByteArrayInputStream(playlist.toByteArray()),
            sink = sink,
        )

        assertThat(report.warningCount).isEqualTo(0)
        assertThat(sink.entries.single().locator).isEqualTo(locator)
        assertThat(sink.entries.single().requestMetadata.defaultHeaders).isEmpty()
    }
}

private class RecordingSink : M3uParseSink {
    val headers = mutableListOf<M3uPlaylistHeader>()
    val entries = mutableListOf<M3uEntry>()
    val warnings = mutableListOf<M3uWarning>()

    override suspend fun onHeader(header: M3uPlaylistHeader) {
        headers += header
    }

    override suspend fun onEntry(entry: M3uEntry) {
        entries += entry
    }

    override suspend fun onWarning(warning: M3uWarning) {
        warnings += warning
    }
}
