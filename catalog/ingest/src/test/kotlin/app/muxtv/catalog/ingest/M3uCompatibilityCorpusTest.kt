package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.test.runTest
import org.junit.Test

class M3uCompatibilityCorpusTest {
    @Test
    fun `manifest v1 declares complete sanitized compatibility corpus`() = runTest {
        val manifest = loadManifestV1()

        assertThat(manifest.map { it.id }).containsExactlyElementsIn(EXPECTED_IDS)
        assertThat(manifest.map { it.id }).containsNoDuplicates()
        assertThat(manifest.map { it.path }).containsNoDuplicates()
        assertThat(discoverFixturePaths()).containsExactlyElementsIn(manifest.map { it.path })

        manifest.forEach { fixture ->
            assertThat(fixture.disposition).isEqualTo(SupportDisposition.SUPPORTED)
            assertThat(fixture.safeExpectation).isNotEmpty()
            assertThat(fixture.path.substringBefore('/')).isEqualTo(fixture.category)

            val rawFixture = readResource(fixture.path)
            assertSyntheticNetworkLocationsOnly(rawFixture)

            val sink = CorpusRecordingSink()
            val report = StreamingM3uParser().parse(
                input = rawFixture.byteInputStream(StandardCharsets.UTF_8),
                sink = sink,
            )

            assertThat(report.parsedEntries).isEqualTo(fixture.expectedEntries)
            assertThat(report.skippedEntries).isEqualTo(fixture.expectedSkipped)
            assertThat(report.warningCount).isEqualTo(fixture.expectedWarnings)
            assertFixtureSemantics(fixture.id, sink)
            assertRedaction(fixture.redactionProbe, report, sink)
        }
    }

    @Test
    fun `support disposition vocabulary is explicit and stable`() {
        assertThat(SupportDisposition.entries.map { it.name }).containsExactly(
            "SUPPORTED",
            "IGNORED_SAFE",
            "REJECTED",
            "NOT_IMPLEMENTED",
        ).inOrder()
    }

    private fun loadManifestV1(): List<ManifestFixture> {
        val lines = readResource(MANIFEST_FILE)
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()

        check(lines.isNotEmpty()) { "Compatibility manifest is empty: $CORPUS_ROOT/$MANIFEST_FILE" }
        check(lines.first() == SCHEMA_MARKER) {
            "Unsupported compatibility manifest schema marker: ${lines.first()}"
        }
        check(lines.size >= 2 && lines[1] == MANIFEST_HEADER) {
            "Unexpected compatibility manifest header."
        }

        return lines.drop(2).mapIndexed { index, line ->
            val columns = line.split('\t')
            check(columns.size == MANIFEST_COLUMN_COUNT) {
                "Invalid compatibility manifest row ${index + 3}: expected $MANIFEST_COLUMN_COUNT columns, found ${columns.size}."
            }
            ManifestFixture(
                id = columns[0],
                path = columns[1],
                category = columns[2],
                disposition = SupportDisposition.valueOf(columns[3]),
                expectedEntries = columns[4].toInt(),
                expectedSkipped = columns[5].toInt(),
                expectedWarnings = columns[6].toInt(),
                safeExpectation = columns[7],
                redactionProbe = columns[8].takeUnless { it == "-" },
            ).also { fixture ->
                check(fixture.id.isNotBlank()) { "Compatibility fixture id must not be blank." }
                check(fixture.path.endsWith(".m3u")) { "Compatibility fixture must be an .m3u file: ${fixture.path}" }
                check(!fixture.path.startsWith('/') && ".." !in fixture.path && '\\' !in fixture.path) {
                    "Unsafe compatibility fixture path: ${fixture.path}"
                }
            }
        }
    }

