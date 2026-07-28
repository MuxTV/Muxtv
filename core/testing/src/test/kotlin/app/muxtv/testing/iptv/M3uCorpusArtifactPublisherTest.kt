package app.muxtv.testing.iptv

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Comparator
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class M3uCorpusArtifactPublisherTest {
    private lateinit var root: Path

    @Before
    fun setUp() {
        root = Files.createTempDirectory("muxtv-corpus-artifacts-")
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
    fun `publishes deterministic playlist and canonical manifest pair`() {
        val result = M3uCorpusArtifactPublisher().publish(
            M3uCorpusArtifactRequest(
                spec = spec(seed = 42L),
                outputDirectory = root,
            ),
        )

        val expectedBase = "muxtv-m3u-small-1k-seed-42-$SOURCE_COMMIT"
        assertThat(result.playlistPath.fileName.toString()).isEqualTo("$expectedBase.m3u8")
        assertThat(result.manifestPath.fileName.toString()).isEqualTo("$expectedBase.manifest.json")
        assertThat(Files.isRegularFile(result.playlistPath)).isTrue()
        assertThat(Files.isRegularFile(result.manifestPath)).isTrue()

        val playlistBytes = Files.readAllBytes(result.playlistPath)
        assertThat(result.manifest.utf8ByteCount).isEqualTo(playlistBytes.size.toLong())
        assertThat(result.manifest.sha256).isEqualTo(playlistBytes.sha256())

        val manifestJson = Files.readString(result.manifestPath)
        assertThat(manifestJson).contains("\"profile\": \"small-1k\"")
        assertThat(manifestJson).contains("\"seed\": 42")
        assertThat(manifestJson).contains("\"sourceCommit\": \"$SOURCE_COMMIT\"")
        assertThat(manifestJson).contains("\"playlistSha256\": \"${result.manifest.sha256}\"")
        assertThat(manifestJson).endsWith("\n")
        assertThat(manifestJson).doesNotContain("\r")
        assertThat(Files.list(root).use { it.count() }).isEqualTo(2L)
    }

    @Test
    fun `negative seed has a filesystem safe deterministic token`() {
        val result = M3uCorpusArtifactPublisher().publish(
            M3uCorpusArtifactRequest(
                spec = spec(seed = Long.MIN_VALUE),
                outputDirectory = root,
            ),
        )

        assertThat(result.playlistPath.fileName.toString())
            .contains("seed-neg-9223372036854775808")
        assertThat(result.playlistPath.fileName.toString()).doesNotContain("seed--")
    }

    @Test
    fun `refuses implicit overwrite and preserves the existing pair`() {
        val publisher = M3uCorpusArtifactPublisher()
        val request = M3uCorpusArtifactRequest(spec = spec(seed = 7L), outputDirectory = root)
        val existing = publisher.publish(request)
        val oldPlaylist = Files.readAllBytes(existing.playlistPath)
        val oldManifest = Files.readAllBytes(existing.manifestPath)

        val error = assertThrows(M3uCorpusArtifactException::class.java) {
            publisher.publish(request)
        }

        assertThat(error.reason).isEqualTo(M3uCorpusArtifactFailureReason.TargetExists)
        assertThat(error.message).doesNotContain(root.toString())
        assertThat(Files.readAllBytes(existing.playlistPath)).isEqualTo(oldPlaylist)
        assertThat(Files.readAllBytes(existing.manifestPath)).isEqualTo(oldManifest)
        assertThat(Files.list(root).use { it.count() }).isEqualTo(2L)
    }

    @Test
    fun `explicit overwrite replaces both artifacts without temporary residue`() {
        val publisher = M3uCorpusArtifactPublisher()
        val initialRequest = M3uCorpusArtifactRequest(spec = spec(seed = 9L), outputDirectory = root)
        val initial = publisher.publish(initialRequest)
        Files.writeString(initial.playlistPath, "old-playlist")
        Files.writeString(initial.manifestPath, "old-manifest")

        val replaced = publisher.publish(initialRequest.copy(overwrite = true))

        assertThat(Files.readString(replaced.playlistPath)).isNotEqualTo("old-playlist")
        assertThat(Files.readString(replaced.manifestPath)).isNotEqualTo("old-manifest")
        assertThat(Files.list(root).use { it.count() }).isEqualTo(2L)
    }

    @Test
    fun `failed second publish removes a new partial pair`() {
        val publisher = M3uCorpusArtifactPublisher.forTesting(
            moveFile = FailOnceOnManifestPublishMove(),
        )
        val request = M3uCorpusArtifactRequest(spec = spec(seed = 11L), outputDirectory = root)

        val error = assertThrows(M3uCorpusArtifactException::class.java) {
            publisher.publish(request)
        }

        assertThat(error.reason).isEqualTo(M3uCorpusArtifactFailureReason.PublishFailed)
        assertThat(error.message).doesNotContain(root.toString())
        assertThat(Files.list(root).use { it.count() }).isEqualTo(0L)
    }

    @Test
    fun `failed overwrite restores the complete previous pair`() {
        val normalPublisher = M3uCorpusArtifactPublisher()
        val request = M3uCorpusArtifactRequest(spec = spec(seed = 13L), outputDirectory = root)
        val existing = normalPublisher.publish(request)
        val oldPlaylist = "known-old-playlist".toByteArray()
        val oldManifest = "known-old-manifest".toByteArray()
        Files.write(existing.playlistPath, oldPlaylist)
        Files.write(existing.manifestPath, oldManifest)

        val failingPublisher = M3uCorpusArtifactPublisher.forTesting(
            moveFile = FailOnceOnManifestPublishMove(),
        )
        val error = assertThrows(M3uCorpusArtifactException::class.java) {
            failingPublisher.publish(request.copy(overwrite = true))
        }

        assertThat(error.reason).isEqualTo(M3uCorpusArtifactFailureReason.PublishFailed)
        assertThat(Files.readAllBytes(existing.playlistPath)).isEqualTo(oldPlaylist)
        assertThat(Files.readAllBytes(existing.manifestPath)).isEqualTo(oldManifest)
        assertThat(Files.list(root).use { it.count() }).isEqualTo(2L)
    }

    @Test
    fun `request and result diagnostics redact filesystem paths`() {
        val request = M3uCorpusArtifactRequest(spec = spec(seed = 17L), outputDirectory = root)
        val result = M3uCorpusArtifactPublisher().publish(request)

        assertThat(request.toString()).doesNotContain(root.toString())
        assertThat(request.toString()).contains("outputDirectory=<redacted>")
        assertThat(result.toString()).doesNotContain(root.toString())
        assertThat(result.toString()).contains("playlistPath=<redacted>")
        assertThat(result.toString()).contains("manifestPath=<redacted>")
    }

    private fun spec(seed: Long) = M3uCorpusSpec(
        profile = M3uCorpusProfile.SMALL_1K,
        seed = seed,
        sourceCommit = SOURCE_COMMIT,
    )

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(this)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class FailOnceOnManifestPublishMove : (Path, Path) -> Unit {
        private var failed = false

        override fun invoke(source: Path, target: Path) {
            if (!failed && target.fileName.toString().endsWith(".manifest.json")) {
                failed = true
                throw IllegalStateException("synthetic manifest publish failure")
            }
            Files.move(source, target)
        }
    }

    private companion object {
        const val SOURCE_COMMIT = "0123456789abcdef0123456789abcdef01234567"
    }
}
