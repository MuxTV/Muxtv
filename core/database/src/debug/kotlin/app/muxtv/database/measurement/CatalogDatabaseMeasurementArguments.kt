package app.muxtv.database.measurement

import android.content.Context
import android.os.Bundle
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private val OUTPUT_NAME_PATTERN = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.json")

internal data class CatalogDatabaseMeasurementArguments(
    val spec: CatalogDatabaseMeasurementSpec,
    val outputName: String,
) {
    init {
        require(outputName.matches(OUTPUT_NAME_PATTERN))
    }

    companion object {
        fun parse(arguments: Bundle): CatalogDatabaseMeasurementArguments {
            val sourceCommit = arguments.requiredString(ARGUMENT_SOURCE_COMMIT)
            val runnerLabel = arguments.requiredString(ARGUMENT_RUNNER_LABEL)
            val warmups = arguments.requiredInt(ARGUMENT_WARMUPS)
            val iterations = arguments.requiredInt(ARGUMENT_ITERATIONS)
            val entryCount = arguments.requiredInt(ARGUMENT_ENTRY_COUNT)
            val outputName = arguments.requiredString(ARGUMENT_OUTPUT_NAME)
            require(entryCount == DEFAULT_ENTRY_COUNT) {
                "Catalog database measurement entry count is unsupported."
            }
            return CatalogDatabaseMeasurementArguments(
                spec = CatalogDatabaseMeasurementSpec(
                    sourceCommit = sourceCommit,
                    runnerLabel = runnerLabel,
                    workload = CatalogDatabaseMeasurementWorkload(
                        entryCount = entryCount,
                        batchSize = DEFAULT_BATCH_SIZE,
                        firstPageLimit = DEFAULT_FIRST_PAGE_LIMIT,
                        sourceOverviewCount = DEFAULT_SOURCE_OVERVIEW_COUNT,
                        warmupIterations = warmups,
                        measuredIterations = iterations,
                    ),
                ),
                outputName = outputName,
            )
        }

        private fun Bundle.requiredString(key: String): String =
            getString(key)?.trim()?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("Catalog database measurement argument is missing.")

        private fun Bundle.requiredInt(key: String): Int =
            requiredString(key).toIntOrNull()
                ?: throw IllegalArgumentException("Catalog database measurement numeric argument is invalid.")

        const val ARGUMENT_SOURCE_COMMIT = "measurementSourceCommit"
        const val ARGUMENT_RUNNER_LABEL = "measurementRunnerLabel"
        const val ARGUMENT_WARMUPS = "measurementWarmups"
        const val ARGUMENT_ITERATIONS = "measurementIterations"
        const val ARGUMENT_ENTRY_COUNT = "measurementEntryCount"
        const val ARGUMENT_OUTPUT_NAME = "measurementOutputName"
        const val DEFAULT_ENTRY_COUNT = 50_000
        const val DEFAULT_BATCH_SIZE = 250
        const val DEFAULT_FIRST_PAGE_LIMIT = 100
        const val DEFAULT_SOURCE_OVERVIEW_COUNT = 32
    }
}

internal object CatalogDatabaseMeasurementReportPublisher {
    fun publish(
        context: Context,
        report: CatalogDatabaseMeasurementReport,
        outputName: String,
    ): File {
        require(outputName.matches(OUTPUT_NAME_PATTERN))
        val externalRoot = requireNotNull(context.getExternalFilesDir(null)) {
            "Catalog database measurement external storage is unavailable."
        }
        val directory = File(externalRoot, DIRECTORY_NAME)
        check(directory.isDirectory || directory.mkdirs()) {
            "Catalog database measurement directory could not be created."
        }
        val target = File(directory, outputName)
        val staged = File.createTempFile(".catalog-database-measurement-", ".tmp", directory)
        try {
            staged.outputStream().use { output ->
                CatalogDatabaseMeasurementJsonWriter.write(report, output)
            }
            try {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    staged.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            check(target.isFile && target.length() > 0L) {
                "Catalog database measurement report publication failed."
            }
            return target
        } catch (error: IOException) {
            throw IllegalStateException("Catalog database measurement report publication failed.", error)
        } finally {
            staged.delete()
        }
    }

    const val DIRECTORY_NAME = "measurements"
}
