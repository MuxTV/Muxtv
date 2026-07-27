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
        assertThat(sink.headers.single().epgUrls).containsExactly(
            "https://epg.example/guide.xml",
            "https://backup.example/guide.xml",
        ).inOrder()

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
