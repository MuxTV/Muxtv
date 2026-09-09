package app.muxtv.catalog.refresh

import app.muxtv.catalog.PlaybackArchiveMetadata
import app.muxtv.catalog.PlaybackArchiveRequest
import app.muxtv.catalog.PlaybackArchiveResolution
import app.muxtv.catalog.PlaybackArchiveUnavailableReason
import app.muxtv.player.PlaybackIntent
import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import org.junit.Test

class CatchupDialectCompatibilityCorpusTest {
    @Test
    fun `manifest v1 is complete sanitized and candidate matches golden descriptors`() {
        val manifest = loadManifestV1()

        assertThat(manifest.map { it.id }).containsNoDuplicates()
        assertThat(manifest.map { it.path }).containsNoDuplicates()
        assertThat(discoverCasePaths()).containsExactlyElementsIn(manifest.map { it.path })

        manifest.forEach { row ->
            assertThat(row.safeExpectation).isNotEmpty()
            assertThat(row.path.substringBefore('/')).isEqualTo(row.category)
            assertThat(row.disposition).isAnyOf(
                SupportDisposition.SUPPORTED,
                SupportDisposition.NOT_IMPLEMENTED,
            )

            val rawCase = readResource(row.path)
            assertThat(rawCase.toByteArray(StandardCharsets.UTF_8).size)
                .isAtMost(MAX_CASE_BYTES)
            val fixture = parseCase(row.id, rawCase)
            assertThat(fixture.expectedStatus).isEqualTo(row.expectedB)
            assertSyntheticNetworkLocationOnly(fixture.live)

            if (fixture.provider == Provider.REFERENCE_ONLY) {
                assertThat(row.disposition).isEqualTo(SupportDisposition.NOT_IMPLEMENTED)
                assertThat(fixture.expectedStatus).isEqualTo("DEFER")
                return@forEach
            }

            val actual = resolveCandidate(fixture)
            val expected = fixture.expectedDescriptor()
            assertThat(actual.descriptor).named(row.id).isEqualTo(expected)

            if (actual.locator != null) {
                assertResolvedLocatorIsSyntheticAndComplete(actual.locator)
                assertTransportSemantics(fixture, actual.locator)
            }
            fixture.redactionProbe?.let { probe ->
                assertThat(actual.diagnostics).named("$row.id diagnostics").doesNotContain(probe)
            }
        }
    }

    @Test
    fun `support disposition vocabulary matches the compatibility corpus contract`() {
        assertThat(SupportDisposition.entries.map { it.name }).containsExactly(
            "SUPPORTED",
            "IGNORED_SAFE",
            "REJECTED",
            "NOT_IMPLEMENTED",
        ).inOrder()
    }

    private fun resolveCandidate(fixture: CatchupCase): CandidateResult = when (fixture.provider) {
        Provider.M3U -> {
            val result = M3uCatchupTransportResolver(nowEpochMillis = { fixture.nowMs }).resolve(
                intent = fixture.intent(),
                liveLocator = fixture.live,
                metadata = M3uCatchupMetadata(
                    mode = fixture.mode,
                    source = fixture.source,
                    days = fixture.days,
                    correction = fixture.correction,
                ),
            )
            when (result) {
                M3uCatchupTransportResolution.NotApplicable -> CandidateResult(
                    descriptor = SanitizedPlaybackDescriptor(status = "NOT_APPLICABLE"),
                    locator = null,
                    diagnostics = result.toString(),
                )
                is M3uCatchupTransportResolution.Unavailable -> CandidateResult(
                    descriptor = SanitizedPlaybackDescriptor(status = result.reason.name),
                    locator = null,
                    diagnostics = result.toString(),
                )
                is M3uCatchupTransportResolution.Ready -> CandidateResult(
                    descriptor = result.toDescriptor(fixture),
                    locator = result.locator,
                    diagnostics = result.toString(),
                )
            }
        }

        Provider.XTREAM -> {
            val result = XtreamPlaybackArchiveResolver(nowEpochMillis = { fixture.nowMs }).resolve(
                PlaybackArchiveRequest(
                    intent = fixture.intent(),
                    livePlaybackReference = fixture.live,
                    metadata = PlaybackArchiveMetadata(
                        mode = fixture.mode,
                        source = fixture.source,
                        days = fixture.days,
                        correction = fixture.correction,
                    ),
                ),
            )
            when (result) {
                PlaybackArchiveResolution.NotApplicable -> CandidateResult(
                    descriptor = SanitizedPlaybackDescriptor(status = "NOT_APPLICABLE"),
                    locator = null,
                    diagnostics = result.toString(),
                )
                is PlaybackArchiveResolution.Unavailable -> CandidateResult(
                    descriptor = SanitizedPlaybackDescriptor(status = result.reason.toCorpusName()),
                    locator = null,
                    diagnostics = result.toString(),
                )
                is PlaybackArchiveResolution.Ready -> CandidateResult(
                    descriptor = result.toDescriptor(fixture),
                    locator = result.locator,
                    diagnostics = result.toString(),
                )
            }
        }

        Provider.REFERENCE_ONLY -> error("Reference-only fixtures are not executable MuxTV candidates")
    }

