package app.muxtv.catalog.ingest

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class XtreamCompatibilityCorpusTest {
    @Test
    fun `manifest v1 declares complete sanitized Xtream corpus`() = runTest {
        val manifest = loadManifestV1()

        assertThat(manifest.map { it.id }).containsExactlyElementsIn(EXPECTED_IDS)
        assertThat(manifest.map { it.id }).containsNoDuplicates()
        assertThat(manifest.map { it.path }).containsNoDuplicates()
        assertThat(discoverFixturePaths()).containsExactlyElementsIn(manifest.map { it.path })

        manifest.forEach { fixture ->
            assertThat(fixture.safeExpectation).isNotEmpty()
            assertThat(fixture.path.substringBefore('/')).isEqualTo(fixture.category)

            val rawFixture = readResource(fixture.path)
            assertSyntheticNetworkLocationsOnly(rawFixture)

            when (fixture.expectedOutcome) {
                ExpectedOutcome.AUTHENTICATED -> {
                    val result = StreamingXtreamParser().parseAuth(
                        rawFixture.byteInputStream(StandardCharsets.UTF_8),
                    )
                    assertThat(result).isInstanceOf(XtreamAuthResult.Authenticated::class.java)
                    val authenticated = result as XtreamAuthResult.Authenticated
                    assertThat(authenticated.allowedOutputFormats).containsExactly("m3u8", "ts").inOrder()
                    assertRedaction(fixture.redactionProbe, result.toString())
                }

                ExpectedOutcome.AUTH_REJECTED -> {
                    val result = StreamingXtreamParser().parseAuth(
                        rawFixture.byteInputStream(StandardCharsets.UTF_8),
                    )
                    assertThat(result).isEqualTo(XtreamAuthResult.Rejected)
                    assertRedaction(fixture.redactionProbe, result.toString())
                }

                ExpectedOutcome.LIVE -> {
                    val sink = CorpusRecordingXtreamSink()
                    val report = StreamingXtreamParser().parseLive(
                        input = rawFixture.byteInputStream(StandardCharsets.UTF_8),
                        sink = sink,
                    )
                    assertThat(report.parsedEntries).isEqualTo(fixture.expectedEntries)
                    assertThat(report.skippedEntries).isEqualTo(fixture.expectedSkipped)
                    assertThat(report.warningCount).isEqualTo(fixture.expectedWarnings)
                    assertFixtureSemantics(fixture.id, sink)
                    assertRedaction(
                        fixture.redactionProbe,
                        buildString {
                            append(report).append('\n')
                            sink.entries.forEach { append(it).append('\n') }
                            sink.warnings.forEach { append(it).append('\n') }
                        },
                    )
                }

                ExpectedOutcome.FORMAT_REJECTED -> {
                    val failure = runCatching {
                        StreamingXtreamParser().parseLive(
                            input = rawFixture.byteInputStream(StandardCharsets.UTF_8),
                            sink = CorpusRecordingXtreamSink(),
                        )
                    }.exceptionOrNull()
                    assertThat(failure).isInstanceOf(XtreamFormatException::class.java)
                    assertRedaction(fixture.redactionProbe, failure.toString())
                }
            }
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

    @Test
    fun `large live array emits before the complete input is consumed`() = runTest {
        val payload = buildLargeLivePayload(entryCount = 240, paddingCharacters = 2_048)
        val input = CountingInputStream(ByteArrayInputStream(payload))
        var bytesReadAtFirstEntry: Long? = null
        val sink = object : XtreamLiveSink {
            override suspend fun onEntry(entry: XtreamLiveEntry) {
                if (bytesReadAtFirstEntry == null) bytesReadAtFirstEntry = input.bytesRead
            }
        }

        val report = StreamingXtreamParser().parseLive(input = input, sink = sink)

        assertThat(report.parsedEntries).isEqualTo(240)
        assertThat(bytesReadAtFirstEntry).isNotNull()
        assertThat(bytesReadAtFirstEntry!!).isLessThan(payload.size.toLong())
    }

    @Test
    fun `live item count is a hard bound`() = runTest {
        val payload = buildLargeLivePayload(entryCount = 4, paddingCharacters = 0)
        val sink = CorpusRecordingXtreamSink()

        val failure = runCatching {
            StreamingXtreamParser().parseLive(
                input = payload.inputStream(),
                sink = sink,
                limits = XtreamParseLimits(maxItems = 3),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamLimitExceededException::class.java)
        assertThat((failure as XtreamLimitExceededException).reason)
            .isEqualTo(XtreamLimitReason.ItemCountExceeded)
        assertThat(sink.entries).hasSize(3)
    }

    @Test
    fun `known field length is a hard bound`() = runTest {
        val payload = """[{"stream_id":1,"name":"123456789","stream_type":"live"}]"""

        val failure = runCatching {
            StreamingXtreamParser().parseLive(
                input = payload.byteInputStream(),
                sink = CorpusRecordingXtreamSink(),
                limits = XtreamParseLimits(maxFieldCharacters = 8),
            )
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(XtreamLimitExceededException::class.java)
        assertThat((failure as XtreamLimitExceededException).reason)
            .isEqualTo(XtreamLimitReason.FieldCharactersExceeded)
    }

    @Test
    fun `sink cancellation remains terminal`() = runTest {
        val payload = buildLargeLivePayload(entryCount = 4, paddingCharacters = 0)
        val sink = object : XtreamLiveSink {
            override suspend fun onEntry(entry: XtreamLiveEntry) {
                throw CancellationException("test cancellation")
            }
        }

        val failure = runCatching {
            StreamingXtreamParser().parseLive(input = payload.inputStream(), sink = sink)
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    private fun loadManifestV1(): List<ManifestFixture> {
        val lines = readResource(MANIFEST_FILE)
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()

        check(lines.isNotEmpty()) { "Xtream compatibility manifest is empty." }
        check(lines.first() == SCHEMA_MARKER) { "Unsupported Xtream compatibility manifest schema." }
        check(lines.size >= 2 && lines[1] == MANIFEST_HEADER) { "Unexpected Xtream manifest header." }

        return lines.drop(2).mapIndexed { index, line ->
            val columns = line.split('\t')
            check(columns.size == MANIFEST_COLUMN_COUNT) {
                "Invalid Xtream manifest row ${index + 3}: expected $MANIFEST_COLUMN_COUNT columns, found ${columns.size}."
            }
            ManifestFixture(
                id = columns[0],
                path = columns[1],
                category = columns[2],
                surface = CorpusSurface.valueOf(columns[3]),
                disposition = SupportDisposition.valueOf(columns[4]),
                expectedOutcome = ExpectedOutcome.valueOf(columns[5]),
                expectedEntries = columns[6].toInt(),
                expectedSkipped = columns[7].toInt(),
                expectedWarnings = columns[8].toInt(),
                safeExpectation = columns[9],
                redactionProbe = columns[10].takeUnless { it == "-" },
            ).also { fixture ->
                check(fixture.id.isNotBlank())
                check(fixture.path.endsWith(".json"))
                check(!fixture.path.startsWith('/') && ".." !in fixture.path && '\\' !in fixture.path)
            }
        }
    }

    private fun discoverFixturePaths(): Set<String> {
        val rootUrl = checkNotNull(javaClass.classLoader.getResource(CORPUS_ROOT)) {
            "Missing Xtream compatibility resource root: $CORPUS_ROOT"
        }
        check(rootUrl.protocol == "file")
        val rootPath = java.nio.file.Paths.get(rootUrl.toURI())
        val result = linkedSetOf<String>()
        java.nio.file.Files.walk(rootPath).use { paths ->
            paths
                .filter { java.nio.file.Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                .forEach { path -> result += rootPath.relativize(path).toString().replace('\\', '/') }
        }
        return result
    }

    private fun assertSyntheticNetworkLocationsOnly(rawFixture: String) {
        HTTP_URL.findAll(rawFixture).forEach { match ->
            val host = URI(match.value).host
            check(host != null && host.endsWith(".invalid")) {
                "Xtream compatibility fixture contains a non-synthetic network host."
            }
        }
    }

    private fun assertFixtureSemantics(id: String, sink: CorpusRecordingXtreamSink) {
        when (id) {
            "live-basic" -> {
                assertThat(sink.entries.map { it.streamId }).containsExactly(101L, 102L).inOrder()
                assertThat(sink.entries.first().name).isEqualTo("Synthetic News")
                assertThat(sink.entries.first().epgChannelId).isEqualTo("synthetic.news")
                assertThat(sink.entries.first().categoryId).isEqualTo("10")
            }

            "live-type-variance" -> with(sink.entries.single()) {
                assertThat(streamId).isEqualTo(707L)
                assertThat(channelNumber).isEqualTo(7)
                assertThat(categoryId).isEqualTo("12")
                assertThat(archiveAvailable).isTrue()
                assertThat(archiveDurationDays).isEqualTo(7)
            }

            "live-null-missing-optional" -> with(sink.entries.single()) {
                assertThat(streamId).isEqualTo(808L)
                assertThat(channelNumber).isNull()
                assertThat(streamIcon).isNull()
                assertThat(epgChannelId).isNull()
                assertThat(categoryId).isNull()
                assertThat(archiveAvailable).isNull()
                assertThat(archiveDurationDays).isNull()
            }

            "live-malformed-item-preserves-valid" -> {
                assertThat(sink.entries.single().streamId).isEqualTo(909L)
                assertThat(sink.warnings.map { it.kind }).containsExactly(XtreamWarningKind.InvalidIdentity)
            }

            "live-unknown-extra-fields" -> assertThat(sink.entries.single().streamId).isEqualTo(1001L)

            "live-archive-characterization" -> with(sink.entries.single()) {
                assertThat(streamId).isEqualTo(1101L)
                assertThat(archiveAvailable).isTrue()
                assertThat(archiveDurationDays).isEqualTo(14)
            }

            "live-secret-redaction" -> assertThat(sink.entries.single().streamId).isEqualTo(1201L)

            "live-non-live-item-is-ignored" -> {
                assertThat(sink.entries.single().streamId).isEqualTo(1302L)
                assertThat(sink.warnings.map { it.kind }).containsExactly(XtreamWarningKind.NonLiveItem)
            }

            "live-invalid-optional-fields" -> with(sink.entries.single()) {
                assertThat(streamId).isEqualTo(1401L)
                assertThat(channelNumber).isNull()
                assertThat(streamIcon).isNull()
                assertThat(categoryId).isNull()
                assertThat(archiveAvailable).isNull()
                assertThat(archiveDurationDays).isNull()
                assertThat(sink.warnings.map { it.kind })
                    .containsExactly(XtreamWarningKind.InvalidOptionalField)
            }

            else -> Unit
        }
    }

    private fun assertRedaction(probe: String?, diagnostics: String) {
        if (probe != null) assertThat(diagnostics).doesNotContain(probe)
    }

    private fun readResource(relativePath: String): String {
        val fullPath = "$CORPUS_ROOT/$relativePath"
        return checkNotNull(javaClass.classLoader.getResourceAsStream(fullPath)) {
            "Missing Xtream compatibility resource: $fullPath"
        }.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun buildLargeLivePayload(entryCount: Int, paddingCharacters: Int): ByteArray {
        val padding = "x".repeat(paddingCharacters)
        return buildString {
            append('[')
            repeat(entryCount) { index ->
                if (index != 0) append(',')
                append("{\"stream_id\":")
                append(index + 1)
                append(",\"name\":\"Synthetic ")
                append(index + 1)
                append("\",\"stream_type\":\"live\",\"future_padding\":\"")
                append(padding)
                append("\"}")
            }
            append(']')
        }.toByteArray(StandardCharsets.UTF_8)
    }

    private data class ManifestFixture(
        val id: String,
        val path: String,
        val category: String,
        val surface: CorpusSurface,
        val disposition: SupportDisposition,
        val expectedOutcome: ExpectedOutcome,
        val expectedEntries: Int,
        val expectedSkipped: Int,
        val expectedWarnings: Int,
        val safeExpectation: String,
        val redactionProbe: String?,
    )

    private enum class CorpusSurface { AUTH, LIVE }
    private enum class ExpectedOutcome { AUTHENTICATED, AUTH_REJECTED, LIVE, FORMAT_REJECTED }
    private enum class SupportDisposition { SUPPORTED, IGNORED_SAFE, REJECTED, NOT_IMPLEMENTED }

    private companion object {
        const val CORPUS_ROOT = "compatibility/xtream"
        const val MANIFEST_FILE = "manifest-v1.tsv"
        const val SCHEMA_MARKER = "# schema_version=1"
        const val MANIFEST_HEADER =
            "id\tpath\tcategory\tsurface\tdisposition\texpected_outcome\texpected_entries\texpected_skipped\texpected_warnings\tsafe_expectation\tredaction_probe"
        const val MANIFEST_COLUMN_COUNT = 11
        val EXPECTED_IDS = setOf(
            "auth-active",
            "auth-rejected",
            "live-basic",
            "live-type-variance",
            "live-null-missing-optional",
            "live-malformed-item-preserves-valid",
            "live-unknown-extra-fields",
            "live-archive-characterization",
            "live-secret-redaction",
            "live-non-live-item-is-ignored",
            "live-invalid-optional-fields",
            "live-structural-rejected",
        )
        val HTTP_URL = Regex("https?://[^\\s\\\",]+")
    }
}

private class CorpusRecordingXtreamSink : XtreamLiveSink {
    val entries = mutableListOf<XtreamLiveEntry>()
    val warnings = mutableListOf<XtreamWarning>()

    override suspend fun onEntry(entry: XtreamLiveEntry) {
        entries += entry
    }

    override suspend fun onWarning(warning: XtreamWarning) {
        warnings += warning
    }
}

private class CountingInputStream(
    private val delegate: InputStream,
) : InputStream() {
    var bytesRead: Long = 0
        private set

    override fun read(): Int = delegate.read().also { value ->
        if (value >= 0) bytesRead += 1
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate.read(buffer, offset, length).also { count ->
            if (count > 0) bytesRead += count
        }

    override fun close() = delegate.close()
}
