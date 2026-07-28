package app.muxtv.testing.iptv

import app.muxtv.catalog.ingest.M3uEntry
import app.muxtv.catalog.ingest.M3uParseSink
import app.muxtv.catalog.ingest.M3uWarning
import app.muxtv.catalog.ingest.StreamingM3uParser
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DeterministicM3uCorpusGeneratorTest {
    @Test
    fun `same profile and seed produce byte-identical corpus and manifest`() {
        val firstOutput = ByteArrayOutputStream()
        val secondOutput = ByteArrayOutputStream()
        val spec = M3uCorpusSpec(
            profile = M3uCorpusProfile.SMALL_1K,
            seed = 42L,
            sourceCommit = TEST_SOURCE_COMMIT,
        )

        val first = DeterministicM3uCorpusGenerator.generate(spec, firstOutput)
        val second = DeterministicM3uCorpusGenerator.generate(spec, secondOutput)

        assertThat(secondOutput.toByteArray()).isEqualTo(firstOutput.toByteArray())
        assertThat(second).isEqualTo(first)
        assertThat(first.profile).isEqualTo(M3uCorpusProfile.SMALL_1K)
        assertThat(first.seed).isEqualTo(42L)
        assertThat(first.sourceCommit).isEqualTo(TEST_SOURCE_COMMIT)
        assertThat(first.expectedParsedEntries).isEqualTo(1_000)
        assertThat(first.expectedSkippedEntries).isEqualTo(1)
        assertThat(first.expectedWarningCount).isEqualTo(2)
        assertThat(first.expectedDuplicateIdentities).isGreaterThan(0)
        assertThat(first.utf8ByteCount).isEqualTo(firstOutput.size().toLong())
        assertThat(first.sha256).hasLength(64)
    }

    @Test
    fun `different seeds change output digest without changing profile expectations`() {
        val first = generate(seed = 1L)
        val second = generate(seed = 2L)

        assertThat(second.manifest.sha256).isNotEqualTo(first.manifest.sha256)
        assertThat(second.bytes).isNotEqualTo(first.bytes)
        assertThat(second.manifest.expectedParsedEntries)
            .isEqualTo(first.manifest.expectedParsedEntries)
        assertThat(second.manifest.expectedSkippedEntries)
            .isEqualTo(first.manifest.expectedSkippedEntries)
        assertThat(second.manifest.expectedDuplicateIdentities)
            .isEqualTo(first.manifest.expectedDuplicateIdentities)
    }

    @Test
    fun `small corpus manifest matches the real streaming parser report`() = runTest {
        val generated = generate(seed = 7L)
        val entries = mutableListOf<M3uEntry>()
        val warnings = mutableListOf<M3uWarning>()

        val report = StreamingM3uParser().parse(
            input = ByteArrayInputStream(generated.bytes),
            sink = object : M3uParseSink {
                override suspend fun onEntry(entry: M3uEntry) {
                    entries += entry
                }

                override suspend fun onWarning(warning: M3uWarning) {
                    warnings += warning
                }
            },
        )

        assertThat(report.parsedEntries).isEqualTo(generated.manifest.expectedParsedEntries)
        assertThat(report.skippedEntries).isEqualTo(generated.manifest.expectedSkippedEntries)
        assertThat(report.warningCount).isEqualTo(generated.manifest.expectedWarningCount)
        assertThat(entries).hasSize(generated.manifest.expectedParsedEntries)
        assertThat(warnings).hasSize(generated.manifest.expectedWarningCount)
        assertThat(entries.mapNotNull(M3uEntry::tvgId).distinct().size)
            .isEqualTo(
                generated.manifest.expectedParsedEntries -
                    generated.manifest.expectedDuplicateIdentities,
            )
    }

    @Test
    fun `corpus uses only reserved synthetic identities and contains no credential fixtures`() {
        val text = generate(seed = 99L).bytes.toString(Charsets.UTF_8)

        assertThat(text).contains("stream.example")
        assertThat(text).contains("images.example")
        assertThat(text).contains("epg.example")
        assertThat(text).doesNotContain("Authorization")
        assertThat(text).doesNotContain("Bearer ")
        assertThat(text).doesNotContain("token=")
        assertThat(text).doesNotContain("password")
        assertThat(text).doesNotContain("localhost")
        assertThat(text).doesNotContain("127.0.0.1")
    }

    @Test
    fun `all named profiles expose stable sizes and bounded expectations`() {
        assertThat(M3uCorpusProfile.SMALL_1K.entryCount).isEqualTo(1_000)
        assertThat(M3uCorpusProfile.MEDIUM_10K.entryCount).isEqualTo(10_000)
        assertThat(M3uCorpusProfile.LARGE_50K.entryCount).isEqualTo(50_000)

        M3uCorpusProfile.entries.forEach { profile ->
            assertThat(profile.expectedDuplicateIdentities).isAtLeast(1)
            assertThat(profile.expectedDuplicateIdentities).isLessThan(profile.entryCount)
        }
    }

    private fun generate(seed: Long): GeneratedCorpus {
        val output = ByteArrayOutputStream()
        val manifest = DeterministicM3uCorpusGenerator.generate(
            spec = M3uCorpusSpec(
                profile = M3uCorpusProfile.SMALL_1K,
                seed = seed,
                sourceCommit = TEST_SOURCE_COMMIT,
            ),
            output = output,
        )
        return GeneratedCorpus(output.toByteArray(), manifest)
    }

    private data class GeneratedCorpus(
        val bytes: ByteArray,
        val manifest: M3uCorpusManifest,
    )

    private companion object {
        const val TEST_SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