    private fun M3uCatchupTransportResolution.Ready.toDescriptor(
        fixture: CatchupCase,
    ): SanitizedPlaybackDescriptor = SanitizedPlaybackDescriptor(
        status = "READY",
        transport = transportShape(fixture, locator),
        granularityMs = timeline.granularityMillis,
        mediaOffsetMs = initialMediaPositionMillis,
        queryKeys = queryKeys(locator),
        correctionMs = timeline.correctionMillis,
        windowStartMs = timeline.windowStartEpochMillis,
        windowEndMs = timeline.windowEndEpochMillis,
        programmeStartMs = timeline.programmeStartEpochMillis,
        programmeEndMs = timeline.programmeEndEpochMillis,
        initialPositionMs = timeline.initialPositionEpochMillis,
        playAsLive = timeline.playAsLive,
    )

    private fun PlaybackArchiveResolution.Ready.toDescriptor(
        fixture: CatchupCase,
    ): SanitizedPlaybackDescriptor = SanitizedPlaybackDescriptor(
        status = "READY",
        transport = transportShape(fixture, locator),
        granularityMs = timeline.granularityMillis,
        mediaOffsetMs = initialMediaPositionMillis,
        queryKeys = queryKeys(locator),
        correctionMs = timeline.correctionMillis,
        windowStartMs = timeline.windowStartEpochMillis,
        windowEndMs = timeline.windowEndEpochMillis,
        programmeStartMs = timeline.programmeStartEpochMillis,
        programmeEndMs = timeline.programmeEndEpochMillis,
        initialPositionMs = timeline.initialPositionEpochMillis,
        playAsLive = timeline.playAsLive,
    )

    private fun CatchupCase.expectedDescriptor(): SanitizedPlaybackDescriptor {
        if (expectedStatus != "READY") {
            return SanitizedPlaybackDescriptor(status = expectedStatus)
        }
        return SanitizedPlaybackDescriptor(
            status = "READY",
            transport = expectedTransport,
            granularityMs = expectedGranularityMs,
            mediaOffsetMs = expectedMediaOffsetMs,
            queryKeys = expectedQueryKeys,
            correctionMs = expectedCorrectionMs,
            windowStartMs = expectedWindowStartMs,
            windowEndMs = expectedWindowEndMs,
            programmeStartMs = expectedProgrammeStartMs,
            programmeEndMs = expectedProgrammeEndMs,
            initialPositionMs = expectedInitialPositionMs,
            playAsLive = false,
        )
    }

    private fun transportShape(fixture: CatchupCase, locator: String): String {
        if (fixture.provider == Provider.XTREAM) {
            return if (locator.startsWith("muxtv-provider://xtream/archive/")) {
                "XTREAM_OPAQUE"
            } else {
                "UNKNOWN"
            }
        }
        val uri = URI(locator)
        val path = uri.path.orEmpty()
        val fileName = path.substringAfterLast('/')
        return when {
            fileName.startsWith("timeshift_abs-") && fileName.endsWith(".m3u8") -> "FLUSSONIC_HLS"
            fileName.startsWith("archive-") && fileName.endsWith(".ts") -> "FLUSSONIC_TS"
            path.endsWith("/streaming/timeshift.php") -> "XC_PHP"
            fixture.mode.equals("shift", ignoreCase = true) ||
                fixture.mode.equals("timeshift", ignoreCase = true) -> "SHIFT_QUERY"
            else -> "APPEND_QUERY"
        }
    }

