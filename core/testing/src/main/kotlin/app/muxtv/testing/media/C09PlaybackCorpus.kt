package app.muxtv.testing.media

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class C09PlaybackTransport { MPEG_TS, HLS }

enum class C09PlaybackCodec { AVC, HEVC }

data class C09PlaybackFixture(
    val id: String,
    val transport: C09PlaybackTransport,
    val codec: C09PlaybackCodec,
    val widthPixels: Int,
    val heightPixels: Int,
    val framesPerSecond: Int,
    val entrypoint: String,
    val supportingFiles: List<String> = emptyList(),
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9-]{0,63}"))) { "fixture id is invalid" }
        require(widthPixels > 0 && heightPixels > 0) { "fixture dimensions must be positive" }
        require(framesPerSecond > 0) { "fixture frame rate must be positive" }
        require(entrypoint.isSafeCorpusPath()) { "fixture entrypoint is invalid" }
        require(supportingFiles.all(String::isSafeCorpusPath)) { "fixture supporting file is invalid" }
    }
}

data class C09MaterializedPlaybackFixture(
    val fixture: C09PlaybackFixture,
    val entrypoint: File,
)

/**
 * Immutable synthetic decoder corpus for C09 renderer-policy characterization.
 *
 * The corpus is provider-neutral, video-only and generated from FFmpeg `testsrc2`. Every resource
 * is pinned by SHA-256 before it can be materialized for a host/device evidence run.
 */
object C09PlaybackCorpus {
    const val manifestSha256 = "c0a7c7a9b8f09f5c33b85519d82e2996e1d2cbc62ad8339767f44fa7dc71fc3c"
    const val contentSha256 = "10c41a6ef0de1834fcf6658d5c8edddf1abb9e52d933553fb3c108220ccb36b5"

    val fixtures: List<C09PlaybackFixture> = listOf(
        C09PlaybackFixture(
            id = "raw-avc-360p30",
            transport = C09PlaybackTransport.MPEG_TS,
            codec = C09PlaybackCodec.AVC,
            widthPixels = 640,
            heightPixels = 360,
            framesPerSecond = 30,
            entrypoint = "avc-360p30.ts",
        ),
        C09PlaybackFixture(
            id = "raw-avc-720p50",
            transport = C09PlaybackTransport.MPEG_TS,
            codec = C09PlaybackCodec.AVC,
            widthPixels = 1_280,
            heightPixels = 720,
            framesPerSecond = 50,
            entrypoint = "avc-720p50.ts",
        ),
        C09PlaybackFixture(
            id = "raw-hevc-360p30",
            transport = C09PlaybackTransport.MPEG_TS,
            codec = C09PlaybackCodec.HEVC,
            widthPixels = 640,
            heightPixels = 360,
            framesPerSecond = 30,
            entrypoint = "hevc-360p30.ts",
        ),
        C09PlaybackFixture(
            id = "raw-hevc-720p50",
            transport = C09PlaybackTransport.MPEG_TS,
            codec = C09PlaybackCodec.HEVC,
            widthPixels = 1_280,
            heightPixels = 720,
            framesPerSecond = 50,
            entrypoint = "hevc-720p50.ts",
        ),
        C09PlaybackFixture(
            id = "hls-avc-360p30",
            transport = C09PlaybackTransport.HLS,
            codec = C09PlaybackCodec.AVC,
            widthPixels = 640,
            heightPixels = 360,
            framesPerSecond = 30,
            entrypoint = "avc-hls.m3u8",
            supportingFiles = listOf("avc-360p30.ts"),
        ),
        C09PlaybackFixture(
            id = "hls-hevc-360p30",
            transport = C09PlaybackTransport.HLS,
            codec = C09PlaybackCodec.HEVC,
            widthPixels = 640,
            heightPixels = 360,
            framesPerSecond = 30,
            entrypoint = "hevc-hls.m3u8",
            supportingFiles = listOf("hevc-360p30.ts"),
        ),
    )

