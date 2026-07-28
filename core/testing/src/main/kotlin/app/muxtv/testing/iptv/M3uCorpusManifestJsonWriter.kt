package app.muxtv.testing.iptv

import java.io.OutputStream

/** Writes a stable, payload-free manifest for a generated M3U corpus. */
object M3uCorpusManifestJsonWriter {
    const val MANIFEST_SCHEMA_VERSION: Int = 1

    fun write(
        manifest: M3uCorpusManifest,
        output: OutputStream,
    ) {
        val json = listOf(
            "{",
            "  \"manifestSchemaVersion\": $MANIFEST_SCHEMA_VERSION,",
            "  \"generatorSchemaVersion\": ${manifest.schemaVersion},",
            "  \"profile\": \"${manifest.profile.artifactId}\",",
            "  \"seed\": ${manifest.seed},",
            "  \"sourceCommit\": \"${manifest.sourceCommit}\",",
            "  \"expectedParsedEntries\": ${manifest.expectedParsedEntries},",
            "  \"expectedSkippedEntries\": ${manifest.expectedSkippedEntries},",
            "  \"expectedWarningCount\": ${manifest.expectedWarningCount},",
            "  \"expectedDuplicateIdentities\": ${manifest.expectedDuplicateIdentities},",
            "  \"expectedUniqueIdentities\": ${manifest.expectedUniqueIdentities},",
            "  \"playlistUtf8ByteCount\": ${manifest.utf8ByteCount},",
            "  \"playlistSha256\": \"${manifest.sha256}\"",
            "}",
        ).joinToString(separator = "\n", postfix = "\n")

        output.write(json.toByteArray(Charsets.UTF_8))
        output.flush()
    }
}