    private fun discoverFixturePaths(): Set<String> {
        val rootUrl = checkNotNull(javaClass.classLoader.getResource(CORPUS_ROOT)) {
            "Missing compatibility resource root: $CORPUS_ROOT"
        }
        check(rootUrl.protocol == "file") {
            "Compatibility corpus must be a file-backed JVM test resource, found protocol=${rootUrl.protocol}."
        }
        val rootPath = Paths.get(rootUrl.toURI())
        val result = linkedSetOf<String>()
        Files.walk(rootPath).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".m3u") }
                .forEach { path ->
                    result += rootPath.relativize(path).toString().replace('\\', '/')
                }
        }
        return result
    }

    private fun assertSyntheticNetworkLocationsOnly(rawFixture: String) {
        HTTP_URL.findAll(rawFixture).forEach { match ->
            val host = URI(match.value).host
            check(host != null && host.endsWith(".invalid")) {
                "Compatibility fixture contains a non-synthetic network host: ${match.value}"
            }
        }
    }

    private fun assertFixtureSemantics(
        id: String,
        sink: CorpusRecordingSink,
    ) {
        when (id) {
            "basic-tvg-and-group" -> {
                val entry = sink.entries.single()
                assertThat(entry.displayName).isEqualTo("Synthetic News")
                assertThat(entry.tvgId).isEqualTo("synthetic.news")
                assertThat(entry.tvgName).isEqualTo("Synthetic News")
                assertThat(entry.tvgLogo).isEqualTo("https://images.invalid/news.png")
                assertThat(entry.groupTitle).isEqualTo("News")
                assertThat(entry.channelNumber).isEqualTo("101")
            }

            "vlc-user-agent-referrer" -> {
                val entry = sink.entries.single()
                assertThat(entry.userAgent).isEqualTo("MuxTV TEST_VLC_SECRET Agent")
                assertThat(entry.referrer).isEqualTo("https://referrer.invalid/TEST_VLC_SECRET/")
                assertThat(entry.locator).isEqualTo("https://streams.invalid/live/vlc.m3u8?token=TEST_VLC_SECRET")
            }

            "kodi-properties" -> {
                val entry = sink.entries.single()
                assertThat(entry.userAgent).isEqualTo("MuxTV TEST_KODI_SECRET Agent")
                assertThat(entry.referrer).isEqualTo("https://referrer.invalid/TEST_KODI_SECRET/")
                assertThat(entry.locator).isEqualTo("https://streams.invalid/live/kodi.ts?token=TEST_KODI_SECRET")
            }

            "catchup-metadata" -> {
                val entry = sink.entries.single()
                assertThat(entry.catchupMode).isEqualTo("append")
                assertThat(entry.catchupSource).isEqualTo("?utc={utc}&token=TEST_CATCHUP_SECRET")
                assertThat(entry.catchupDays).isEqualTo(7)
                assertThat(entry.catchupCorrection).isEqualTo("+2.0")
            }

            "url-tvg-header" -> {
                val header = sink.headers.single()
                assertThat(header.epgUrls).containsExactly(
                    "https://epg.invalid/guide.xml?token=TEST_EPG_SECRET",
                    "https://backup.invalid/guide.xml",
                    "https://third.invalid/guide.xml",
                ).inOrder()
            }

            "malformed-entry-preserves-following-valid-entry" -> {
                assertThat(sink.warnings.map { it.kind }).containsExactly(
                    M3uWarningKind.MalformedExtInf,
                    M3uWarningKind.BareLocator,
                ).inOrder()
                assertThat(sink.entries.last().tvgId).isEqualTo("synthetic.valid")
                assertThat(sink.entries.last().displayName).isEqualTo("Synthetic Valid After Malformed")
            }

            "secret-redaction" -> {
                assertThat(sink.entries).hasSize(1)
                assertThat(sink.headers).hasSize(1)
            }

            "extgrp-begin-directive-scope" -> {
                val entries = sink.entries
                assertThat(entries).hasSize(6)
                assertThat(entries[0].tvgId).isEqualTo("news.one")
                assertThat(entries[0].groupTitle).isEqualTo("News")
                assertThat(entries[1].tvgId).isEqualTo("news.two")
                assertThat(entries[1].groupTitle).isEqualTo("News")
                assertThat(entries[2].tvgId).isEqualTo("sports.one")
                assertThat(entries[2].groupTitle).isEqualTo("Sports")
                assertThat(entries[3].tvgId).isEqualTo("plain.after.sports")
                assertThat(entries[3].groupTitle).isNull()
                assertThat(entries[4].tvgId).isEqualTo("movies.one")
                assertThat(entries[4].groupTitle).isEqualTo("Movies")
                assertThat(entries[5].tvgId).isEqualTo("plain.after.empty")
                assertThat(entries[5].groupTitle).isNull()
                assertThat(sink.warnings).isEmpty()
            }

            else -> error("Manifest fixture has no semantic contract: $id")
        }
    }

    private fun assertRedaction(
        probe: String?,
        report: M3uParseReport,
        sink: CorpusRecordingSink,
    ) {
        if (probe == null) return
        val diagnostics = buildString {
            append(report).append('\n')
            sink.headers.forEach { append(it).append('\n') }
            sink.entries.forEach { append(it).append('\n') }
            sink.warnings.forEach { append(it).append('\n') }
        }
        assertThat(diagnostics).doesNotContain(probe)
    }

    private fun readResource(relativePath: String): String {
        val fullPath = "$CORPUS_ROOT/$relativePath"
        return openResource(fullPath).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun openResource(fullPath: String): InputStream =
        checkNotNull(javaClass.classLoader.getResourceAsStream(fullPath)) {
            "Missing compatibility resource: $fullPath"
        }

    private data class ManifestFixture(
        val id: String,
        val path: String,
        val category: String,
        val disposition: SupportDisposition,
        val expectedEntries: Int,
        val expectedSkipped: Int,
        val expectedWarnings: Int,
        val safeExpectation: String,
        val redactionProbe: String?,
    )

    private enum class SupportDisposition {
        SUPPORTED,
        IGNORED_SAFE,
        REJECTED,
        NOT_IMPLEMENTED,
    }

    private companion object {
        const val CORPUS_ROOT = "compatibility/m3u"
        const val MANIFEST_FILE = "manifest-v1.tsv"
        const val SCHEMA_MARKER = "# schema_version=1"
        const val MANIFEST_HEADER =
            "id\tpath\tcategory\tdisposition\texpected_entries\texpected_skipped\texpected_warnings\tsafe_expectation\tredaction_probe"
        const val MANIFEST_COLUMN_COUNT = 9
        val EXPECTED_IDS = setOf(
            "basic-tvg-and-group",
            "vlc-user-agent-referrer",
            "kodi-properties",
            "catchup-metadata",
            "url-tvg-header",
            "malformed-entry-preserves-following-valid-entry",
            "secret-redaction",
            "extgrp-begin-directive-scope",
        )
        val HTTP_URL = Regex("https?://[^\\s\\\",]+")
    }
}

private class CorpusRecordingSink : M3uParseSink {
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
