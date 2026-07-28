package app.muxtv.testing.iptv

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class M3uCorpusManifestJsonWriterTest {
    @Test
    fun `writes canonical stable json with explicit schema and trailing newline`() {
        val manifest = generateManifest(seed = 42L)
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()

        M3uCorpusManifestJsonWriter.write(manifest, first)
        M3uCorpusManifestJsonWriter.write(manifest, second)

        assertThat(second.toByteArray()).isEqualTo(first.toByteArray())
        val expected = """
            {
              "manifestSchemaVersion": 1,
              "generatorSchemaVersion": ${manifest.schemaVersion},
              "profile": "small-1k",
              "seed": 42,
              "sourceCommit": "${manifest.sourceCommit}",
              "expectedParsedEntries": ${manifest.expectedParsedEntries},
              "expectedSkippedEntries": ${manifest.expectedSkippedEntries},
              "expectedWarningCount": ${manifest.expectedWarningCount},
              "expectedDuplicateIdentities": ${manifest.expectedDuplicateIdentities},
              "expectedUniqueIdentities": ${manifest.expectedUniqueIdentities},
              "playlistUtf8ByteCount": ${manifest.utf8ByteCount},
              "playlistSha256": "${manifest.sha256}"
            }
        """.trimIndent() + "\n"

        assertThat(first.toString(Charsets.UTF_8)).isEqualTo(expected)
        assertThat(first.toByteArray()).doesNotContain('\r'.code.toByte())
    }

    @Test
    fun `writer flushes but does not close caller owned stream`() {
        val output = TrackingOutputStream()

        M3uCorpusManifestJsonWriter.write(generateManifest(seed = 7L), output)

        assertThat(output.flushCount).isGreaterThan(0)
        assertThat(output.closed).isFalse()
        output.write(0)
    }

    @Test
    fun `profiles expose stable artifact ids`() {
        assertThat(M3uCorpusProfile.SMALL_1K.artifactId).isEqualTo("small-1k")
        assertThat(M3uCorpusProfile.MEDIUM_10K.artifactId).isEqualTo("medium-10k")
        assertThat(M3uCorpusProfile.LARGE_50K.artifactId).isEqualTo("large-50k")
    }

    @Test
    fun `source commit must be a full lowercase git sha`() {
        listOf(
            "",
            "abc1234",
            "0123456789ABCDEF0123456789ABCDEF01234567",
            "not-a-source-commit-value-xxxxxxxxxxxx",
            "0123456789abcdef0123456789abcdef0123456 ",
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                M3uCorpusSpec(
                    profile = M3uCorpusProfile.SMALL_1K,
                    seed = 1L,
                    sourceCommit = invalid,
                )
            }
        }
    }

    private fun generateManifest(seed: Long): M3uCorpusManifest {
        val playlist = ByteArrayOutputStream()
        return DeterministicM3uCorpusGenerator.generate(
            spec = M3uCorpusSpec(
                profile = M3uCorpusProfile.SMALL_1K,
                seed = seed,
                sourceCommit = TEST_SOURCE_COMMIT,
            ),
            output = playlist,
        )
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed: Boolean = false
            private set
        var flushCount: Int = 0
            private set

        override fun flush() {
            flushCount += 1
            super.flush()
        }

        override fun close() {
            closed = true
            super.close()
        }
    }

    private companion object {
        const val TEST_SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