    private fun queryKeys(locator: String): List<String> {
        val rawQuery = runCatching { URI(locator).rawQuery }.getOrNull() ?: return emptyList()
        if (rawQuery.isBlank()) return emptyList()
        return rawQuery.split('&')
            .filter(String::isNotBlank)
            .map { part -> decode(part.substringBefore('=')) }
    }

    private fun queryMap(locator: String): Map<String, String> {
        val rawQuery = URI(locator).rawQuery ?: return emptyMap()
        return rawQuery.split('&')
            .filter(String::isNotBlank)
            .associate { part ->
                decode(part.substringBefore('=')) to decode(part.substringAfter('=', ""))
            }
    }

    private fun assertTransportSemantics(fixture: CatchupCase, locator: String) {
        val uri = URI(locator)
        val query = queryMap(locator)
        when (fixture.id) {
            "append-utc-baseline", "append-existing-query" ->
                assertThat(query["utc"]).isEqualTo("1788344130")

            "append-start-dollar" ->
                assertThat(query["start"]).isEqualTo("1788344130")

            "append-lutc-duration-offset" -> {
                assertThat(query["utc"]).isEqualTo("1788344130")
                assertThat(query["lutc"]).isEqualTo("1788352200")
                assertThat(query["duration"]).isEqualTo("3630")
                assertThat(query["offset"]).isEqualTo("8069")
            }

            "append-date-tokens" ->
                assertThat(query["start"]).isEqualTo("2026-09-02T10:15:30Z")

            "shift-standard", "legacy-timeshift-standard" -> {
                assertThat(query["utc"]).isEqualTo("1788344130")
                assertThat(query["lutc"]).isEqualTo("1788352200")
            }

            "flussonic-hls" ->
                assertThat(uri.path).isEqualTo("/channel/timeshift_abs-1788344130.m3u8")

            "flussonic-ts" ->
                assertThat(uri.path).isEqualTo("/channel/archive-1788344130-3630.ts")

            "m3u-xc-php", "m3u-xtream-php" -> {
                assertThat(uri.path).isEqualTo("/streaming/timeshift.php")
                assertThat(query["stream"]).isEqualTo("707")
                assertThat(query["start"]).isEqualTo("2026-09-02:10-15")
                assertThat(query["duration"]).isEqualTo("60")
                assertThat(query["username"]).isNotEmpty()
                assertThat(query["password"]).isNotEmpty()
            }

            "positive-correction" -> {
                assertThat(query["utc"]).isEqualTo("1788336930")
                assertThat(query["end"]).isEqualTo("1788340560")
            }

            "position-near-live-edge" ->
                assertThat(query["utc"]).isEqualTo("1788352199")

            "xtream-native-path" -> {
                val segments = uri.path.orEmpty().trim('/').split('/')
                assertThat(segments).containsExactly(
                    "archive",
                    "707",
                    "62",
                    "1788344100000",
                    "ts",
                ).inOrder()
            }
        }
    }

    private fun assertResolvedLocatorIsSyntheticAndComplete(locator: String) {
        assertThat(locator).doesNotContain("{")
        assertThat(locator).doesNotContain("}")
        if (locator.startsWith("http://") || locator.startsWith("https://")) {
            val host = URI(locator).host
            assertThat(host).isNotNull()
            assertThat(host!!).endsWith(".invalid")
        }
    }

    private fun assertSyntheticNetworkLocationOnly(locator: String) {
        if (!locator.startsWith("http://") && !locator.startsWith("https://")) return
        val host = URI(locator).host
        check(host != null && host.endsWith(".invalid")) {
            "C18 fixture contains non-synthetic network location: $locator"
        }
    }

