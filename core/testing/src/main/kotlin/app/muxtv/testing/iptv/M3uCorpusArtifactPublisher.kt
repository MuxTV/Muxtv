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
)

data class M3uCorpusArtifactPair(
    val playlistPath: Path,
    val manifestPath: Path,
    val manifest: M3uCorpusManifest,
)

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
        val atomicOptions = if (replaceExisting) {
            arrayOf(ATOMIC_MOVE, REPLACE_EXISTING)
        } else {
            arrayOf(ATOMIC_MOVE)
        }
        val fallbackOptions = if (replaceExisting) {
            arrayOf(REPLACE_EXISTING)
        } else {
            emptyArray()
        }

        try {
            Files.move(source, target, *atomicOptions)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, *fallbackOptions)
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
        fileOps.createDirectories(request.outputDirectory)

        val baseName = buildBaseName(request.spec)
        val playlistPath = request.outputDirectory.resolve("$baseName.m3u8")
        val manifestPath = request.outputDirectory.resolve("$baseName.manifest.json")

        if (!request.overwrite && (fileOps.exists(playlistPath) || fileOps.exists(manifestPath))) {
            throw M3uCorpusArtifactException(M3uCorpusArtifactFailureReason.TargetExists)
        }

        var playlistTemp: Path? = null
        var manifestTemp: Path? = null
        var playlistBackup: Path? = null
        var manifestBackup: Path? = null
        var playlistPublished = false
        var manifestPublished = false

        try {
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
                    playlistBackup = moveToBackup(
                        target = playlistPath,
                        outputDirectory = request.outputDirectory,
                        baseName = baseName,
                        suffix = ".m3u8.bak",
                    )
                }
                if (fileOps.exists(manifestPath)) {
                    manifestBackup = moveToBackup(
                        target = manifestPath,
                        outputDirectory = request.outputDirectory,
                        baseName = baseName,
                        suffix = ".manifest.json.bak",
                    )
                }
            }

            fileOps.move(playlistTemp, playlistPath, replaceExisting = false)
            playlistTemp = null
            playlistPublished = true

            // Manifest is the commit marker and is intentionally published last.
            fileOps.move(manifestTemp, manifestPath, replaceExisting = false)
            manifestTemp = null
            manifestPublished = true

            playlistBackup?.let(fileOps::deleteIfExists)
            playlistBackup = null
            manifestBackup?.let(fileOps::deleteIfExists)
            manifestBackup = null

            return M3uCorpusArtifactPair(
                playlistPath = playlistPath,
                manifestPath = manifestPath,
                manifest = manifest,
            )
        } catch (_: M3uCorpusArtifactException) {
            throw
        } catch (_: Throwable) {
            val rollbackSucceeded = rollback(
                playlistPath = playlistPath,
                manifestPath = manifestPath,
                playlistPublished = playlistPublished,
                manifestPublished = manifestPublished,
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
            // Backups are removed only after successful publication or successful restoration.
            if (playlistBackup == null && manifestBackup == null) {
                Unit
            }
        }
    }

    private fun moveToBackup(
        target: Path,
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
        fileOps.move(target, backup, replaceExisting = false)
        return backup
    }

    private fun rollback(
        playlistPath: Path,
        manifestPath: Path,
        playlistPublished: Boolean,
        manifestPublished: Boolean,
        playlistBackup: Path?,
        manifestBackup: Path?,
    ): Boolean {
        var succeeded = true

        if (manifestPublished) {
            succeeded = deleteForRollback(manifestPath) && succeeded
        }
        if (playlistPublished) {
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
    } catch (_: Throwable) {
        false
    }

    private fun restoreBackup(
        backup: Path,
        target: Path,
    ): Boolean = try {
        fileOps.deleteIfExists(target)
        fileOps.move(backup, target, replaceExisting = false)
        true
    } catch (_: Throwable) {
        false
    }

    private fun deleteQuietly(path: Path) {
        try {
            fileOps.deleteIfExists(path)
        } catch (_: Throwable) {
            // Temp cleanup is best effort; publication/rollback outcome remains authoritative.
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
