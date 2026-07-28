package app.muxtv.testing.iptv

import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import kotlin.math.max

enum class M3uCorpusProfile(
    val entryCount: Int,
) {
    SMALL_1K(1_000),
    MEDIUM_10K(10_000),
    LARGE_50K(50_000),
    ;

    val expectedDuplicateIdentities: Int
        get() = max(1, entryCount / DUPLICATE_INTERVAL)

    internal fun identityIndex(entryIndex: Int): Int {
        require(entryIndex in 0 until entryCount)
        return if (entryIndex % DUPLICATE_INTERVAL == DUPLICATE_INTERVAL - 1) {
            entryIndex - 1
        } else {
            entryIndex
        }
    }

    private companion object {
        const val DUPLICATE_INTERVAL = 100
    }
}

data class M3uCorpusSpec(
    val profile: M3uCorpusProfile,
    val seed: Long,
    val sourceCommit: String,
) {
    init {
        require(sourceCommit.isNotBlank())
        require(sourceCommit.length <= MAX_SOURCE_COMMIT_CHARACTERS)
        require(sourceCommit.none(Char::isWhitespace))
    }

    private companion object {
        const val MAX_SOURCE_COMMIT_CHARACTERS = 128
    }
}

data class M3uCorpusManifest(
    val schemaVersion: Int,
    val profile: M3uCorpusProfile,
    val seed: Long,
    val sourceCommit: String,
    val expectedParsedEntries: Int,
    val expectedSkippedEntries: Int,
    val expectedWarningCount: Int,
    val expectedDuplicateIdentities: Int,
    val expectedUniqueIdentities: Int,
    val utf8ByteCount: Long,
    val sha256: String,
) {
    init {
        require(schemaVersion > 0)
        require(sourceCommit.isNotBlank())
        require(expectedParsedEntries > 0)
        require(expectedSkippedEntries >= 0)
        require(expectedWarningCount >= 0)
        require(expectedDuplicateIdentities in 0 until expectedParsedEntries)
        require(expectedUniqueIdentities == expectedParsedEntries - expectedDuplicateIdentities)
        require(utf8ByteCount > 0)
        require(sha256.matches(Regex("[0-9a-f]{64}")))
    }
}

/**
 * Generates provider-neutral M3U bytes directly into the caller-owned stream.
 *
 * The implementation keeps only one logical line in memory, uses reserved `.example` hosts and
 * returns a manifest derived from the exact UTF-8 bytes written to [output]. The stream is flushed
 * but never closed.
 */
object DeterministicM3uCorpusGenerator {
    const val SCHEMA_VERSION: Int = 1

    fun generate(
        spec: M3uCorpusSpec,
        output: OutputStream,
    ): M3uCorpusManifest {
        val digest = MessageDigest.getInstance("SHA-256")
        val random = Random(spec.seed)
        var byteCount = 0L
        var lineNumber = 0L

        fun writeLine(value: String) {
            val lineEnding = if ((lineNumber + spec.seed).floorMod(MIXED_LINE_ENDING_INTERVAL) == 0L) {
                "\r\n"
            } else {
                "\n"
            }
            val bytes = (value + lineEnding).toByteArray(Charsets.UTF_8)
            output.write(bytes)
            digest.update(bytes)
            byteCount += bytes.size
            lineNumber += 1
        }

        writeLine("#EXTM3U url-tvg=\"https://epg.example/guide.xml\" corpus-schema=\"$SCHEMA_VERSION\"")
        // Deliberate parser warning: a supported option directive without a pending EXTINF record.
        writeLine("#EXTVLCOPT:http-user-agent=ignored-corpus-preamble")

        repeat(spec.profile.entryCount) { index ->
            val identityIndex = spec.profile.identityIndex(index)
            val randomSuffix = random.nextInt(RANDOM_SUFFIX_BOUND)
            val identity = "corpus-${spec.seed.toString(16)}-$identityIndex"
            val group = "Group ${index % GROUP_COUNT}"
            val longSuffix = if (index % LONG_METADATA_INTERVAL == 0) {
                "-" + "x".repeat(LONG_METADATA_CHARACTERS)
            } else {
                ""
            }
            val displayName = "Synthetic Channel $index-$randomSuffix$longSuffix"
            val channelNumber = index + 1

            writeLine(
                "#EXTINF:-1 " +
                    "tvg-id=\"$identity\" " +
                    "tvg-name=\"$displayName\" " +
                    "tvg-logo=\"https://images.example/corpus/$identity.png\" " +
                    "group-title=\"$group\" " +
                    "tvg-chno=\"$channelNumber\"," +
                    displayName,
            )

            if (index % USER_AGENT_INTERVAL == 0) {
                writeLine("#EXTVLCOPT:http-user-agent=MuxTV-Corpus/${index % USER_AGENT_VARIANTS}")
            }
            if (index % REFERRER_INTERVAL == 0) {
                writeLine("#KODIPROP:http-referrer=https://portal.example/corpus/${index % REFERRER_VARIANTS}")
            }

            val locator = if (index % RELATIVE_LOCATOR_INTERVAL == 0) {
                "streams/$identity.m3u8"
            } else {
                "https://stream.example/live/$identity.m3u8"
            }
            writeLine(locator)
        }

        // Deliberate malformed EXTINF: contributes one skipped entry and one warning, no locator.
        writeLine("#EXTINF:-1 malformed-corpus-record-without-comma")
        output.flush()

        val duplicateCount = spec.profile.expectedDuplicateIdentities
        return M3uCorpusManifest(
            schemaVersion = SCHEMA_VERSION,
            profile = spec.profile,
            seed = spec.seed,
            sourceCommit = spec.sourceCommit,
            expectedParsedEntries = spec.profile.entryCount,
            expectedSkippedEntries = EXPECTED_SKIPPED_ENTRIES,
            expectedWarningCount = EXPECTED_WARNING_COUNT,
            expectedDuplicateIdentities = duplicateCount,
            expectedUniqueIdentities = spec.profile.entryCount - duplicateCount,
            utf8ByteCount = byteCount,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun Long.floorMod(divisor: Long): Long {
        val remainder = this % divisor
        return if (remainder >= 0) remainder else remainder + divisor
    }

    private fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
        String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff)
    }

    private const val MIXED_LINE_ENDING_INTERVAL = 11L
    private const val LONG_METADATA_INTERVAL = 257
    private const val LONG_METADATA_CHARACTERS = 1_024
    private const val USER_AGENT_INTERVAL = 17
    private const val USER_AGENT_VARIANTS = 4
    private const val REFERRER_INTERVAL = 29
    private const val REFERRER_VARIANTS = 3
    private const val RELATIVE_LOCATOR_INTERVAL = 5
    private const val GROUP_COUNT = 8
    private const val RANDOM_SUFFIX_BOUND = 1_000_000
    private const val EXPECTED_SKIPPED_ENTRIES = 1
    private const val EXPECTED_WARNING_COUNT = 2
}
