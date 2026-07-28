package app.muxtv.player.media3

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PlayerProxyMeasurementRunner(
    context: Context,
    private val nanoTime: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val applicationContext = context.applicationContext

    suspend fun run(spec: PlayerProxyMeasurementSpec): PlayerProxyMeasurementReport =
        withContext(Dispatchers.Default) {
            val fixture = RequestFixture.create()
            val operations = listOf(
                measureOperation(OPERATION_REQUEST_CONSTRUCT, spec.workload) {
                    measureRequestConstruction(spec.workload.operationsPerSample, fixture)
                },
                measureOperation(OPERATION_SETUP_ENVELOPE, spec.workload) { sampleToken ->
                    measureSetupEnvelopeRoundTrip(sampleToken, spec.workload.operationsPerSample, fixture)
                },
                measureOperation(OPERATION_INSTALL_CLEAR, spec.workload) { sampleToken ->
                    measureCoordinatorInstallAndClear(sampleToken, spec.workload.operationsPerSample, fixture)
                },
                measureOperation(OPERATION_CANCEL_BEFORE_INSTALL, spec.workload) { sampleToken ->
                    measureCoordinatorCancelBeforeInstall(sampleToken, spec.workload.operationsPerSample, fixture)
                },
                measureOperation(OPERATION_REGISTRY_RECONNECT, spec.workload) { sampleToken ->
                    measureRegistryDisconnectAndReacquire(sampleToken, spec.workload.operationsPerSample)
                },
            )

            PlayerProxyMeasurementReport(
                schemaVersion = REPORT_SCHEMA_VERSION,
                methodVersion = METHOD_VERSION,
                buildMode = BUILD_MODE,
                thresholdApplied = false,
                sourceCommit = spec.sourceCommit,
                runnerLabel = spec.runnerLabel,
                workload = spec.workload,
                requestProfileSha256 = fixture.sha256,
                environment = captureEnvironment(),
                operations = operations,
                failureCount = 0,
                limitations = LIMITATIONS,
            )
        }

    private fun measureOperation(
        operationId: String,
        workload: PlayerProxyMeasurementWorkload,
        block: (String) -> BatchResult,
    ): PlayerProxyOperationReport {
        repeat(workload.warmupSamples) { index ->
            val result = block("warmup-${index + 1}")
            check(result.successfulResultCount == workload.operationsPerSample) {
                "Player proxy measurement warmup agreement failed."
            }
        }
        val samples = buildList(workload.measuredSamples) {
            repeat(workload.measuredSamples) { index ->
                val result = block("sample-${index + 1}")
                check(result.successfulResultCount == workload.operationsPerSample) {
                    "Player proxy measurement result agreement failed."
                }
                add(
                    PlayerProxyMeasurementSample(
                        sampleIndex = index + 1,
                        batchWallTimeNanos = result.wallTimeNanos,
                        operationCount = workload.operationsPerSample,
                        normalizedNanosPerOperation =
                            (result.wallTimeNanos / workload.operationsPerSample).coerceAtLeast(1L),
                        successfulResultCount = result.successfulResultCount,
                    ),
                )
            }
        }
        return PlayerProxyOperationReport(
            operationId = operationId,
            expectedSuccessfulResultCount = workload.operationsPerSample,
            samples = samples,
            batchWallTimeNanos = PlayerProxyMeasurementStatistics.summarize(
                samples.map(PlayerProxyMeasurementSample::batchWallTimeNanos),
            ),
            normalizedNanosPerOperation = PlayerProxyMeasurementStatistics.summarize(
                samples.map(PlayerProxyMeasurementSample::normalizedNanosPerOperation),
            ),
        )
    }

    private fun measureRequestConstruction(
        operationCount: Int,
        fixture: RequestFixture,
    ): BatchResult {
        var successful = 0
        var retained: PlaybackSessionRequest? = null
        val startedAt = nanoTime()
        repeat(operationCount) { index ->
            val request = fixture.newRequest(index)
            retained = request
            if (
                request.mediaId.isNotEmpty() &&
                request.variantId.isNotEmpty() &&
                request.locator.isNotEmpty() &&
                request.requestHeaders.size == fixture.headerCount &&
                request.displayName != null &&
                request.artworkUri != null
            ) {
                successful += 1
            }
        }
        val completedAt = nanoTime()
        check(retained != null) { "Player proxy request construction agreement failed." }
        return BatchResult(completedAt - startedAt, successful)
    }

    private fun measureSetupEnvelopeRoundTrip(
        sampleToken: String,
        operationCount: Int,
        fixture: RequestFixture,
    ): BatchResult {
        val request = fixture.newRequest(0)
        val ids = setupIds(sampleToken, operationCount)
        var successful = 0
        val startedAt = nanoTime()
        ids.forEach { id ->
            val decoded = MuxTvPlaybackSessionContract.parseSetupArgs(
                MuxTvPlaybackSessionContract.setupArgs(id, request),
            )
            if (decoded?.id == id && decoded.request == request) successful += 1
        }
        val completedAt = nanoTime()
        return BatchResult(completedAt - startedAt, successful)
    }

    private fun measureCoordinatorInstallAndClear(
        sampleToken: String,
        operationCount: Int,
        fixture: RequestFixture,
    ): BatchResult {
        val request = fixture.newRequest(0)
        val ids = setupIds(sampleToken, operationCount)
        var installCallbacks = 0
        var clearCallbacks = 0
        var successful = 0
        val coordinator = PlaybackSetupCoordinator<PlaybackSessionRequest>(
            install = { installed -> if (installed === request) installCallbacks += 1 },
            clearInstalled = { clearCallbacks += 1 },
        )
        val startedAt = nanoTime()
        ids.forEach { id ->
            val installed = coordinator.install(id, request)
            val cleared = coordinator.cancel(id)
            if (
                installed == PlaybackSetupInstallResult.Installed &&
                cleared == PlaybackSetupCancelResult.ActiveCleared
            ) {
                successful += 1
            }
        }
        val completedAt = nanoTime()
        check(installCallbacks == operationCount && clearCallbacks == operationCount) {
            "Player proxy active setup callback agreement failed."
        }
        return BatchResult(completedAt - startedAt, successful)
    }

    private fun measureCoordinatorCancelBeforeInstall(
        sampleToken: String,
        operationCount: Int,
        fixture: RequestFixture,
    ): BatchResult {
        val request = fixture.newRequest(0)
        val ids = setupIds(sampleToken, operationCount)
        var installCallbacks = 0
        var clearCallbacks = 0
        var successful = 0
        val coordinator = PlaybackSetupCoordinator<PlaybackSessionRequest>(
            install = { installCallbacks += 1 },
            clearInstalled = { clearCallbacks += 1 },
        )
        val startedAt = nanoTime()
        ids.forEach { id ->
            val cancelled = coordinator.cancel(id)
            val installResult = coordinator.install(id, request)
            if (
                cancelled == PlaybackSetupCancelResult.PendingCancelled &&
                installResult == PlaybackSetupInstallResult.Cancelled
            ) {
                successful += 1
            }
        }
        val completedAt = nanoTime()
        check(installCallbacks == 0 && clearCallbacks == 0) {
            "Player proxy cancelled setup callback agreement failed."
        }
        return BatchResult(completedAt - startedAt, successful)
    }

    private fun measureRegistryDisconnectAndReacquire(
        sampleToken: String,
        operationCount: Int,
    ): BatchResult {
        val fixtures = List(operationCount) { index ->
            val first = SyntheticController("$sampleToken-first-$index")
            val second = SyntheticController("$sampleToken-second-$index")
            RegistryFixture(
                first = first,
                second = second,
                firstFuture = Futures.immediateFuture(first),
                secondFuture = Futures.immediateFuture(second),
            )
        }
        var successful = 0
        var releasedPending = 0
        var releasedConnected = 0
        val registry = ControllerConnectionRegistry<SyntheticController>(
            releasePending = { releasedPending += 1 },
            releaseConnected = { releasedConnected += 1 },
        )
        val startedAt = nanoTime()
        fixtures.forEach { fixture ->
            val firstAcquire = registry.acquire { fixture.firstFuture }
            registry.complete(firstAcquire, Result.success(fixture.first))
            val firstDisconnected = registry.disconnected(fixture.first)
            val secondAcquire = registry.acquire { fixture.secondFuture }
            registry.complete(secondAcquire, Result.success(fixture.second))
            val secondDisconnected = registry.disconnected(fixture.second)
            if (
                firstAcquire === fixture.firstFuture &&
                secondAcquire === fixture.secondFuture &&
                firstDisconnected &&
                secondDisconnected
            ) {
                successful += 1
            }
        }
        val completedAt = nanoTime()
        registry.close()
        check(releasedPending == 0 && releasedConnected == 0) {
            "Player proxy registry release agreement failed."
        }
        return BatchResult(completedAt - startedAt, successful)
    }

    private fun setupIds(sampleToken: String, count: Int): List<PlaybackSetupId> =
        List(count) { index ->
            requireNotNull(
                PlaybackSetupId.parse("$sampleToken-${index.toString().padStart(5, '0')}"),
            ) { "Player proxy setup identity preparation failed." }
        }

    private fun captureEnvironment(): PlayerProxyMeasurementEnvironment {
        val activityManager = requireNotNull(
            applicationContext.getSystemService(ActivityManager::class.java),
        ) { "Android activity manager is unavailable." }
        return PlayerProxyMeasurementEnvironment(
            manufacturer = Build.MANUFACTURER.safeEnvironmentValue(),
            model = Build.MODEL.safeEnvironmentValue(),
            fingerprint = Build.FINGERPRINT.safeEnvironmentValue(MAX_FINGERPRINT_LENGTH),
            apiLevel = Build.VERSION.SDK_INT,
            supportedAbis = Build.SUPPORTED_ABIS.map { abi -> abi.safeEnvironmentValue() },
            lowRamDevice = activityManager.isLowRamDevice,
            memoryClassMb = activityManager.memoryClass,
            availableProcessors = Runtime.getRuntime().availableProcessors(),
        )
    }

    private fun String.safeEnvironmentValue(maxLength: Int = MAX_ENVIRONMENT_VALUE_LENGTH): String =
        trim().take(maxLength).ifBlank { "unknown" }

    private data class BatchResult(
        val wallTimeNanos: Long,
        val successfulResultCount: Int,
    ) {
        init {
            require(wallTimeNanos > 0L)
            require(successfulResultCount >= 0)
        }
    }

    private data class RegistryFixture(
        val first: SyntheticController,
        val second: SyntheticController,
        val firstFuture: ListenableFuture<SyntheticController>,
        val secondFuture: ListenableFuture<SyntheticController>,
    )

    private class SyntheticController(private val identity: String) {
        init {
            require(identity.isNotBlank())
        }

        override fun toString(): String = "SyntheticController(<redacted>)"
    }

    private class RequestFixture private constructor(
        private val mediaIdPrefix: String,
        private val variantIdPrefix: String,
        private val locator: String,
        private val displayName: String,
        private val artworkUri: String,
        private val headers: Map<String, String>,
        private val insecureHttpApproved: Boolean,
        val sha256: String,
    ) {
        val headerCount: Int = headers.size

        fun newRequest(index: Int): PlaybackSessionRequest = PlaybackSessionRequest(
            mediaId = "$mediaIdPrefix-${index % ID_VARIANTS}",
            variantId = "$variantIdPrefix-${index % ID_VARIANTS}",
            locator = locator,
            displayName = displayName,
            artworkUri = artworkUri,
            requestHeaders = headers,
            insecureHttpApproved = insecureHttpApproved,
        )

        companion object {
            fun create(): RequestFixture {
                val mediaIdPrefix = "measurement-channel"
                val variantIdPrefix = "measurement-variant"
                val locator = "https://stream.example/player/live.m3u8"
                val displayName = "Synthetic Player Channel"
                val artworkUri = "https://images.example/player/channel.png"
                val headers = linkedMapOf(
                    "Referer" to "https://portal.example/player",
                    "User-Agent" to "MuxTV-Player-Measurement/1",
                ).toMap()
                val profile = List(ID_VARIANTS) { index ->
                    PlaybackSessionRequest(
                        mediaId = "$mediaIdPrefix-$index",
                        variantId = "$variantIdPrefix-$index",
                        locator = locator,
                        displayName = displayName,
                        artworkUri = artworkUri,
                        requestHeaders = headers,
                        insecureHttpApproved = false,
                    )
                }
                return RequestFixture(
                    mediaIdPrefix = mediaIdPrefix,
                    variantIdPrefix = variantIdPrefix,
                    locator = locator,
                    displayName = displayName,
                    artworkUri = artworkUri,
                    headers = headers,
                    insecureHttpApproved = false,
                    sha256 = PlayerProxyRequestProfileDigest.sha256(profile),
                )
            }
        }
    }

    private companion object {
        const val REPORT_SCHEMA_VERSION = 1
        const val METHOD_VERSION = 1
        const val BUILD_MODE = "debug-instrumentation"
        const val OPERATION_REQUEST_CONSTRUCT = "request-construct"
        const val OPERATION_SETUP_ENVELOPE = "setup-envelope-roundtrip"
        const val OPERATION_INSTALL_CLEAR = "coordinator-install-active-clear"
        const val OPERATION_CANCEL_BEFORE_INSTALL = "coordinator-cancel-before-install"
        const val OPERATION_REGISTRY_RECONNECT = "registry-disconnect-reacquire"
        const val ID_VARIANTS = 32
        const val MAX_ENVIRONMENT_VALUE_LENGTH = 128
        const val MAX_FINGERPRINT_LENGTH = 256
        val LIMITATIONS = listOf(
            "Descriptive repository control-plane proxy evidence for the exact recorded environment only.",
            "No ExoPlayer, MediaSource, network, Binder service, UI or Surface work is measured.",
            "Not a decoder, buffering, zapping, first-frame, Fire OS or physical weak-TV claim.",
        )
    }
}
