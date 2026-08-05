package app.muxtv.buildlogic

import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.DisableCachingByDefault
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@DisableCachingByDefault(
    because = "The task verifies repository working-tree state and has no reusable output.",
)
abstract class VerifyCurrentRoomSchemaTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val versionSource: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val schemaRoot: DirectoryProperty

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    @get:Inject
    protected abstract val execOperations: ExecOperations

    @TaskAction
    fun verifySchema() {
        val currentVersion = extractCurrentDatabaseVersion(versionSource.get().asFile.readText())
        val schemaFile = schemaRoot.file("$currentVersion.json").get().asFile
        if (!schemaFile.isFile) {
            throw GradleException(
                "Missing generated Room schema for version $currentVersion: $schemaFile. " +
                    "Run :core:database:copyRoomSchemas and commit the exact generated artifact.",
            )
        }

        val metadata = extractRoomSchemaMetadata(schemaFile.readText())
        if (metadata.version != currentVersion) {
            throw GradleException(
                "Room schema version mismatch: source=$currentVersion " +
                    "artifact=${metadata.version} ($schemaFile).",
            )
        }
        if (metadata.identityHash.isBlank()) {
            throw GradleException("Room schema identityHash is missing: $schemaFile")
        }

        val repositoryDirectory = repositoryRoot.get().asFile
        val relativeSchemaPath = schemaFile
            .relativeTo(repositoryDirectory)
            .invariantSeparatorsPath
        val standardOutput = ByteArrayOutputStream()
        val errorOutput = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir(repositoryDirectory)
            commandLine(
                "git",
                "status",
                "--porcelain=v1",
                "--",
                relativeSchemaPath,
            )
            this.standardOutput = standardOutput
            this.errorOutput = errorOutput
            isIgnoreExitValue = true
        }
        if (result.exitValue != 0) {
            throw GradleException(
                "Unable to inspect generated Room schema with git: " +
                    errorOutput.toString(Charsets.UTF_8),
            )
        }

        val gitStatus = standardOutput.toString(Charsets.UTF_8).trim()
        if (gitStatus.isNotEmpty()) {
            throw GradleException(
                "Generated Room schema is missing or differs from the committed artifact: " +
                    "$relativeSchemaPath ($gitStatus). Commit the exact output of " +
                    ":core:database:copyRoomSchemas.",
            )
        }

        logger.lifecycle(
            "Verified committed Room schema v$currentVersion " +
                "identity=${metadata.identityHash} path=$relativeSchemaPath",
        )
    }
}

internal data class RoomSchemaMetadata(
    val version: Int,
    val identityHash: String,
)

internal fun extractCurrentDatabaseVersion(source: String): Int =
    Regex("CURRENT_DATABASE_VERSION\\s*=\\s*(\\d+)")
        .find(source)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: throw GradleException("Unable to resolve CURRENT_DATABASE_VERSION from source.")

internal fun extractRoomSchemaMetadata(json: String): RoomSchemaMetadata {
    val databaseObject = Regex(
        pattern = """(?s)"database"\s*:\s*\{(.*?)\n\s*\}""",
    ).find(json)?.groupValues?.get(1)
        ?: throw GradleException("Room schema has no database object.")
    val version = Regex(""""version"\s*:\s*(\d+)""")
        .find(databaseObject)
        ?.groupValues
        ?.get(1)
        ?.toInt()
        ?: throw GradleException("Room schema has no numeric database.version.")
    val identityHash = Regex(""""identityHash"\s*:\s*"([^"]*)"""")
        .find(databaseObject)
        ?.groupValues
        ?.get(1)
        ?: throw GradleException("Room schema has no database.identityHash.")
    return RoomSchemaMetadata(
        version = version,
        identityHash = identityHash,
    )
}
