package app.muxtv.testing.iptv

import java.nio.file.Files
import java.nio.file.Path

data class M3uCorpusArtifactRequest(
    val spec: M3uCorpusSpec,
    val outputDirectory: Path,
    val overwrite: Boolean = false,
) {
    override fun toString(): String =
        "M3uCorpusArtifactRequest(spec=$spec, outputDirectory=<redacted>, overwrite=$overwrite)"
}

data class M3uCorpusArtifactPair(
    val playlistPath: Path,
    val manifestPath: Path,
    val manifest: M3uCorpusManifest,
) {
    override fun toString(): String =
        "M3uCorpusArtifactPair(playlistPath=<redacted>, manifestPath=<redacted>, manifest=$manifest)"
}

enum class M3uCorpusArtifactFailureReason {
    TargetExists,
    PublishFailed,
    RollbackFailed,
}

class M3uCorpusArtifactException(
    val reason: M3uCorpusArtifactFailureReason,
) : IllegalStateException(
    when (reason) {
        M3uCorpusArtifactFailureReason.TargetExists ->
            "Corpus artifact target already exists."

        M3uCorpusArtifactFailureReason.PublishFailed ->
            "Corpus artifact pair could not be published."

        M3uCorpusArtifactFailureReason.RollbackFailed ->
            "Corpus artifact rollback could not be completed."
    },
)

class M3uCorpusArtifactPublisher private constructor(
    private val moveFile: (source: Path, target: Path) -> Unit,
) {
    constructor() : this({ source, target ->
        // Deliberately no REPLACE_EXISTING: overwrite uses explicit backup/restore.
        Files.move(source, target)
    })

    fun publish(request: M3uCorpusArtifactRequest): M3uCorpusArtifactPair {
        val baseName = buildBaseName(request.spec)
        val playlistPath = request.outputDirectory.resolve("$baseName.m3u8")
        val manifestPath = request.outputDirectory.resolve("$baseName.manifest.json")

        var playlistTemp: Path? = null
        var manifestTemp: Path? = null
        var playlistBackup: Path? = null
        var manifestBackup: Path? = null
        var playlistPublishAttempted = false
        var manifestPublishAttempted = false

        try {
            Files.createDirectories(request.outputDirectory)

            if (!request.overwrite && (Files.exists(playlistPath) || Files.exists(manifestPath))) {
                throw M3uCorpusArtifactException(M3uCorpusArtifactFailureReason.TargetExists)
            }

            playlistTemp = Files.createTempFile(
                request.outputDirectory,
                ".$baseName-",
                ".m3u8.tmp",
            )
            val manifest = Files.newOutputStream(playlistTemp).use { output ->
                DeterministicM3uCorpusGenerator.generate(request.spec, output)
            }

            manifestTemp = Files.createTempFile(
                request.outputDirectory,
                ".$baseName-",
                ".manifest.json.tmp",
            )
            Files.newOutputStream(manifestTemp).use { output ->
                M3uCorpusManifestJsonWriter.write(manifest, output)
            }

            if (request.overwrite) {
                if (Files.exists(playlistPath)) {
                    playlistBackup = reserveBackupPath(
                        outputDirectory = request.outputDirectory,
                        baseName = baseName,
                        suffix = ".m3u8.bak",
                    )
                    moveFile(playlistPath, playlistBackup)
                }
                if (Files.exists(manifestPath)) {
                    manifestBackup = reserveBackupPath(
                        outputDirectory = request.outputDirectory,
                        baseName = baseName,
                        suffix = ".manifest.json.bak",
                    )
                    moveFile(manifestPath, manifestBackup)
                }
            }

            playlistPublishAttempted = true
            moveFile(playlistTemp, playlistPath)
            playlistTemp = null

            // Manifest is the commit marker and is intentionally published last.
            manifestPublishAttempted = true
            moveFile(manifestTemp, manifestPath)
            manifestTemp = null

            // The new pair is committed. Backup cleanup cannot invalidate that outcome.
            playlistBackup?.let(::deleteQuietly)
            playlistBackup = null
            manifestBackup?.let(::deleteQuietly)
            manifestBackup = null

            return M3uCorpusArtifactPair(
                playlistPath = playlistPath,
                manifestPath = manifestPath,
                manifest = manifest,
            )
        } catch (error: M3uCorpusArtifactException) {
            throw error
        } catch (_: Exception) {
            val rollbackSucceeded = rollback(
                playlistPath = playlistPath,
                manifestPath = manifestPath,
                playlistTemp = playlistTemp,
                manifestTemp = manifestTemp,
                playlistPublishAttempted = playlistPublishAttempted,
                manifestPublishAttempted = manifestPublishAttempted,
                playlistBackup = playlistBackup,
                manifestBackup = manifestBackup,
            )
            if (rollbackSucceeded) {
                playlistTemp = null
                manifestTemp = null
                playlistBackup = null
                manifestBackup = null
            }
            throw M3uCorpusArtifactException(
                if (rollbackSucceeded) {
                    M3uCorpusArtifactFailureReason.PublishFailed
                } else {
                    M3uCorpusArtifactFailureReason.RollbackFailed
                },
            )
        } finally {
            // Fatal JVM errors still receive a best-effort staging cleanup attempt.
            playlistTemp?.let(::deleteQuietly)
            manifestTemp?.let(::deleteQuietly)
            // On rollback failure, backup files intentionally remain for recovery.
        }
    }

    private fun reserveBackupPath(
        outputDirectory: Path,
        baseName: String,
        suffix: String,
    ): Path {
        val backup = Files.createTempFile(
            outputDirectory,
            ".$baseName-",
            suffix,
        )
        Files.deleteIfExists(backup)
        return backup
    }

    private fun rollback(
        playlistPath: Path,
        manifestPath: Path,
        playlistTemp: Path?,
        manifestTemp: Path?,
        playlistPublishAttempted: Boolean,
        manifestPublishAttempted: Boolean,
        playlistBackup: Path?,
        manifestBackup: Path?,
    ): Boolean {
        var succeeded = true

        if (manifestPublishAttempted) {
            succeeded = deleteForRollback(manifestPath) && succeeded
        }
        if (playlistPublishAttempted) {
            succeeded = deleteForRollback(playlistPath) && succeeded
        }

        if (playlistBackup != null) {
            succeeded = restoreBackup(playlistBackup, playlistPath) && succeeded
        }
        if (manifestBackup != null) {
            succeeded = restoreBackup(manifestBackup, manifestPath) && succeeded
        }

        if (playlistTemp != null) {
            succeeded = deleteForRollback(playlistTemp) && succeeded
        }
        if (manifestTemp != null) {
            succeeded = deleteForRollback(manifestTemp) && succeeded
        }

        return succeeded
    }

    private fun deleteForRollback(path: Path): Boolean = try {
        Files.deleteIfExists(path)
        true
    } catch (_: Exception) {
        false
    }

    private fun restoreBackup(
        backup: Path,
        target: Path,
    ): Boolean {
        return try {
            if (!Files.exists(backup)) {
                Files.exists(target)
            } else {
                Files.deleteIfExists(target)
                moveFile(backup, target)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (_: Exception) {
            // Best effort only: committed pairs or rollback evidence remain authoritative.
        }
    }

    private fun buildBaseName(spec: M3uCorpusSpec): String =
        "muxtv-m3u-${spec.profile.artifactId}-seed-${spec.seed.artifactToken()}-${spec.sourceCommit}"

    private fun Long.artifactToken(): String {
        val value = toString()
        return if (value.startsWith('-')) {
            "neg-${value.substring(1)}"
        } else {
            value
        }
    }

    internal companion object {
        fun forTesting(
            moveFile: (source: Path, target: Path) -> Unit,
        ): M3uCorpusArtifactPublisher = M3uCorpusArtifactPublisher(moveFile)
    }
}
