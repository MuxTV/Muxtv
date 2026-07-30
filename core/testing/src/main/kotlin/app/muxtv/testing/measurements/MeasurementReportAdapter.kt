package app.muxtv.testing.measurements

import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlinx.serialization.json.JsonArray

private const val MAX_REPORT_BYTES = 1_048_576
private const val MAX_SAFE_FIELD_LENGTH = 256
private val SAFE_TOKEN = Regex("[a-z0-9][a-z0-9._-]{0,63}")
private val SOURCE_COMMIT = Regex("[0-9a-f]{40}")
private val SHA_256 = Regex("[0-9a-f]{64}")
private val SYSTEM_IMAGE = Regex(
    "system-images;android-(\\d+);(android-tv|google-tv);(x86_64|x86)",
)

private val M3U_PROFILES = setOf("small-1k", "medium-10k", "large-50k")
private const val M3U_SCOPE = "local-file-open-plus-streaming-parser-no-retention-sink"

private val ROOM_OPERATION_IDS = listOf(
    "stage-batch-250",
    "stage-total-10k",
    "activate-10k",
    "active-channel-first-page",
    "source-overview-32",
)

private val PLAYER_OPERATION_IDS = listOf(
    "request-construct",
    "setup-envelope-roundtrip",
    "coordinator-install-active-clear",
    "coordinator-cancel-before-install",
    "registry-disconnect-reacquire",
)

enum class MeasurementReportFamily(val id: String) {
    M3U_PARSE("m3u-parse"),
    CATALOG_DATABASE("catalog-database"),
    PLAYER_PROXY("player-proxy"),
}

data class AndroidMeasurementProfileContext(
    val requestedApiLevel: Int,
    val systemImage: String,
    val configuredRamMb: Int,
    val configuredCpuCores: Int,
    val fallbackUsed: Boolean,
)

class MeasurementAdaptationRequest(
    val family: MeasurementReportFamily,
    val repetitionId: String,
    reportBytes: ByteArray,
    val androidProfile: AndroidMeasurementProfileContext? = null,
) {
    internal val reportBytes: ByteArray = reportBytes.copyOf()
}

data class AdaptedMeasurementRun(
    val identity: MeasurementComparisonIdentity,
    val run: MeasurementSeriesRun,
)

enum class MeasurementReportAdaptationFailure(val id: String) {
    EMPTY_REPORT("empty-report"),
    REPORT_TOO_LARGE("report-too-large"),
    INVALID_JSON("invalid-json"),
    UNSUPPORTED_SCHEMA("unsupported-schema"),
    INVALID_REPORT("invalid-report"),
    ANDROID_PROFILE_REQUIRED("android-profile-required"),
    UNEXPECTED_ANDROID_PROFILE("unexpected-android-profile"),
    PROFILE_MISMATCH("profile-mismatch"),
}

class MeasurementReportAdaptationException internal constructor(
    val code: MeasurementReportAdaptationFailure,
) : IllegalArgumentException("Measurement report adaptation failed: ${code.id}.")

internal fun failAdaptation(code: MeasurementReportAdaptationFailure): Nothing =
    throw MeasurementReportAdaptationException(code)

object MeasurementReportAdapter {
    fun adapt(request: MeasurementAdaptationRequest): AdaptedMeasurementRun {
        val bytes = request.reportBytes.copyOf()
        if (bytes.isEmpty()) {
            failAdaptation(MeasurementReportAdaptationFailure.EMPTY_REPORT)
        }
        if (bytes.size > MAX_REPORT_BYTES) {
            failAdaptation(MeasurementReportAdaptationFailure.REPORT_TOO_LARGE)
        }
        if (!request.repetitionId.matches(SAFE_TOKEN)) {
            failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
        }
        when (request.family) {
            MeasurementReportFamily.M3U_PARSE -> if (request.androidProfile != null) {
                failAdaptation(MeasurementReportAdaptationFailure.UNEXPECTED_ANDROID_PROFILE)
            }

            MeasurementReportFamily.CATALOG_DATABASE,
            MeasurementReportFamily.PLAYER_PROXY,
            -> if (request.androidProfile == null) {
                failAdaptation(MeasurementReportAdaptationFailure.ANDROID_PROFILE_REQUIRED)
            }
        }

        val root = parseStrictJsonObject(bytes)
        val sourceReportSha256 = sha256(bytes)
        return try {
            when (request.family) {
                MeasurementReportFamily.M3U_PARSE -> adaptM3u(
                    root = root,
                    repetitionId = request.repetitionId,
                    sourceReportSha256 = sourceReportSha256,
                )

                MeasurementReportFamily.CATALOG_DATABASE -> adaptRoom(
                    root = root,
                    repetitionId = request.repetitionId,
                    sourceReportSha256 = sourceReportSha256,
                    profile = requireNotNull(request.androidProfile),
                )

                MeasurementReportFamily.PLAYER_PROXY -> adaptPlayer(
                    root = root,
                    repetitionId = request.repetitionId,
                    sourceReportSha256 = sourceReportSha256,
                    profile = requireNotNull(request.androidProfile),
                )
            }
        } catch (failure: MeasurementReportAdaptationException) {
            throw failure
        } catch (_: Exception) {
            failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)
        }
    }

    private fun adaptM3u(
        root: StrictJsonObject,
        repetitionId: String,
        sourceReportSha256: String,
    ): AdaptedMeasurementRun {
        root.requireExactFields(
            "schemaVersion",
            "methodVersion",
            "thresholdApplied",
            "runnerLabel",
            "sourceCommit",
            "profile",
            "seed",
            "warmupIterations",
            "measuredIterations",
            "measurementScope",
            "corpus",
            "expected",
            "environment",
            "wallTimeSummaryNanos",
            "allocationSummaryBytes",
            "rawSamples",
            "failureCount",
        )
        requireSchema(root)
        requireThresholdFreeSuccess(root)

        val runnerLabel = root.requireString("runnerLabel").requireSafeToken()
        val sourceCommit = root.requireString("sourceCommit").requireSourceCommit()
        val profile = root.requireString("profile")
        if (profile !in M3U_PROFILES) invalidReport()
        val seed = root.requireLong("seed")
        val warmups = root.requireInt("warmupIterations")
        val measuredIterations = root.requireInt("measuredIterations")
        if (warmups !in 0..100 || measuredIterations !in 5..1_000) invalidReport()
        val scope = root.requireString("measurementScope")
        if (scope != M3U_SCOPE) invalidReport()

        val corpus = root.requireObject("corpus")
        corpus.requireExactFields("utf8ByteCount", "sha256")
        val corpusBytes = corpus.requireLong("utf8ByteCount")
        val corpusSha = corpus.requireString("sha256").requireSha256()
        if (corpusBytes <= 0L) invalidReport()

        val expected = root.requireObject("expected")
        expected.requireExactFields("parsedEntries", "skippedEntries", "warningCount")
        val parsedEntries = expected.requireInt("parsedEntries")
        val skippedEntries = expected.requireInt("skippedEntries")
        val warningCount = expected.requireInt("warningCount")
        if (parsedEntries <= 0 || skippedEntries < 0 || warningCount < 0) invalidReport()

        val environment = root.requireObject("environment")
        environment.requireExactFields(
            "osName",
            "osVersion",
            "osArchitecture",
            "jvmVendor",
            "jvmVersion",
            "jvmRuntimeName",
            "availableProcessors",
            "maxHeapBytes",
            "allocationMeasurement",
        )
        val osName = environment.requireString("osName").requireSafeField()
        val osVersion = environment.requireString("osVersion").requireSafeField()
        val architecture = normalizeArchitecture(environment.requireString("osArchitecture"))
        val jvmVendor = environment.requireString("jvmVendor").requireSafeField()
        val jvmVersion = environment.requireString("jvmVersion").requireSafeField()
        val jvmRuntimeName = environment.requireString("jvmRuntimeName").requireSafeField()
        val availableProcessors = environment.requireInt("availableProcessors")
        val maxHeapBytes = environment.requireLong("maxHeapBytes")
        val allocationMeasurement = environment.requireString("allocationMeasurement")
        if (
            availableProcessors <= 0 ||
            maxHeapBytes <= 0L ||
            allocationMeasurement !in setOf("thread-allocated-bytes", "unavailable")
        ) {
            invalidReport()
        }

        val samples = root.requireArray("rawSamples").mapIndexed { index, element ->
            val sample = element.requireObjectValue()
            sample.requireExactFields("iteration", "wallTimeNanos", "allocatedBytes")
            if (sample.requireInt("iteration") != index + 1) invalidReport()
            val wallTime = sample.requireLong("wallTimeNanos")
            val allocation = sample.requireNullableLong("allocatedBytes")
            if (wallTime <= 0L || (allocation != null && allocation < 0L)) invalidReport()
            ParsedM3uSample(wallTime = wallTime, allocatedBytes = allocation)
        }
        if (samples.size != measuredIterations) invalidReport()

        validateSummary(
            summary = parseSummary(root.requireObject("wallTimeSummaryNanos")),
            values = samples.map(ParsedM3uSample::wallTime),
        )
        val allocationSummary = root.requireNullableObject("allocationSummaryBytes")
        if (allocationSummary != null) {
            val allocations = samples.map { it.allocatedBytes ?: invalidReport() }
            validateSummary(parseSummary(allocationSummary), allocations, allowZero = true)
        }

        val identity = MeasurementComparisonIdentity(
            family = MeasurementReportFamily.M3U_PARSE.id,
            schemaVersion = 1,
            methodVersion = 1,
            sourceCommit = sourceCommit,
            fixtureSha256 = corpusSha,
            runnerLabel = runnerLabel,
            apiLevel = null,
            systemImage = null,
            supportedAbis = listOf(architecture),
            configuredRamMb = null,
            cpuCores = availableProcessors,
            lowRamDevice = null,
            memoryClassMb = null,
            buildMode = "jvm-measurement",
            runtimeIdentity = linkedMapOf(
                "os-name" to osName,
                "os-version" to osVersion,
                "os-architecture" to architecture,
                "jvm-vendor" to jvmVendor,
                "jvm-version" to jvmVersion,
                "jvm-runtime-name" to jvmRuntimeName,
                "max-heap-bytes" to maxHeapBytes.toString(),
                "allocation-measurement" to allocationMeasurement,
            ),
            workload = linkedMapOf(
                "profile" to profile,
                "seed" to seed.toString(),
                "warmup-iterations" to warmups.toString(),
                "measured-iterations" to measuredIterations.toString(),
                "measurement-scope" to scope,
                "corpus-utf8-bytes" to corpusBytes.toString(),
                "expected-parsed-entries" to parsedEntries.toString(),
                "expected-skipped-entries" to skippedEntries.toString(),
                "expected-warning-count" to warningCount.toString(),
            ),
        )
        return adaptedRun(
            identity = identity,
            repetitionId = repetitionId,
            sourceReportSha256 = sourceReportSha256,
            operations = linkedMapOf(
                "parse-wall-time" to samples.map(ParsedM3uSample::wallTime),
            ),
        )
    }

    private fun adaptRoom(
        root: StrictJsonObject,
        repetitionId: String,
        sourceReportSha256: String,
        profile: AndroidMeasurementProfileContext,
    ): AdaptedMeasurementRun {
        root.requireExactFields(
            "schemaVersion",
            "methodVersion",
            "buildMode",
            "thresholdApplied",
            "sourceCommit",
            "runnerLabel",
            "cacheState",
            "workload",
            "fixture",
            "environment",
            "operations",
            "failureCount",
            "limitations",
        )
        requireSchema(root)
        requireThresholdFreeSuccess(root)
        val buildMode = root.requireString("buildMode")
        if (buildMode != "debug-instrumentation") invalidReport()
        val sourceCommit = root.requireString("sourceCommit").requireSourceCommit()
        val runnerLabel = root.requireString("runnerLabel").requireSafeToken()
        val cacheState = root.requireString("cacheState")
        if (cacheState != "fresh-file-per-sample") invalidReport()

        val workload = root.requireObject("workload")
        workload.requireExactFields(
            "entryCount",
            "batchSize",
            "firstPageLimit",
            "sourceOverviewCount",
            "warmupIterations",
            "measuredIterations",
        )
        val entryCount = workload.requireInt("entryCount")
        val batchSize = workload.requireInt("batchSize")
        val firstPageLimit = workload.requireInt("firstPageLimit")
        val sourceOverviewCount = workload.requireInt("sourceOverviewCount")
        val warmups = workload.requireInt("warmupIterations")
        val measuredIterations = workload.requireInt("measuredIterations")
        if (
            entryCount <= 0 ||
            batchSize <= 0 ||
            entryCount % batchSize != 0 ||
            firstPageLimit !in 1..entryCount ||
            sourceOverviewCount <= 0 ||
            warmups !in 0..20 ||
            measuredIterations !in 5..100
        ) {
            invalidReport()
        }

        val fixture = root.requireObject("fixture")
        fixture.requireExactFields("entryCount", "sha256")
        if (fixture.requireInt("entryCount") != entryCount) invalidReport()
        val fixtureSha = fixture.requireString("sha256").requireSha256()

        val environment = parseAndroidEnvironment(root.requireObject("environment"))
        validateAndroidProfile(profile, environment)
        val expectedCounts = listOf(batchSize, entryCount, entryCount, firstPageLimit, sourceOverviewCount)
        val operations = parseRoomOperations(
            array = root.requireArray("operations"),
            measuredIterations = measuredIterations,
            expectedCounts = expectedCounts,
        )
        validateLimitations(root.requireArray("limitations"))

        val identity = androidIdentity(
            family = MeasurementReportFamily.CATALOG_DATABASE,
            sourceCommit = sourceCommit,
            fixtureSha = fixtureSha,
            runnerLabel = runnerLabel,
            buildMode = buildMode,
            profile = profile,
            environment = environment,
            runtimeExtras = mapOf("cache-state" to cacheState),
            workload = linkedMapOf(
                "cache-state" to cacheState,
                "entry-count" to entryCount.toString(),
                "batch-size" to batchSize.toString(),
                "first-page-limit" to firstPageLimit.toString(),
                "source-overview-count" to sourceOverviewCount.toString(),
                "warmup-iterations" to warmups.toString(),
                "measured-iterations" to measuredIterations.toString(),
            ),
        )
        return adaptedRun(identity, repetitionId, sourceReportSha256, operations)
    }

    private fun adaptPlayer(
        root: StrictJsonObject,
        repetitionId: String,
        sourceReportSha256: String,
        profile: AndroidMeasurementProfileContext,
    ): AdaptedMeasurementRun {
        root.requireExactFields(
            "schemaVersion",
            "methodVersion",
            "buildMode",
            "thresholdApplied",
            "sourceCommit",
            "runnerLabel",
            "workload",
            "requestProfileSha256",
            "environment",
            "operations",
            "failureCount",
            "limitations",
        )
        requireSchema(root)
        requireThresholdFreeSuccess(root)
        val buildMode = root.requireString("buildMode")
        if (buildMode != "debug-instrumentation") invalidReport()
        val sourceCommit = root.requireString("sourceCommit").requireSourceCommit()
        val runnerLabel = root.requireString("runnerLabel").requireSafeToken()
        val requestProfileSha = root.requireString("requestProfileSha256").requireSha256()

        val workload = root.requireObject("workload")
        workload.requireExactFields("warmupSamples", "measuredSamples", "operationsPerSample")
        val warmupSamples = workload.requireInt("warmupSamples")
        val measuredSamples = workload.requireInt("measuredSamples")
        val operationsPerSample = workload.requireInt("operationsPerSample")
        if (
            warmupSamples !in 0..20 ||
            measuredSamples !in 5..100 ||
            operationsPerSample !in 1..100_000
        ) {
            invalidReport()
        }

        val environment = parseAndroidEnvironment(root.requireObject("environment"))
        validateAndroidProfile(profile, environment)
        val operations = parsePlayerOperations(
            array = root.requireArray("operations"),
            measuredSamples = measuredSamples,
            operationsPerSample = operationsPerSample,
        )
        validateLimitations(root.requireArray("limitations"))

        val identity = androidIdentity(
            family = MeasurementReportFamily.PLAYER_PROXY,
            sourceCommit = sourceCommit,
            fixtureSha = requestProfileSha,
            runnerLabel = runnerLabel,
            buildMode = buildMode,
            profile = profile,
            environment = environment,
            runtimeExtras = emptyMap(),
            workload = linkedMapOf(
                "warmup-samples" to warmupSamples.toString(),
                "measured-samples" to measuredSamples.toString(),
                "operations-per-sample" to operationsPerSample.toString(),
            ),
        )
        return adaptedRun(identity, repetitionId, sourceReportSha256, operations)
    }

    private fun parseRoomOperations(
        array: JsonArray,
        measuredIterations: Int,
        expectedCounts: List<Int>,
    ): Map<String, List<Long>> {
        if (array.size != ROOM_OPERATION_IDS.size) invalidReport()
        return ROOM_OPERATION_IDS.mapIndexed { operationIndex, expectedId ->
            val operation = array[operationIndex].requireObjectValue()
            operation.requireExactFields(
                "operationId",
                "expectedResultCount",
                "wallTimeNanos",
                "databaseBytes",
                "walBytes",
                "shmBytes",
                "rawSamples",
            )
            if (operation.requireString("operationId") != expectedId) invalidReport()
            val expectedCount = operation.requireInt("expectedResultCount")
            if (expectedCount != expectedCounts[operationIndex]) invalidReport()
            val samples = operation.requireArray("rawSamples").mapIndexed { index, element ->
                val sample = element.requireObjectValue()
                sample.requireExactFields(
                    "iteration",
                    "wallTimeNanos",
                    "resultCount",
                    "databaseBytes",
                    "walBytes",
                    "shmBytes",
                )
                if (sample.requireInt("iteration") != index + 1) invalidReport()
                val wallTime = sample.requireLong("wallTimeNanos")
                val resultCount = sample.requireInt("resultCount")
                val databaseBytes = sample.requireLong("databaseBytes")
                val walBytes = sample.requireLong("walBytes")
                val shmBytes = sample.requireLong("shmBytes")
                if (
                    wallTime <= 0L ||
                    resultCount != expectedCount ||
                    databaseBytes < 0L ||
                    walBytes < 0L ||
                    shmBytes < 0L
                ) {
                    invalidReport()
                }
                ParsedRoomSample(wallTime, databaseBytes, walBytes, shmBytes)
            }
            if (samples.size != measuredIterations) invalidReport()
            validateSummary(
                parseSummary(operation.requireObject("wallTimeNanos")),
                samples.map(ParsedRoomSample::wallTime),
            )
            validateSummary(
                parseSummary(operation.requireObject("databaseBytes")),
                samples.map(ParsedRoomSample::databaseBytes),
                allowZero = true,
            )
            validateSummary(
                parseSummary(operation.requireObject("walBytes")),
                samples.map(ParsedRoomSample::walBytes),
                allowZero = true,
            )
            validateSummary(
                parseSummary(operation.requireObject("shmBytes")),
                samples.map(ParsedRoomSample::shmBytes),
                allowZero = true,
            )
            expectedId to samples.map(ParsedRoomSample::wallTime)
        }.toMap(LinkedHashMap())
    }

    private fun parsePlayerOperations(
        array: JsonArray,
        measuredSamples: Int,
        operationsPerSample: Int,
    ): Map<String, List<Long>> {
        if (array.size != PLAYER_OPERATION_IDS.size) invalidReport()
        return PLAYER_OPERATION_IDS.mapIndexed { operationIndex, expectedId ->
            val operation = array[operationIndex].requireObjectValue()
            operation.requireExactFields(
                "operationId",
                "expectedSuccessfulResultCount",
                "batchWallTimeNanos",
                "normalizedNanosPerOperation",
                "rawSamples",
            )
            if (operation.requireString("operationId") != expectedId) invalidReport()
            if (operation.requireInt("expectedSuccessfulResultCount") != operationsPerSample) invalidReport()
            val samples = operation.requireArray("rawSamples").mapIndexed { index, element ->
                val sample = element.requireObjectValue()
                sample.requireExactFields(
                    "sampleIndex",
                    "batchWallTimeNanos",
                    "operationCount",
                    "normalizedNanosPerOperation",
                    "successfulResultCount",
                )
                if (sample.requireInt("sampleIndex") != index + 1) invalidReport()
                val batchWallTime = sample.requireLong("batchWallTimeNanos")
                val operationCount = sample.requireInt("operationCount")
                val normalized = sample.requireLong("normalizedNanosPerOperation")
                val successful = sample.requireInt("successfulResultCount")
                if (
                    batchWallTime <= 0L ||
                    operationCount != operationsPerSample ||
                    successful != operationsPerSample ||
                    normalized != (batchWallTime / operationsPerSample).coerceAtLeast(1L)
                ) {
                    invalidReport()
                }
                ParsedPlayerSample(batchWallTime, normalized)
            }
            if (samples.size != measuredSamples) invalidReport()
            validateSummary(
                parseSummary(operation.requireObject("batchWallTimeNanos")),
                samples.map(ParsedPlayerSample::batchWallTime),
            )
            validateSummary(
                parseSummary(operation.requireObject("normalizedNanosPerOperation")),
                samples.map(ParsedPlayerSample::normalized),
            )
            expectedId to samples.map(ParsedPlayerSample::normalized)
        }.toMap(LinkedHashMap())
    }

    private fun parseAndroidEnvironment(value: StrictJsonObject): AndroidEnvironment {
        value.requireExactFields(
            "manufacturer",
            "model",
            "fingerprint",
            "apiLevel",
            "supportedAbis",
            "lowRamDevice",
            "memoryClassMb",
            "availableProcessors",
        )
        val manufacturer = value.requireString("manufacturer").requireSafeField()
        val model = value.requireString("model").requireSafeField()
        val fingerprint = value.requireString("fingerprint").requireSafeField()
        val apiLevel = value.requireInt("apiLevel")
        val supportedAbis = value.requireArray("supportedAbis")
            .map { normalizeArchitecture(it.requireStringValue()) }
            .distinct()
            .sorted()
        val lowRamDevice = value.requireBoolean("lowRamDevice")
        val memoryClassMb = value.requireInt("memoryClassMb")
        val availableProcessors = value.requireInt("availableProcessors")
        if (
            apiLevel <= 0 ||
            supportedAbis.isEmpty() ||
            memoryClassMb <= 0 ||
            availableProcessors <= 0
        ) {
            invalidReport()
        }
        return AndroidEnvironment(
            manufacturer,
            model,
            fingerprint,
            apiLevel,
            supportedAbis,
            lowRamDevice,
            memoryClassMb,
            availableProcessors,
        )
    }

    private fun validateAndroidProfile(
        profile: AndroidMeasurementProfileContext,
        environment: AndroidEnvironment,
    ) {
        if (
            profile.requestedApiLevel <= 0 ||
            profile.configuredRamMb !in 512..16_384 ||
            profile.configuredCpuCores !in 1..64 ||
            !profile.systemImage.isSafeField()
        ) {
            profileMismatch()
        }
        val match = SYSTEM_IMAGE.matchEntire(profile.systemImage) ?: profileMismatch()
        val imageApi = match.groupValues[1].toIntOrNull() ?: profileMismatch()
        val imageAbi = normalizeArchitecture(match.groupValues[3])
        val expectedFallback = imageApi != profile.requestedApiLevel
        if (
            environment.apiLevel != imageApi ||
            environment.availableProcessors != profile.configuredCpuCores ||
            imageAbi !in environment.supportedAbis ||
            profile.fallbackUsed != expectedFallback
        ) {
            profileMismatch()
        }
    }

    private fun androidIdentity(
        family: MeasurementReportFamily,
        sourceCommit: String,
        fixtureSha: String,
        runnerLabel: String,
        buildMode: String,
        profile: AndroidMeasurementProfileContext,
        environment: AndroidEnvironment,
        runtimeExtras: Map<String, String>,
        workload: Map<String, String>,
    ): MeasurementComparisonIdentity {
        val runtimeIdentity = linkedMapOf(
            "manufacturer" to environment.manufacturer,
            "model" to environment.model,
            "build-fingerprint" to environment.fingerprint,
            "requested-api" to profile.requestedApiLevel.toString(),
            "fallback-used" to profile.fallbackUsed.toString(),
        )
        runtimeIdentity.putAll(runtimeExtras)
        return MeasurementComparisonIdentity(
            family = family.id,
            schemaVersion = 1,
            methodVersion = 1,
            sourceCommit = sourceCommit,
            fixtureSha256 = fixtureSha,
            runnerLabel = runnerLabel,
            apiLevel = environment.apiLevel,
            systemImage = profile.systemImage,
            supportedAbis = environment.supportedAbis,
            configuredRamMb = profile.configuredRamMb,
            cpuCores = profile.configuredCpuCores,
            lowRamDevice = environment.lowRamDevice,
            memoryClassMb = environment.memoryClassMb,
            buildMode = buildMode,
            runtimeIdentity = runtimeIdentity,
            workload = workload,
        )
    }

    private fun adaptedRun(
        identity: MeasurementComparisonIdentity,
        repetitionId: String,
        sourceReportSha256: String,
        operations: Map<String, List<Long>>,
    ): AdaptedMeasurementRun = AdaptedMeasurementRun(
        identity = identity,
        run = MeasurementSeriesRun(
            repetitionId = repetitionId,
            sourceReportSha256 = sourceReportSha256,
            identityFingerprintSha256 = identity.fingerprintSha256,
            thresholdApplied = false,
            failureCount = 0,
            operations = operations,
        ),
    )
}

private data class ParsedSummary(
    val sampleCount: Int,
    val minimum: Long,
    val p50: Long,
    val p90: Long,
    val p95: Long,
    val maximum: Long,
)

private data class ParsedM3uSample(
    val wallTime: Long,
    val allocatedBytes: Long?,
)

private data class ParsedRoomSample(
    val wallTime: Long,
    val databaseBytes: Long,
    val walBytes: Long,
    val shmBytes: Long,
)

private data class ParsedPlayerSample(
    val batchWallTime: Long,
    val normalized: Long,
)

private data class AndroidEnvironment(
    val manufacturer: String,
    val model: String,
    val fingerprint: String,
    val apiLevel: Int,
    val supportedAbis: List<String>,
    val lowRamDevice: Boolean,
    val memoryClassMb: Int,
    val availableProcessors: Int,
)

private fun requireSchema(root: StrictJsonObject) {
    if (root.requireInt("schemaVersion") != 1 || root.requireInt("methodVersion") != 1) {
        failAdaptation(MeasurementReportAdaptationFailure.UNSUPPORTED_SCHEMA)
    }
}

private fun requireThresholdFreeSuccess(root: StrictJsonObject) {
    if (root.requireBoolean("thresholdApplied") || root.requireInt("failureCount") != 0) {
        invalidReport()
    }
}

private fun parseSummary(value: StrictJsonObject): ParsedSummary {
    value.requireExactFields("sampleCount", "minimum", "p50", "p90", "p95", "maximum")
    val summary = ParsedSummary(
        sampleCount = value.requireInt("sampleCount"),
        minimum = value.requireLong("minimum"),
        p50 = value.requireLong("p50"),
        p90 = value.requireLong("p90"),
        p95 = value.requireLong("p95"),
        maximum = value.requireLong("maximum"),
    )
    if (
        summary.sampleCount <= 0 ||
        summary.minimum > summary.p50 ||
        summary.p50 > summary.p90 ||
        summary.p90 > summary.p95 ||
        summary.p95 > summary.maximum
    ) {
        invalidReport()
    }
    return summary
}

private fun validateSummary(
    summary: ParsedSummary,
    values: List<Long>,
    allowZero: Boolean = false,
) {
    if (
        values.isEmpty() ||
        values.size != summary.sampleCount ||
        values.any { if (allowZero) it < 0L else it <= 0L }
    ) {
        invalidReport()
    }
    val sorted = values.sorted()
    if (
        summary.minimum != sorted.first() ||
        summary.p50 != sorted.nearestRank(50) ||
        summary.p90 != sorted.nearestRank(90) ||
        summary.p95 != sorted.nearestRank(95) ||
        summary.maximum != sorted.last()
    ) {
        invalidReport()
    }
}

private fun validateLimitations(values: JsonArray) {
    if (values.isEmpty() || values.size > 32) invalidReport()
    values.forEach { it.requireStringValue().requireSafeField() }
}

private fun List<Long>.nearestRank(percentile: Int): Long {
    val rank = ceil(percentile / 100.0 * size).toInt().coerceIn(1, size)
    return this[rank - 1]
}

private fun String.requireSafeToken(): String {
    if (!matches(SAFE_TOKEN)) invalidReport()
    return this
}

private fun String.requireSourceCommit(): String {
    if (!matches(SOURCE_COMMIT)) invalidReport()
    return this
}

private fun String.requireSha256(): String {
    if (!matches(SHA_256)) invalidReport()
    return this
}

private fun String.requireSafeField(): String {
    if (!isSafeField()) invalidReport()
    return this
}

private fun String.isSafeField(): Boolean =
    isNotBlank() &&
        length <= MAX_SAFE_FIELD_LENGTH &&
        none { character -> character == '\r' || character == '\n' || character.code < 0x20 }

private fun normalizeArchitecture(raw: String): String = when (raw.trim().lowercase(Locale.ROOT)) {
    "amd64", "x64", "x86_64" -> "x86_64"
    "aarch64", "arm64", "arm64-v8a" -> "arm64-v8a"
    "x86" -> "x86"
    else -> invalidReport()
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes)
    .joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

private fun invalidReport(): Nothing =
    failAdaptation(MeasurementReportAdaptationFailure.INVALID_REPORT)

private fun profileMismatch(): Nothing =
    failAdaptation(MeasurementReportAdaptationFailure.PROFILE_MISMATCH)
