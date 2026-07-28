package app.muxtv.testing.iptv

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import org.junit.After
import org.junit.Before
import org.junit.Test

class M3uCorpusCommandTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("muxtv-corpus-command-")
    }

    @After
    fun tearDown() {
        if (Files.exists(root)) {
            Files.walk(root).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    @Test
    fun `valid invocation publishes a deterministic pair and prints only safe summary`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = M3uCorpusCommand().run(
            args = validArgs(seed = 42L),
            stdout = stdout,
            stderr = stderr,
        )

        assertThat(exitCode).isEqualTo(M3uCorpusCommand.EXIT_SUCCESS)
        assertThat(stderr.toString()).isEmpty()
        assertThat(Files.list(root).use { it.count() }).isEqualTo(2L)
        assertThat(stdout.toString()).contains("profile=small-1k")
        assertThat(stdout.toString()).contains("seed=42")
        assertThat(stdout.toString()).contains("parsed=1000")
        assertThat(stdout.toString()).contains("skipped=1")
        assertThat(stdout.toString()).contains("warnings=2")
        assertThat(stdout.toString()).contains("duplicates=10")
        assertThat(stdout.toString()).contains("unique=990")
        assertThat(stdout.toString()).contains("playlist=muxtv-m3u-small-1k-seed-42-")
        assertThat(stdout.toString()).contains("manifest=muxtv-m3u-small-1k-seed-42-")
        assertThat(stdout.toString()).contains("sha256=")
        assertThat(stdout.toString()).doesNotContain(root.toString())
        assertThat(stdout.toString()).doesNotContain("outputDirectory")
    }

    @Test
    fun `help succeeds without filesystem mutation`() {
        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val exitCode = M3uCorpusCommand().run(
            args = listOf("--help"),
            stdout = stdout,
            stderr = stderr,
        )

        assertThat(exitCode).isEqualTo(M3uCorpusCommand.EXIT_SUCCESS)
        assertThat(stdout.toString()).contains("--profile")
        assertThat(stdout.toString()).contains("--source-commit")
        assertThat(stdout.toString()).contains("--overwrite")
        assertThat(stderr.toString()).isEmpty()
        assertThat(Files.list(root).use { it.count() }).isEqualTo(0L)
    }

    @Test
    fun `invalid arguments return stable usage failure without echoing supplied values`() {
        val secretLikeValue = "https://secret.example/live.m3u8?token=do-not-print"
        val cases = listOf(
            emptyList(),
            listOf("--profile", "small-1k"),
            validArgs().dropLast(2),
            validArgs() + listOf("--profile", "large-50k"),
            validArgs().map { if (it == "small-1k") "unknown-profile" else it },
            validArgs().map { if (it == "7") "not-a-number" else it },
            validArgs().map { if (it == SOURCE_COMMIT) SOURCE_COMMIT.uppercase() else it },
            validArgs() + listOf("--unknown", secretLikeValue),
        )

        cases.forEach { args ->
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val exitCode = M3uCorpusCommand().run(args, stdout, stderr)

            assertThat(exitCode).isEqualTo(M3uCorpusCommand.EXIT_USAGE)
            assertThat(stdout.toString()).isEmpty()
            assertThat(stderr.toString()).contains("Usage:")
            assertThat(stderr.toString()).doesNotContain(root.toString())
            assertThat(stderr.toString()).doesNotContain(secretLikeValue)
            assertThat(stderr.toString()).doesNotContain("unknown-profile")
            assertThat(stderr.toString()).doesNotContain("not-a-number")
        }
        assertThat(Files.list(root).use { it.count() }).isEqualTo(0L)
    }

    @Test
    fun `existing target maps to safe publish failure and overwrite replaces the pair`() {
        val command = M3uCorpusCommand()
        assertThat(command.run(validArgs(seed = 13L), StringBuilder(), StringBuilder()))
            .isEqualTo(M3uCorpusCommand.EXIT_SUCCESS)

        val oldFiles = Files.list(root).use { paths -> paths.toList() }
        oldFiles.forEach { path -> Files.writeString(path, "old-content") }

        val failureOutput = StringBuilder()
        val failureCode = command.run(validArgs(seed = 13L), StringBuilder(), failureOutput)

        assertThat(failureCode).isEqualTo(M3uCorpusCommand.EXIT_PUBLISH)
        assertThat(failureOutput.toString()).contains("already exists")
        assertThat(failureOutput.toString()).doesNotContain(root.toString())
        oldFiles.forEach { path -> assertThat(Files.readString(path)).isEqualTo("old-content") }

        val overwriteCode = command.run(
            validArgs(seed = 13L) + "--overwrite",
            StringBuilder(),
            StringBuilder(),
        )

        assertThat(overwriteCode).isEqualTo(M3uCorpusCommand.EXIT_SUCCESS)
        assertThat(Files.list(root).use { it.count() }).isEqualTo(2L)
        oldFiles.forEach { path -> assertThat(Files.readString(path)).isNotEqualTo("old-content") }
    }

    @Test
    fun `all named profiles are accepted through stable artifact ids without regenerating large corpora`() {
        val observedProfiles = mutableListOf<M3uCorpusProfile>()
        val command = M3uCorpusCommand.forTesting { request ->
            observedProfiles += request.spec.profile
            fakePair(request)
        }

        M3uCorpusProfile.entries.forEachIndexed { index, profile ->
            val stdout = StringBuilder()

            val exitCode = command.run(
                args = validArgs(
                    profile = profile.artifactId,
                    seed = index.toLong(),
                    outputDirectory = root.resolve(profile.artifactId),
                ),
                stdout = stdout,
                stderr = StringBuilder(),
            )

            assertThat(exitCode).isEqualTo(M3uCorpusCommand.EXIT_SUCCESS)
            assertThat(stdout.toString()).contains("profile=${profile.artifactId}")
        }

        assertThat(observedProfiles).containsExactlyElementsIn(M3uCorpusProfile.entries).inOrder()
        assertThat(Files.list(root).use { it.count() }).isEqualTo(0L)
    }

    private fun fakePair(request: M3uCorpusArtifactRequest): M3uCorpusArtifactPair {
        val profile = request.spec.profile
        val duplicates = profile.expectedDuplicateIdentities
        val manifest = M3uCorpusManifest(
            schemaVersion = DeterministicM3uCorpusGenerator.SCHEMA_VERSION,
            profile = profile,
            seed = request.spec.seed,
            sourceCommit = request.spec.sourceCommit,
            expectedParsedEntries = profile.entryCount,
            expectedSkippedEntries = 1,
            expectedWarningCount = 2,
            expectedDuplicateIdentities = duplicates,
            expectedUniqueIdentities = profile.entryCount - duplicates,
            utf8ByteCount = 1,
            sha256 = "0".repeat(64),
        )
        return M3uCorpusArtifactPair(
            playlistPath = request.outputDirectory.resolve("fixture-${profile.artifactId}.m3u8"),
            manifestPath = request.outputDirectory.resolve("fixture-${profile.artifactId}.manifest.json"),
            manifest = manifest,
        )
    }

    private fun validArgs(
        profile: String = "small-1k",
        seed: Long = 7L,
        outputDirectory: Path = root,
    ): List<String> = listOf(
        "--profile",
        profile,
        "--seed",
        seed.toString(),
        "--source-commit",
        SOURCE_COMMIT,
        "--output",
        outputDirectory.toString(),
    )

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