    private fun loadManifestV1(): List<ManifestFixture> {
        val lines = readResource(MANIFEST_FILE)
            .lineSequence()
            .filter(String::isNotBlank)
            .toList()
        check(lines.isNotEmpty() && lines.first() == SCHEMA_MARKER) {
            "Unsupported C18 catch-up manifest schema"
        }
        check(lines.size >= 2 && lines[1] == MANIFEST_HEADER) {
            "Unexpected C18 catch-up manifest header"
        }
        return lines.drop(2).mapIndexed { index, line ->
            val columns = line.split('\t')
            check(columns.size == MANIFEST_COLUMN_COUNT) {
                "Invalid C18 manifest row ${index + 3}: expected $MANIFEST_COLUMN_COUNT columns, found ${columns.size}"
            }
            ManifestFixture(
                id = columns[0],
                path = columns[1],
                category = columns[2],
                disposition = SupportDisposition.valueOf(columns[3]),
                expectedA = columns[4],
                expectedB = columns[5],
                expectedC = columns[6],
                safeExpectation = columns[7],
                reference = columns[8],
            ).also { row ->
                check(row.id.isNotBlank())
                check(row.path.endsWith(".case"))
                check(!row.path.startsWith('/') && ".." !in row.path && '\\' !in row.path)
                check(row.expectedA.isNotBlank() && row.expectedB.isNotBlank() && row.expectedC.isNotBlank())
                check(row.reference.isNotBlank())
            }
        }
    }

    private fun parseCase(id: String, raw: String): CatchupCase {
        val properties = Properties().apply {
            raw.reader().use(::load)
        }
        fun required(name: String): String = checkNotNull(properties.getProperty(name)) {
            "Missing C18 case property '$name' for $id"
        }.trim()
        fun nullable(name: String): String? = required(name).takeUnless { it == "<null>" || it == "-" }
        fun nullableLong(name: String): Long? = nullable(name)?.toLong()
        fun nullableInt(name: String): Int? = nullable(name)?.toInt()

        val fixture = CatchupCase(
            id = id,
            caseVersion = required("case_version").toInt(),
            provider = when (required("provider")) {
                "m3u" -> Provider.M3U
                "xtream" -> Provider.XTREAM
                "reference-only" -> Provider.REFERENCE_ONLY
                else -> error("Unknown C18 provider for $id")
            },
            intentKind = required("intent"),
            nowMs = required("now_ms").toLong(),
            startMs = required("start_ms").toLong(),
            endMs = nullableLong("end_ms"),
            live = required("live"),
            mode = nullable("mode"),
            source = nullable("source"),
            days = nullableInt("days"),
            correction = nullable("correction"),
            expectedStatus = required("expected_status"),
            expectedTransport = nullable("expected_transport"),
            expectedGranularityMs = nullableLong("expected_granularity_ms"),
            expectedMediaOffsetMs = nullableLong("expected_media_offset_ms"),
            expectedQueryKeys = nullable("expected_query_keys")
                ?.split(',')
                ?.filter(String::isNotBlank)
                .orEmpty(),
            expectedCorrectionMs = nullableLong("expected_correction_ms"),
            expectedWindowStartMs = nullableLong("expected_window_start_ms"),
            expectedWindowEndMs = nullableLong("expected_window_end_ms"),
            expectedProgrammeStartMs = nullableLong("expected_programme_start_ms"),
            expectedProgrammeEndMs = nullableLong("expected_programme_end_ms"),
            expectedInitialPositionMs = nullableLong("expected_initial_position_ms"),
            redactionProbe = nullable("redaction_probe"),
        )
        check(fixture.caseVersion == 1) { "Unsupported C18 case version for $id" }
        check(fixture.intentKind == "programme" || fixture.intentKind == "position") {
            "Unknown C18 intent kind for $id"
        }
        return fixture
    }

    private fun CatchupCase.intent(): PlaybackIntent = when (intentKind) {
        "programme" -> PlaybackIntent.CatchupProgram(
            channelId = "channel-$id",
            programmeId = "programme-$id",
            startEpochMillis = startMs,
            endEpochMillis = checkNotNull(endMs) { "Programme fixture requires end_ms: $id" },
        )
        "position" -> PlaybackIntent.CatchupPosition(
            channelId = "channel-$id",
            positionEpochMillis = startMs,
        )
        else -> error("Unsupported C18 intent kind: $intentKind")
    }