    /** Returns bounded, secret-free integrity failures. Empty means the exact corpus is intact. */
    fun verifyIntegrity(): List<String> {
        val issues = ArrayList<String>()
        val manifest = readResource(MANIFEST_PATH)
        if (manifest == null) {
            issues += "missing:$MANIFEST_PATH"
        } else if (manifest.sha256() != manifestSha256) {
            issues += "sha256:$MANIFEST_PATH"
        }

        val content = linkedMapOf<String, ByteArray>()
        EXPECTED_FILES.forEach { (path, expectedSha256) ->
            val bytes = readResource(path)
            if (bytes == null) {
                issues += "missing:$path"
            } else {
                content[path] = bytes
                if (bytes.sha256() != expectedSha256) issues += "sha256:$path"
            }
        }

        val referencedFiles = fixtures
            .flatMap { fixture -> listOf(fixture.entrypoint) + fixture.supportingFiles }
            .toSet()
        if (referencedFiles != EXPECTED_FILES.keys) issues += "fixture-file-set"

        if (content.size == EXPECTED_FILES.size && content.contentSha256() != contentSha256) {
            issues += "content-sha256"
        }
        return issues.take(MAX_INTEGRITY_ISSUES)
    }

    /**
     * Copies the verified corpus to one directory so HLS relative segment references remain valid.
     * Existing files are replaced only after the packaged resources have passed integrity checks.
     */
    fun materialize(directory: File): List<C09MaterializedPlaybackFixture> {
        val issues = verifyIntegrity()
        require(issues.isEmpty()) { "C09 playback corpus integrity failed: ${issues.joinToString(",")}" }
        require(directory.exists() || directory.mkdirs()) { "unable to create C09 corpus directory" }
        require(directory.isDirectory) { "C09 corpus destination must be a directory" }

        EXPECTED_FILES.keys.forEach { path ->
            val bytes = checkNotNull(readResource(path))
            File(directory, path).writeBytes(bytes)
        }
        File(directory, MANIFEST_PATH).writeBytes(checkNotNull(readResource(MANIFEST_PATH)))

        return fixtures.map { fixture ->
            C09MaterializedPlaybackFixture(
                fixture = fixture,
                entrypoint = File(directory, fixture.entrypoint),
            )
        }
    }

    private fun readResource(path: String): ByteArray? =
        C09PlaybackCorpus::class.java.classLoader
            ?.getResourceAsStream("$RESOURCE_ROOT/$path")
            ?.use { it.readBytes() }

    private fun Map<String, ByteArray>.contentSha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        toSortedMap().forEach { (path, bytes) ->
            digest.update(path.toByteArray(Charsets.UTF_8))
            digest.update(0)
            digest.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(bytes.size.toLong()).array())
            digest.update(bytes)
        }
        return digest.digest().toHex()
    }

    private const val RESOURCE_ROOT = "app/muxtv/testing/media/c09"
    private const val MANIFEST_PATH = "manifest-v1.tsv"
    private const val MAX_INTEGRITY_ISSUES = 16

    private val EXPECTED_FILES = linkedMapOf(
        "avc-360p30.ts" to "f18f019088523bdd9a8cf9521d316fdfa140c84563336ca15f9cd41bc9c038fd",
        "avc-720p50.ts" to "39759e22e38ce40a6c046e33b1780079d9cb4bd2bec297395731d94b43a5738f",
        "avc-hls.m3u8" to "80a2bbcdce538eab68d4e5ef51031ceaab8e5d2c0ef958b6fd2f256d5411c327",
        "hevc-360p30.ts" to "9276b5b278de9dbb90ba1fcdadb6af3748f35d1345ab8339f4de2feac5052142",
        "hevc-720p50.ts" to "8c9855b58fb653d554298c6263edf2b1e608cb411f8913273786873bf06d29dc",
        "hevc-hls.m3u8" to "648128b2140128e651ee18bd32824323a6872e341b051f9cbab06e548fec6f78",
    )
}

private fun String.isSafeCorpusPath(): Boolean =
    matches(Regex("[a-z0-9][a-z0-9.-]{0,63}")) && ".." !in this

private fun ByteArray.sha256(): String =
    MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }
