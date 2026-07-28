package app.muxtv.testing.iptv

import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

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

/** Minimal filesystem seam for deterministic failure/rollback contracts in the testing module. */
interface M3uCorpusArtifactFileOps {
    fun createDirectories(directory: Path)

    fun exists(path: Path): Boolean

    fun createTempFile(
        directory: Path,
        prefix: String,
        suffix: String,
    ): Path

    fun newOutputStream(path: Path): OutputStream

    fun move(
        source: Path,
        target: Path,
        replaceExisting: Boolean,
    )

    fun deleteIfExists(path: Path)
}

object NioM3uCorpusArtifactFileOps : M3uCorpusArtifactFileOps {
    override fun createDirectories(directory: Path) {
        Files.createDirectories(directory)
    }

    override fun exists(path: Path): Boolean = Files.exists(path)

    override fun createTempFile(
        directory: Path,
        prefix: String,
        suffix: String,
    ): Path = Files.createTempFile(directory, prefix, suffix)

    override fun newOutputStream(path: Path): OutputStream = Files.newOutputStream(path)

    override fun move(
        source: Path,
        target: Path,
        replaceExisting: Boolean,
    ) {
        if (!replaceExisting) {
            // The default move contract must fail rather than replace an existing target.
            Files.move(source, target)
            return
        }

        try {
            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, REPLACE_EXISTING)
        }
    }

    override fun deleteIfExists(path: Path) {
        Files.deleteIfExists(path)
    }
}

class M3uCorpusArtifactPublisher(
    private val fileOps: M3uCorpusArtifactFileOps = NioM3uCorpusArtifactFileOps,
) {
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
            fileOps.createDirectories(request.outputDirectory)

            if (!request.overwrite && (fileOps.exists(playlistPath) || fileOps.exists(manifestPath))) {
                throw M3uCorpusArtifactException(M3uCorpusArtifactFailureReason.TargetExists)
            }

            playlistTemp = fileOps.createTempFile(
                request.outputDirectory,
                ".$baseName-",
                ".m3u8.tmp",
            )
            val manifest = fileOps.newOutputStream(playlistTemp).use { output ->
                DeterministicM3uCorpusGenerator.generate(request.spec, output)
            }

            manifestTemp = fileOps.createTempFile(
                request.outputDirectory,
                ".$baseName-",
                ".manifest.json.tmp",
            )
            fileOps.newOutputStream(manifestTemp).use { output ->
                M3uCorpusManifestJsonWriter.write(manifest, output)
            }

            if (request.overwrite) {
                if (fileOps.exists(playlistPath)) {
                    playlistBackup = reserveBackupPath(
                        outputDirectory = request.outputDirectory,
                        baseName = baseName,
                        suffix = ".m3u8.bak",
                    )
                    fileOps.move(playlistPath, playlistBackup, replaceExisting = false)
                }
                if (fileOps.exists(manifestPath)) {
                    manifestBackup = reserveBackupPath(
                        outputDirectory = request.outputDirectory,
                        baseName = baseName,
                        suffix = ".manifest.json.bak",
                    )
                    fileOps.move(manifestPath, manifestBackup, replaceExisting = false)
                }
            }

            playlistPublishAttempted = true
            fileOps.move(playlistTemp, playlistPath, replaceExisting = false)
            playlistTemp = null

            // Manifest is the commit marker and is intentionally published last.
            manifestPublishAttempted = true
            fileOps.move(manifestTemp, manifestPath, replaceExisting = false)
            manifestTemp = null

            // The new pair is committed. Backup cleanup cannot invalidate that successful outcome.
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
                playlistPublishAttempted = playlistPublishAttempted,
                manifestPublishAttempted = manifestPublishAttempted,
                playlistBackup = playlistBackup,
                manifestBackup = manifestBackup,
            )
            if (rollbackSucceeded) {
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
            playlistTemp?.let(::deleteQuietly)
            manifestTemp?.let(::deleteQuietly)
            // On rollback failure, backup files intentionally remain for manual recovery.
        }
    }

    private fun reserveBackupPath(
        outputDirectory: Path,
        baseName: String,
        suffix: String,
    ): Path {
        val backup = fileOps.createTempFile(
            outputDirectory,
            ".$baseName-",
            suffix,
        )
        fileOps.deleteIfExists(backup)
        return backup
    }

    private fun rollback(
        playlistPath: Path,
        manifestPath: Path,
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

        return succeeded
    }

    private fun deleteForRollback(path: Path): Boolean = try {
        fileOps.deleteIfExists(path)
        true
    } catch (_: Exception) {
        false
    }

    private fun restoreBackup(
        backup: Path,
        target: Path,
    ): Boolean {
        return try {
            if (!fileOps.exists(backup)) {
                fileOps.exists(target)
            } else {
                fileOps.deleteIfExists(target)
                fileOps.move(backup, target, replaceExisting = false)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun deleteQuietly(path: Path) {
        try {
            fileOps.deleteIfExists(path)
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
}