    private fun discoverCasePaths(): Set<String> {
        val rootUrl = checkNotNull(javaClass.classLoader.getResource(CORPUS_ROOT)) {
            "Missing C18 compatibility resource root: $CORPUS_ROOT"
        }
        check(rootUrl.protocol == "file") {
            "C18 corpus must be a file-backed JVM test resource, found protocol=${rootUrl.protocol}"
        }
        val rootPath = Paths.get(rootUrl.toURI())
        val result = linkedSetOf<String>()
        Files.walk(rootPath).use { paths ->
            paths
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".case") }
                .forEach { path ->
                    result += rootPath.relativize(path).toString().replace('\\', '/')
                }
        }
        return result
    }

    private fun readResource(relativePath: String): String {
        val fullPath = "$CORPUS_ROOT/$relativePath"
        return openResource(fullPath).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
    }

    private fun openResource(fullPath: String): InputStream =
        checkNotNull(javaClass.classLoader.getResourceAsStream(fullPath)) {
            "Missing C18 compatibility resource: $fullPath"
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun PlaybackArchiveUnavailableReason.toCorpusName(): String = when (this) {
        PlaybackArchiveUnavailableReason.OutsideRetention -> "OUTSIDE_RETENTION"
        PlaybackArchiveUnavailableReason.UnsupportedMode -> "UNSUPPORTED_MODE"
        PlaybackArchiveUnavailableReason.InvalidMetadata -> "INVALID_METADATA"
    }

    private data class CandidateResult(
        val descriptor: SanitizedPlaybackDescriptor,
        val locator: String?,
        val diagnostics: String,
    )

    private data class SanitizedPlaybackDescriptor(
        val status: String,
        val transport: String? = null,
        val granularityMs: Long? = null,
        val mediaOffsetMs: Long? = null,
        val queryKeys: List<String> = emptyList(),
        val correctionMs: Long? = null,
        val windowStartMs: Long? = null,
        val windowEndMs: Long? = null,
        val programmeStartMs: Long? = null,
        val programmeEndMs: Long? = null,
        val initialPositionMs: Long? = null,
        val playAsLive: Boolean? = null,
    )

    private data class ManifestFixture(
        val id: String,
        val path: String,
        val category: String,
        val disposition: SupportDisposition,
        val expectedA: String,
        val expectedB: String,
        val expectedC: String,
        val safeExpectation: String,
        val reference: String,
    )

    private data class CatchupCase(
        val id: String,
        val caseVersion: Int,
        val provider: Provider,
        val intentKind: String,
        val nowMs: Long,
        val startMs: Long,
        val endMs: Long?,
        val live: String,
        val mode: String?,
        val source: String?,
        val days: Int?,
        val correction: String?,
        val expectedStatus: String,
        val expectedTransport: String?,
        val expectedGranularityMs: Long?,
        val expectedMediaOffsetMs: Long?,
        val expectedQueryKeys: List<String>,
        val expectedCorrectionMs: Long?,
        val expectedWindowStartMs: Long?,
        val expectedWindowEndMs: Long?,
        val expectedProgrammeStartMs: Long?,
        val expectedProgrammeEndMs: Long?,
        val expectedInitialPositionMs: Long?,
        val redactionProbe: String?,
    )

    private enum class Provider {
        M3U,
        XTREAM,
        REFERENCE_ONLY,
    }

    private enum class SupportDisposition {
        SUPPORTED,
        IGNORED_SAFE,
        REJECTED,
        NOT_IMPLEMENTED,
    }

    private companion object {
        const val CORPUS_ROOT = "compatibility/catchup"
        const val MANIFEST_FILE = "manifest-v1.tsv"
        const val SCHEMA_MARKER = "# schema_version=1"
        const val MANIFEST_HEADER =
            "id\tpath\tcategory\tdisposition\texpected_a\texpected_b\texpected_c\tsafe_expectation\treference"
        const val MANIFEST_COLUMN_COUNT = 9
        const val MAX_CASE_BYTES = 64 * 1024
    }
}
