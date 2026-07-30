package app.muxtv.testing.measurements

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlin.system.exitProcess

private const val MAX_SERIES_REQUEST_BYTES = 65_536L
private const val MAX_SERIES_REPORT_BYTES = 1_048_576L
private val SERIES_SAFE_TOKEN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val SERIES_JSON_BASENAME = Regex("[a-z0-9][a-z0-9._-]{0,63}\\.json")

enum class MeasurementSeriesCommandExitCode(val code: Int, val diagnostic: String) {
    SUCCESS(0, "success"),
    USAGE(2, "usage-error"),
    INPUT(3, "input-error"),
    ANALYSIS(4, "analysis-error"),
    PUBLICATION(5, "publication-error"),
    INTERNAL(10, "internal-error"),
}

object MeasurementSeriesCommand {
    fun run(
        args: Array<String>,
        stdout: Appendable,
        stderr: Appendable,
    ): Int {
        return try {
            val options = parseOptions(args)
            val request = readRequest(options.requestFile)
            val result = analyze(
                request = request,
                inputDirectory = options.inputDirectory,
            )
            publish(
                request = request,
                outputDirectory = options.outputDirectory,
                varianceBytes = result.varianceBytes,
                auditBytes = result.auditBytes,
            )
            stdout.append("status=passed\n")
            stdout.append("report=${request.outputName}\n")
            stdout.append("manifest=${request.manifestName}\n")
            MeasurementSeriesCommandExitCode.SUCCESS.code
        } catch (_: SeriesUsageException) {
            stderr.append("${MeasurementSeriesCommandExitCode.USAGE.diagnostic}\n")
            MeasurementSeriesCommandExitCode.USAGE.code
        } catch (_: SeriesInputException) {
            stderr.append("${MeasurementSeriesCommandExitCode.INPUT.diagnostic}\n")
            MeasurementSeriesCommandExitCode.INPUT.code
        } catch (_: SeriesAnalysisException) {
            stderr.append("${MeasurementSeriesCommandExitCode.ANALYSIS.diagnostic}\n")
            MeasurementSeriesCommandExitCode.ANALYSIS.code
        } catch (_: SeriesPublicationException) {
            stderr.append("${MeasurementSeriesCommandExitCode.PUBLICATION.diagnostic}\n")
            MeasurementSeriesCommandExitCode.PUBLICATION.code
        } catch (_: Exception) {
            stderr.append("${MeasurementSeriesCommandExitCode.INTERNAL.diagnostic}\n")
            MeasurementSeriesCommandExitCode.INTERNAL.code
        }
    }

    private fun parseOptions(args: Array<String>): SeriesOptions {
        if (args.size != 6 || args.size % 2 != 0) usageFailure()
        val values = linkedMapOf<String, String>()
        var index = 0
        while (index < args.size) {
            val option = args[index]
            val value = args[index + 1]
            if (option !in ALLOWED_OPTIONS || value.isBlank() || values.put(option, value) != null) {
                usageFailure()
            }
            index += 2
        }
        if (values.keys != ALLOWED_OPTIONS) usageFailure()
        return try {
            SeriesOptions(
                requestFile = Path.of(requireNotNull(values[OPTION_REQUEST])),
                inputDirectory = Path.of(requireNotNull(values[OPTION_INPUT_DIRECTORY])),
                outputDirectory = Path.of(requireNotNull(values[OPTION_OUTPUT_DIRECTORY])),
            )
        } catch (_: InvalidPathException) {
            usageFailure()
        }
    }

    private fun readRequest(path: Path): SeriesRequest {
        val bytes = readBoundedFile(path, MAX_SERIES_REQUEST_BYTES)
        return try {
            decodeRequest(bytes)
        } catch (failure: SeriesInputException) {
            throw failure
        } catch (_: MeasurementReportAdaptationException) {
            inputFailure()
        } catch (_: IllegalArgumentException) {
            inputFailure()
        }
    }

    private fun decodeRequest(bytes: ByteArray): SeriesRequest {
        val root = parseStrictJsonObject(bytes)
        root.requireExactFields(
            "schemaVersion",
            "family",
            "outputName",
            "runs",
            "androidProfile",
        )
        if (root.requireInt("schemaVersion") != 1) inputFailure()
        val family = when (root.requireString("family")) {
            MeasurementReportFamily.M3U_PARSE.id -> MeasurementReportFamily.M3U_PARSE
            MeasurementReportFamily.CATALOG_DATABASE.id -> MeasurementReportFamily.CATALOG_DATABASE
            MeasurementReportFamily.PLAYER_PROXY.id -> MeasurementReportFamily.PLAYER_PROXY
            else -> inputFailure()
        }
        val outputName = root.requireString("outputName").requireSeriesJsonBasename()
        if (outputName.endsWith(".manifest.json")) inputFailure()
        val runs = root.requireArray("runs").map { element ->
            val run = element.requireObjectValue()
            run.requireExactFields("repetitionId", "reportName")
            SeriesRequestRun(
                repetitionId = run.requireString("repetitionId").requireSeriesToken(),
                reportName = run.requireString("reportName").requireSeriesJsonBasename(),
            )
        }
        if (runs.size !in 2..20) inputFailure()
        if (runs.map(SeriesRequestRun::repetitionId).distinct().size != runs.size) inputFailure()
        if (runs.map(SeriesRequestRun::reportName).distinct().size != runs.size) inputFailure()

        val androidProfileObject = root.requireNullableObject("androidProfile")
        val androidProfile = androidProfileObject?.let(::parseAndroidProfile)
        when (family) {
            MeasurementReportFamily.M3U_PARSE -> if (androidProfile != null) inputFailure()
            MeasurementReportFamily.CATALOG_DATABASE,
            MeasurementReportFamily.PLAYER_PROXY,
            -> if (androidProfile == null) inputFailure()
        }

        return SeriesRequest(
            family = family,
            outputName = outputName,
            runs = runs,
            androidProfile = androidProfile,
        )
    }

    private fun parseAndroidProfile(value: StrictJsonObject): AndroidMeasurementProfileContext {
        value.requireExactFields(
            "requestedApiLevel",
            "systemImage",
            "configuredRamMb",
            "configuredCpuCores",
            "fallbackUsed",
        )
        val requestedApiLevel = value.requireInt("requestedApiLevel")
        val systemImage = value.requireString("systemImage")
        val configuredRamMb = value.requireInt("configuredRamMb")
        val configuredCpuCores = value.requireInt("configuredCpuCores")
        val fallbackUsed = value.requireBoolean("fallbackUsed")
        if (
            requestedApiLevel !in 1..100 ||
            systemImage.isBlank() ||
            systemImage.length > 256 ||
            systemImage.any { it == '\r' || it == '\n' || it.code < 0x20 } ||
            configuredRamMb !in 512..16_384 ||
            configuredCpuCores !in 1..64
        ) {
            inputFailure()
        }
        return AndroidMeasurementProfileContext(
            requestedApiLevel = requestedApiLevel,
            systemImage = systemImage,
            configuredRamMb = configuredRamMb,
            configuredCpuCores = configuredCpuCores,
            fallbackUsed = fallbackUsed,
        )
    }

    private fun analyze(
        request: SeriesRequest,
        inputDirectory: Path,
    ): SeriesAnalysisResult {
        val realInputDirectory = try {
            inputDirectory.toRealPath()
        } catch (_: IOException) {
            inputFailure()
        } catch (_: SecurityException) {
            inputFailure()
        }
        if (!Files.isDirectory(realInputDirectory)) inputFailure()

        val adapted = request.runs.map { run ->
            val reportPath = resolveInputReport(realInputDirectory, run.reportName)
            val bytes = readBoundedFile(reportPath, MAX_SERIES_REPORT_BYTES)
            try {
                MeasurementReportAdapter.adapt(
                    MeasurementAdaptationRequest(
                        family = request.family,
                        repetitionId = run.repetitionId,
                        reportBytes = bytes,
                        androidProfile = request.androidProfile,
                    ),
                )
            } catch (_: MeasurementReportAdaptationException) {
                inputFailure()
            }
        }

        val identity = adapted.first().identity
        val variance = try {
            MeasurementVarianceAnalyzer.analyze(
                identity = identity,
                runs = adapted.map(AdaptedMeasurementRun::run),
            )
        } catch (_: IllegalArgumentException) {
            analysisFailure()
        }

        val varianceOutput = ByteArrayOutputStream()
        MeasurementVarianceJsonWriter.write(variance, varianceOutput)
        val varianceBytes = varianceOutput.toByteArray()
        val audit = try {
            MeasurementSeriesAuditManifest(
                schemaVersion = 1,
                thresholdApplied = false,
                family = request.family.id,
                outputName = request.outputName,
                varianceReportSha256 = sha256(varianceBytes),
                identityFingerprintSha256 = variance.identityFingerprintSha256,
                seriesCount = request.runs.size,
                inputs = request.runs.zip(adapted).map { (run, adaptedRun) ->
                    MeasurementSeriesAuditInput(
                        reportName = run.reportName,
                        sha256 = adaptedRun.run.sourceReportSha256,
                    )
                },
            )
        } catch (_: IllegalArgumentException) {
            analysisFailure()
        }
        val auditOutput = ByteArrayOutputStream()
        MeasurementSeriesAuditJsonWriter.write(audit, auditOutput)
        return SeriesAnalysisResult(
            varianceBytes = varianceBytes,
            auditBytes = auditOutput.toByteArray(),
        )
    }

    private fun resolveInputReport(
        realInputDirectory: Path,
        reportName: String,
    ): Path {
        val candidate = realInputDirectory.resolve(reportName).normalize()
        val realCandidate = try {
            candidate.toRealPath()
        } catch (_: IOException) {
            inputFailure()
        } catch (_: SecurityException) {
            inputFailure()
        }
        if (realCandidate.parent != realInputDirectory || !Files.isRegularFile(realCandidate)) {
            inputFailure()
        }
        return realCandidate
    }

    private fun publish(
        request: SeriesRequest,
        outputDirectory: Path,
        varianceBytes: ByteArray,
        auditBytes: ByteArray,
    ) {
        val realOutputDirectory = try {
            outputDirectory.toRealPath()
        } catch (_: IOException) {
            publicationFailure()
        } catch (_: SecurityException) {
            publicationFailure()
        }
        if (!Files.isDirectory(realOutputDirectory)) publicationFailure()
        val varianceTarget = realOutputDirectory.resolve(request.outputName)
        val auditTarget = realOutputDirectory.resolve(request.manifestName)
        if (Files.exists(varianceTarget) || Files.exists(auditTarget)) publicationFailure()

        var varianceStage: Path? = null
        var auditStage: Path? = null
        var variancePublished = false
        try {
            varianceStage = Files.createTempFile(realOutputDirectory, ".muxtv-variance-", ".tmp")
            auditStage = Files.createTempFile(realOutputDirectory, ".muxtv-variance-manifest-", ".tmp")
            Files.write(
                varianceStage,
                varianceBytes,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.write(
                auditStage,
                auditBytes,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            Files.move(varianceStage, varianceTarget, StandardCopyOption.ATOMIC_MOVE)
            variancePublished = true
            varianceStage = null
            Files.move(auditStage, auditTarget, StandardCopyOption.ATOMIC_MOVE)
            auditStage = null
        } catch (_: IOException) {
            if (variancePublished) {
                try {
                    Files.deleteIfExists(varianceTarget)
                } catch (_: IOException) {
                    // Best-effort removal preserves the typed publication failure.
                }
            }
            publicationFailure()
        } catch (_: SecurityException) {
            if (variancePublished) {
                try {
                    Files.deleteIfExists(varianceTarget)
                } catch (_: Exception) {
                    // Best-effort removal preserves the typed publication failure.
                }
            }
            publicationFailure()
        } finally {
            varianceStage?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: Exception) {
                    // Temporary cleanup is best effort after a typed result is selected.
                }
            }
            auditStage?.let {
                try {
                    Files.deleteIfExists(it)
                } catch (_: Exception) {
                    // Temporary cleanup is best effort after a typed result is selected.
                }
            }
        }
    }

    private fun readBoundedFile(path: Path, maximumBytes: Long): ByteArray {
        try {
            if (!Files.isRegularFile(path)) inputFailure()
            val size = Files.size(path)
            if (size <= 0L || size > maximumBytes) inputFailure()
            return Files.readAllBytes(path)
        } catch (failure: SeriesInputException) {
            throw failure
        } catch (_: IOException) {
            inputFailure()
        } catch (_: SecurityException) {
            inputFailure()
        }
    }

    private val ALLOWED_OPTIONS = linkedSetOf(
        OPTION_REQUEST,
        OPTION_INPUT_DIRECTORY,
        OPTION_OUTPUT_DIRECTORY,
    )
    private const val OPTION_REQUEST = "--request"
    private const val OPTION_INPUT_DIRECTORY = "--input-directory"
    private const val OPTION_OUTPUT_DIRECTORY = "--output-directory"
}

private data class SeriesOptions(
    val requestFile: Path,
    val inputDirectory: Path,
    val outputDirectory: Path,
)

private data class SeriesRequestRun(
    val repetitionId: String,
    val reportName: String,
)

private data class SeriesRequest(
    val family: MeasurementReportFamily,
    val outputName: String,
    val runs: List<SeriesRequestRun>,
    val androidProfile: AndroidMeasurementProfileContext?,
) {
    val manifestName: String = outputName.removeSuffix(".json") + ".manifest.json"
}

private data class SeriesAnalysisResult(
    val varianceBytes: ByteArray,
    val auditBytes: ByteArray,
)

private class SeriesUsageException : IllegalArgumentException()
private class SeriesInputException : IllegalArgumentException()
private class SeriesAnalysisException : IllegalArgumentException()
private class SeriesPublicationException : IllegalStateException()

private fun String.requireSeriesToken(): String {
    if (!matches(SERIES_SAFE_TOKEN)) inputFailure()
    return this
}

private fun String.requireSeriesJsonBasename(): String {
    if (!matches(SERIES_JSON_BASENAME) || contains('/') || contains('\\') || contains("..")) {
        inputFailure()
    }
    return this
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private fun usageFailure(): Nothing = throw SeriesUsageException()
private fun inputFailure(): Nothing = throw SeriesInputException()
private fun analysisFailure(): Nothing = throw SeriesAnalysisException()
private fun publicationFailure(): Nothing = throw SeriesPublicationException()

fun main(args: Array<String>) {
    exitProcess(MeasurementSeriesCommand.run(args, System.out, System.err))
}
