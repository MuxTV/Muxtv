package app.muxtv.player.media3

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.benchmark.competitive.CompetitiveDriverKind
import app.muxtv.benchmark.competitive.CompetitiveEnvironment
import app.muxtv.benchmark.competitive.CompetitiveExecutionPhase
import app.muxtv.benchmark.competitive.CompetitiveExecutionPlanner
import app.muxtv.benchmark.competitive.CompetitiveScenario
import app.muxtv.benchmark.competitive.CompetitiveScenarioVariant
import app.muxtv.benchmark.competitive.CompetitiveVariant
import app.muxtv.benchmark.competitive.CorpusProvenance
import app.muxtv.benchmark.competitive.RepositoryPin
import app.muxtv.player.PlaybackRuntimeTransport
import app.muxtv.testing.media.C09PlaybackCorpus
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C10 deterministic emulator evidence for Media3 streaming LoadControl A/B/C.
 *
 * This deliberately does not modify [MuxTvPlaybackService]. The instrumentation player uses the
 * production renderer policy and only substitutes the preregistered C10 LoadControl variants. The
 * synthetic AVC bytes are reused from the immutable C09 corpus for provenance; C09 renderer-policy
 * measurements are not rerun or used as C10 performance evidence.
 */
@RunWith(AndroidJUnit4::class)
@C10Media3LoadControlEvidence
@AndroidXOptIn(UnstableApi::class)
class C10Media3LoadControlEvidenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun loadControlVariantsProduceCorrectnessFirstInterleavedEvidence() {
        assertThat(PRODUCTION_MEDIA3_LOAD_CONTROL_VARIANT)
            .isEqualTo(Media3LoadControlVariant.A_CURRENT)
        assertThat(PRODUCTION_MEDIA3_RENDERER_VARIANT)
            .isEqualTo(Media3RendererVariant.A_CURRENT)
        assertThat(C09PlaybackCorpus.verifyIntegrity()).isEmpty()

        val sourceSha = requireSourceSha()
        val source = RepositoryPin(REPOSITORY, sourceSha)
        val corpus = CorpusProvenance(
            manifestSha256 = C09PlaybackCorpus.manifestSha256,
            contentSha256 = C09PlaybackCorpus.contentSha256,
        )
        val corpusDirectory = context.cacheDir.resolve("c10-media3-load-control-corpus").apply {
            deleteRecursively()
            check(mkdirs()) { "unable to create C10 evidence corpus directory" }
        }
        val materialized = C09PlaybackCorpus.materialize(corpusDirectory)
        val avcTs = checkNotNull(
            materialized.singleOrNull { it.fixture.id == "raw-avc-360p30" },
        ).entrypoint.readBytes()

        val a = media3LoadControlSettings(Media3LoadControlVariant.A_CURRENT)
        val b = media3LoadControlSettings(Media3LoadControlVariant.B_LOWER_START_500MS)
        val c = media3LoadControlSettings(
            Media3LoadControlVariant.C_LOWER_START_500MS_REBUFFER_3000MS,
        )
        Log.i(
            TAG,
            listOf(
                "C10_ENV",
                "source_sha=$sourceSha",
                "manifest_sha256=${corpus.manifestSha256}",
                "content_sha256=${corpus.contentSha256}",
                "api=${Build.VERSION.SDK_INT}",
                "model=${safeField(Build.MODEL)}",
                "fingerprint_sha256=${sha256(Build.FINGERPRINT)}",
                "production_load_control=${PRODUCTION_MEDIA3_LOAD_CONTROL_VARIANT.name}",
                "production_renderer=${PRODUCTION_MEDIA3_RENDERER_VARIANT.name}",
                "a=${a.minBufferMs}/${a.maxBufferMs}/${a.bufferForPlaybackMs}/${a.bufferForPlaybackAfterRebufferMs}",
                "b=${b.minBufferMs}/${b.maxBufferMs}/${b.bufferForPlaybackMs}/${b.bufferForPlaybackAfterRebufferMs}",
                "c=${c.minBufferMs}/${c.maxBufferMs}/${c.bufferForPlaybackMs}/${c.bufferForPlaybackAfterRebufferMs}",
                "evidence_authority=emulator_non_vendor",
            ).joinToString("\t"),
        )

        val failures = mutableListOf<String>()
        ActivityScenario.launch(C10Media3EvidenceActivity::class.java).use { activityScenario ->
            val surface = activityScenario.awaitSurface()
            PlayerRunner(context, surface, avcTs).use { runner ->
                C10_SCENARIOS.forEachIndexed { scenarioIndex, networkScenario ->
                    val environment = environment(networkScenario)
                    val scenario = competitiveScenario(
                        networkScenario = networkScenario,
                        scenarioIndex = scenarioIndex,
                        source = source,
                        corpus = corpus,
                    )
                    Log.i(
                        TAG,
                        listOf(
                            "C10_SCENARIO",
                            "scenario=${networkScenario.id}",
                            "transport=${networkScenario.transport.name}",
                            "network_profile=${networkScenario.networkProfile}",
                            "segments=${networkScenario.segmentCount}",
                            "first_segment_delay_ms=${networkScenario.firstSegmentDelayMs}",
                            "throttle_period_ms=${networkScenario.throttlePeriodMs}",
                            "stall_segment=${networkScenario.stallSegmentIndex ?: -1}",
                            "stall_delay_ms=${networkScenario.stallBodyDelayMs}",
                            "seed=${scenario.seed}",
                            "environment_sha256=${environment.fingerprintSha256}",
                        ).joinToString("\t"),
                    )

                    val correctnessSamples = linkedMapOf<CompetitiveVariant, C10Sample>()
                    CompetitiveExecutionPlanner.planCorrectness(scenario).forEach { slot ->
                        val sample = runner.run(
                            scenario = networkScenario,
                            phase = slot.phase,
                            round = slot.round,
                            ordinal = slot.ordinal,
                            variant = slot.variant,
                        )
                        correctnessSamples[slot.variant] = sample
                        logSample(sample)
                    }

                    val correctness = correctnessSamples.mapValues { (_, sample) -> sample.success }
                    val correctnessFailures = correctness.filterValues { passed -> !passed }.keys
                    if (correctnessFailures.isNotEmpty()) {
                        failures += "${networkScenario.id}:correctness:${correctnessFailures.joinToString(",") { it.name }}"
                        Log.i(
                            TAG,
                            "C10_SCENARIO_SKIPPED\tscenario=${networkScenario.id}\treason=correctness_gate\tvariants=" +
                                correctnessFailures.joinToString(",") { it.name },
                        )
                        return@forEachIndexed
                    }

                    val performanceSamples = mutableListOf<C10Sample>()
                    CompetitiveExecutionPlanner.planPerformance(scenario, correctness).forEach { slot ->
                        val sample = runner.run(
                            scenario = networkScenario,
                            phase = slot.phase,
                            round = slot.round,
                            ordinal = slot.ordinal,
                            variant = slot.variant,
                        )
                        performanceSamples += sample
                        logSample(sample)
                    }

                    val unstable = performanceSamples.filterNot(C10Sample::success)
                    if (unstable.isNotEmpty()) {
                        failures += "${networkScenario.id}:performance:${unstable.size}"
                    }

                    val measuredByVariant = performanceSamples
                        .filter { it.phase == CompetitiveExecutionPhase.MEASURED }
                        .groupBy(C10Sample::variant)
                    val countsValid = scenario.variants.all { variant ->
                        measuredByVariant[variant.id]?.size == MEASURED_ROUNDS
                    }
                    if (!countsValid) {
                        failures += "${networkScenario.id}:measured-count"
                        return@forEachIndexed
                    }

                    val summaries = scenario.variants.associate { variantSpec ->
                        val samples = checkNotNull(measuredByVariant[variantSpec.id])
                        variantSpec.id to summarize(samples)
                    }
                    val baseline = checkNotNull(summaries[CompetitiveVariant.A])
                    scenario.variants.forEach { variantSpec ->
                        val summary = checkNotNull(summaries[variantSpec.id])
                        Log.i(
                            TAG,
                            listOf(
                                "C10_REPORT",
                                "scenario=${networkScenario.id}",
                                "variant=${variantSpec.id.name}",
                                "samples=${summary.sampleCount}",
                                "first_frame_p50_ms=${summary.firstFrameP50Ms}",
                                "first_frame_p95_ms=${summary.firstFrameP95Ms}",
                                "first_frame_delta_bp=${deltaBasisPoints(summary.firstFrameP50Ms, baseline.firstFrameP50Ms)}",
                                "initial_buffer_p50_ms=${summary.initialBufferingP50Ms}",
                                "initial_buffer_p95_ms=${summary.initialBufferingP95Ms}",
                                "initial_buffer_delta_bp=${deltaBasisPoints(summary.initialBufferingP50Ms, baseline.initialBufferingP50Ms)}",
                                "rebuffer_incidence_bp=${summary.rebufferIncidenceBasisPoints}",
                                "rebuffer_count_total=${summary.rebufferCountTotal}",
                                "rebuffer_ms_total=${summary.completedRebufferDurationTotalMs}",
                                "peak_pss_p50_kb=${summary.peakPssP50Kb}",
                                "peak_pss_p95_kb=${summary.peakPssP95Kb}",
                                "pss_growth_max_kb=${summary.pssGrowthMaxKb}",
                                "playback_failures=${summary.playbackFailures}",
                                "player_errors=${summary.playerErrors}",
                                "timeouts=${summary.timeouts}",
                                "stale_generation_failures=${summary.staleGenerationFailures}",
                                "environment_sha256=${environment.fingerprintSha256}",
                            ).joinToString("\t"),
                        )
                    }
                }
            }
        }

        assertThat(failures).isEmpty()
    }

    private fun competitiveScenario(
        networkScenario: C10NetworkScenario,
        scenarioIndex: Int,
        source: RepositoryPin,
        corpus: CorpusProvenance,
    ): CompetitiveScenario = CompetitiveScenario(
        schemaVersion = 1,
        scenarioId = "c10-${networkScenario.id}",
        seed = BASE_SEED + scenarioIndex,
        warmupRounds = WARMUP_ROUNDS,
        measuredRounds = MEASURED_ROUNDS,
        baselineVariant = CompetitiveVariant.A,
        corpus = corpus,
        variants = listOf(
            variant(CompetitiveVariant.A, "A_CURRENT", source),
            variant(CompetitiveVariant.B, "B_LOWER_START_500MS", source),
            variant(
                CompetitiveVariant.C,
                "C_LOWER_START_500MS_REBUFFER_3000MS",
                source,
            ),
        ),
    )

    private fun variant(
        id: CompetitiveVariant,
        label: String,
        source: RepositoryPin,
    ): CompetitiveScenarioVariant = CompetitiveScenarioVariant(
        id = id,
        label = label,
        source = source,
        driverKind = CompetitiveDriverKind.EXTERNAL,
        benchmarkRef = "c10-media3-load-control-policy",
    )

    private fun environment(scenario: C10NetworkScenario): CompetitiveEnvironment =
        CompetitiveEnvironment(
            schemaVersion = 1,
            environmentId = "android-tv-api-${Build.VERSION.SDK_INT}-${scenario.id}",
            buildMode = "debug",
            baselineProfileState = "not-applied",
            networkProfile = scenario.networkProfile,
            runtime = mapOf(
                "android_sdk" to Build.VERSION.SDK_INT.toString(),
                "android_release" to safeField(Build.VERSION.RELEASE),
                "vm_version" to safeField(System.getProperty("java.vm.version") ?: "unknown"),
                "segment_count" to scenario.segmentCount.toString(),
                "first_segment_delay_ms" to scenario.firstSegmentDelayMs.toString(),
                "throttle_period_ms" to scenario.throttlePeriodMs.toString(),
                "stall_segment" to (scenario.stallSegmentIndex ?: -1).toString(),
                "stall_delay_ms" to scenario.stallBodyDelayMs.toString(),
            ),
            device = mapOf(
                "brand" to safeField(Build.BRAND),
                "model" to safeField(Build.MODEL),
                "product" to safeField(Build.PRODUCT),
                "hardware" to safeField(Build.HARDWARE),
                "fingerprint_sha256" to sha256(Build.FINGERPRINT),
            ),
        )

    private fun requireSourceSha(): String {
        val value = InstrumentationRegistry.getArguments().getString("c10SourceSha")
            ?: error("c10SourceSha instrumentation argument is required")
        require(value.matches(Regex("[0-9a-f]{40}"))) {
            "c10SourceSha must be an exact lowercase 40-hex commit"
        }
        return value
    }

    private fun ActivityScenario<C10Media3EvidenceActivity>.awaitSurface(): Surface {
        val latch = CountDownLatch(1)
        val surfaceRef = AtomicReference<Surface>()
        onActivity { activity ->
            val holder = activity.surfaceView.holder
            val current = holder.surface
            if (current != null && current.isValid) {
                surfaceRef.set(current)
                latch.countDown()
            } else {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(createdHolder: SurfaceHolder) {
                            surfaceRef.set(createdHolder.surface)
                            latch.countDown()
                            createdHolder.removeCallback(this)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) = Unit

                        override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                    },
                )
            }
        }
        check(latch.await(SURFACE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "C10 evidence surface creation timed out"
        }
        return checkNotNull(surfaceRef.get()).also { surface ->
            check(surface.isValid) { "C10 evidence surface is invalid" }
        }
    }

    private fun logSample(sample: C10Sample) {
        Log.i(
            TAG,
            listOf(
                "C10_SAMPLE",
                "scenario=${sample.scenarioId}",
                "phase=${sample.phase.name}",
                "round=${sample.round}",
                "ordinal=${sample.ordinal}",
                "variant=${sample.variant.name}",
                "success=${sample.success}",
                "outcome=${sample.outcome}",
                "first_frame_ms=${sample.firstFrameLatencyMillis ?: -1L}",
                "initial_buffer_ms=${sample.initialBufferingDurationMillis ?: -1L}",
                "rebuffer_count=${sample.rebufferCount}",
                "rebuffer_ms=${sample.completedRebufferDurationMillis}",
                "peak_pss_kb=${sample.peakPssKb}",
                "pss_growth_kb=${sample.pssGrowthKb}",
                "player_error_code=${sample.playerErrorCode ?: -1}",
                "timed_out=${sample.timedOut}",
                "stale_generation_safe=${sample.staleGenerationSafe}",
            ).joinToString("\t"),
        )
    }

    private inner class PlayerRunner(
        private val context: Context,
        private val surface: Surface,
        private val avcTs: ByteArray,
    ) : Closeable {
        private val thread = HandlerThread("c10-media3-load-control-evidence").apply { start() }
        private val handler = Handler(thread.looper)
        private var generation = 0L

        fun run(
            scenario: C10NetworkScenario,
            phase: CompetitiveExecutionPhase,
            round: Int,
            ordinal: Int,
            variant: CompetitiveVariant,
        ): C10Sample = C10Origin.start(scenario, avcTs).use { origin ->
            generation += 1L
            val runGeneration = generation
            val runtimeTransport = when (scenario.transport) {
                C10Transport.RAW_TS -> PlaybackRuntimeTransport.MPEG_TS_LIVE
                C10Transport.HLS -> PlaybackRuntimeTransport.HLS
            }
            val state = PlaybackRuntimeMeasurementState().apply {
                activate(
                    generation = runGeneration,
                    transport = runtimeTransport,
                    deviceSummary = null,
                )
            }
            val completion = CountDownLatch(1)
            val errorRef = AtomicReference<PlaybackException?>()
            val startedAtNanos = AtomicReference<Long?>()
            val initialReadyMillis = AtomicReference<Long?>()
            val firstFramePssKb = AtomicLong(-1L)
            val ended = AtomicBoolean(false)
            val pssAtStartKb = currentPssKb()
            lateinit var player: ExoPlayer

            onPlayerThread {
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        val started = startedAtNanos.get() ?: return
                        val elapsed = elapsedMillisSince(started)
                        state.onFirstFrame(runGeneration, elapsed)
                        firstFramePssKb.compareAndSet(-1L, currentPssKb())
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errorRef.compareAndSet(null, error)
                        completion.countDown()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            val started = startedAtNanos.get()
                            if (started != null) {
                                initialReadyMillis.compareAndSet(null, elapsedMillisSince(started))
                            }
                        }
                        if (playbackState == Player.STATE_ENDED) {
                            ended.set(true)
                            completion.countDown()
                        }
                    }
                }
                player = ExoPlayer.Builder(
                    context,
                    createMedia3RenderersFactory(context, PRODUCTION_MEDIA3_RENDERER_VARIANT),
                )
                    .setLoadControl(createMedia3LoadControl(variant.toLoadControlVariant()))
                    .setLooper(thread.looper)
                    .build()
                player.addAnalyticsListener(
                    PlaybackRuntimeAnalyticsListener(
                        state = state,
                        eventGeneration = { runGeneration },
                    ),
                )
                player.addListener(listener)
                player.setVideoSurface(surface)
                startedAtNanos.set(SystemClock.elapsedRealtimeNanos())
                player.setMediaItem(MediaItem.fromUri(origin.entrypointUrl()))
                player.prepare()
                player.play()
            }

            val completed = completion.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val snapshotBeforeStaleProbe = state.snapshot()
            val pssAtEndKb = currentPssKb()
            state.onFirstFrame(runGeneration + 1L, 1L)
            state.onBuffering(runGeneration + 1L, 1L)
            state.onReady(runGeneration + 1L, 2L)
            val staleGenerationSafe = snapshotBeforeStaleProbe == state.snapshot()
            val playerError = errorRef.get()
            onPlayerThread { player.release() }
            val pssAfterReleaseKb = currentPssKb()

            val snapshot = snapshotBeforeStaleProbe
            val firstFrame = snapshot?.firstFrameLatencyMillis
            val decoderName = snapshot?.videoDecoderName
            val peakPssKb = maxOf(
                pssAtStartKb,
                firstFramePssKb.get().coerceAtLeast(0L),
                pssAtEndKb,
                pssAfterReleaseKb,
            )
            val pssGrowthKb = (peakPssKb - pssAtStartKb).coerceAtLeast(0L)
            val memoryBounded = pssGrowthKb <= MAX_PSS_GROWTH_KB
            val success = completed &&
                ended.get() &&
                firstFrame != null &&
                initialReadyMillis.get() != null &&
                decoderName != null &&
                playerError == null &&
                (snapshot?.decoderInitializationFailureCount ?: 0) == 0 &&
                staleGenerationSafe &&
                memoryBounded
            val outcome = when {
                !completed -> "completion-timeout"
                playerError != null -> "player-error"
                !ended.get() -> "not-ended"
                firstFrame == null -> "no-first-frame"
                initialReadyMillis.get() == null -> "no-initial-ready"
                decoderName == null -> "decoder-not-observed"
                !staleGenerationSafe -> "stale-generation-mutation"
                !memoryBounded -> "memory-bound-exceeded"
                else -> "useful-playback"
            }

            C10Sample(
                scenarioId = scenario.id,
                phase = phase,
                round = round,
                ordinal = ordinal,
                variant = variant,
                success = success,
                outcome = outcome,
                firstFrameLatencyMillis = firstFrame,
                initialBufferingDurationMillis = initialReadyMillis.get(),
                rebufferCount = snapshot?.rebufferCount ?: 0,
                completedRebufferDurationMillis =
                    snapshot?.completedRebufferDurationMillis ?: 0L,
                peakPssKb = peakPssKb,
                pssGrowthKb = pssGrowthKb,
                playerErrorCode = playerError?.errorCode,
                timedOut = !completed,
                staleGenerationSafe = staleGenerationSafe,
            )
        }

        private fun elapsedMillisSince(startedAtNanos: Long): Long {
            val elapsedNanos = (SystemClock.elapsedRealtimeNanos() - startedAtNanos)
                .coerceAtLeast(1L)
            return ((elapsedNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
        }

        private fun onPlayerThread(block: () -> Unit) {
            val latch = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>()
            handler.post {
                try {
                    block()
                } catch (throwable: Throwable) {
                    failure.set(throwable)
                } finally {
                    latch.countDown()
                }
            }
            check(latch.await(PLAYER_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "C10 player-thread operation timed out"
            }
            failure.get()?.let { throw it }
        }

        override fun close() {
            thread.quitSafely()
            thread.join(TimeUnit.SECONDS.toMillis(PLAYER_THREAD_TIMEOUT_SECONDS))
            check(!thread.isAlive) { "C10 player thread did not terminate" }
        }
    }

    private class C10Origin private constructor(
        private val scenario: C10NetworkScenario,
        private val mediaBytes: ByteArray,
    ) : Closeable {
        private val server = MockWebServer().also { mockServer ->
            mockServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/live.m3u8" -> textResponse(hlsPlaylist())
                        path == "/stream.ts" -> mediaResponse(segmentIndex = 0)
                        path.startsWith("/segment-") && path.endsWith(".ts") -> {
                            val segmentIndex = path
                                .removePrefix("/segment-")
                                .removeSuffix(".ts")
                                .toIntOrNull()
                            if (segmentIndex == null || segmentIndex !in 0 until scenario.segmentCount) {
                                MockResponse.Builder().code(404).build()
                            } else {
                                mediaResponse(segmentIndex)
                            }
                        }
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
        }

        fun entrypointUrl(): String = when (scenario.transport) {
            C10Transport.RAW_TS -> server.url("/stream.ts").toString()
            C10Transport.HLS -> server.url("/live.m3u8").toString()
        }

        private fun textResponse(body: String): MockResponse = MockResponse.Builder()
            .code(200)
            .addHeader("Content-Type", "application/x-mpegURL")
            .body(body)
            .build()

        private fun mediaResponse(segmentIndex: Int): MockResponse {
            var builder = MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "video/mp2t")
                .addHeader("Content-Length", mediaBytes.size.toString())
                .body(Buffer().write(mediaBytes))

            val bodyDelayMillis = when {
                segmentIndex == 0 -> scenario.firstSegmentDelayMs
                segmentIndex == scenario.stallSegmentIndex -> scenario.stallBodyDelayMs
                else -> 0L
            }
            if (bodyDelayMillis > 0L) {
                builder = builder.bodyDelay(bodyDelayMillis, TimeUnit.MILLISECONDS)
            }
            if (scenario.throttlePeriodMs > 0L) {
                val bytesPerPeriod = ((mediaBytes.size.toLong() + THROTTLE_PERIOD_COUNT - 1L) /
                    THROTTLE_PERIOD_COUNT).coerceAtLeast(188L)
                builder = builder.throttleBody(
                    bytesPerPeriod,
                    scenario.throttlePeriodMs,
                    TimeUnit.MILLISECONDS,
                )
            }
            return builder.build()
        }

        private fun hlsPlaylist(): String = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:1")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            repeat(scenario.segmentCount) { index ->
                if (index > 0) appendLine("#EXT-X-DISCONTINUITY")
                appendLine("#EXTINF:1.000,")
                appendLine("segment-$index.ts")
            }
            appendLine("#EXT-X-ENDLIST")
        }

        override fun close() {
            server.close()
        }

        companion object {
            fun start(
                scenario: C10NetworkScenario,
                mediaBytes: ByteArray,
            ): C10Origin = C10Origin(scenario, mediaBytes).also { it.server.start() }
        }
    }

    private data class C10Sample(
        val scenarioId: String,
        val phase: CompetitiveExecutionPhase,
        val round: Int,
        val ordinal: Int,
        val variant: CompetitiveVariant,
        val success: Boolean,
        val outcome: String,
        val firstFrameLatencyMillis: Long?,
        val initialBufferingDurationMillis: Long?,
        val rebufferCount: Int,
        val completedRebufferDurationMillis: Long,
        val peakPssKb: Long,
        val pssGrowthKb: Long,
        val playerErrorCode: Int?,
        val timedOut: Boolean,
        val staleGenerationSafe: Boolean,
    )

    private data class C10Summary(
        val sampleCount: Int,
        val firstFrameP50Ms: Long,
        val firstFrameP95Ms: Long,
        val initialBufferingP50Ms: Long,
        val initialBufferingP95Ms: Long,
        val rebufferIncidenceBasisPoints: Long,
        val rebufferCountTotal: Long,
        val completedRebufferDurationTotalMs: Long,
        val peakPssP50Kb: Long,
        val peakPssP95Kb: Long,
        val pssGrowthMaxKb: Long,
        val playbackFailures: Int,
        val playerErrors: Int,
        val timeouts: Int,
        val staleGenerationFailures: Int,
    )

    private fun summarize(samples: List<C10Sample>): C10Summary {
        check(samples.isNotEmpty())
        val firstFrames = samples.map { checkNotNull(it.firstFrameLatencyMillis) }
        val initialBuffering = samples.map { checkNotNull(it.initialBufferingDurationMillis) }
        val peakPss = samples.map(C10Sample::peakPssKb)
        return C10Summary(
            sampleCount = samples.size,
            firstFrameP50Ms = percentile(firstFrames, 0.50),
            firstFrameP95Ms = percentile(firstFrames, 0.95),
            initialBufferingP50Ms = percentile(initialBuffering, 0.50),
            initialBufferingP95Ms = percentile(initialBuffering, 0.95),
            rebufferIncidenceBasisPoints =
                samples.count { it.rebufferCount > 0 }.toLong() * 10_000L / samples.size,
            rebufferCountTotal = samples.sumOf { it.rebufferCount.toLong() },
            completedRebufferDurationTotalMs =
                samples.sumOf(C10Sample::completedRebufferDurationMillis),
            peakPssP50Kb = percentile(peakPss, 0.50),
            peakPssP95Kb = percentile(peakPss, 0.95),
            pssGrowthMaxKb = samples.maxOf(C10Sample::pssGrowthKb),
            playbackFailures = samples.count { !it.success },
            playerErrors = samples.count { it.playerErrorCode != null },
            timeouts = samples.count(C10Sample::timedOut),
            staleGenerationFailures = samples.count { !it.staleGenerationSafe },
        )
    }

    private fun percentile(values: List<Long>, quantile: Double): Long {
        check(values.isNotEmpty())
        val sorted = values.sorted()
        val rank = ceil(quantile * sorted.size.toDouble()).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun deltaBasisPoints(value: Long, baseline: Long): Long {
        if (baseline <= 0L) return 0L
        return ((value - baseline) * 10_000L) / baseline
    }

    private fun currentPssKb(): Long {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        return memoryInfo.totalPss.toLong().coerceAtLeast(1L)
    }

    private companion object {
        const val TAG = "C10LoadControlEvidence"
        const val REPOSITORY = "MuxTV/Muxtv"
        const val BASE_SEED = 20260911L
        const val WARMUP_ROUNDS = 2
        const val MEASURED_ROUNDS = 20
        const val SURFACE_TIMEOUT_SECONDS = 30L
        const val RUN_TIMEOUT_SECONDS = 20L
        const val PLAYER_THREAD_TIMEOUT_SECONDS = 30L
        const val MAX_PSS_GROWTH_KB = 192L * 1024L
        const val THROTTLE_PERIOD_COUNT = 8L

        val C10_SCENARIOS = listOf(
            C10NetworkScenario(
                id = "raw-ts-fast",
                transport = C10Transport.RAW_TS,
                networkProfile = "local-fast",
                segmentCount = 1,
            ),
            C10NetworkScenario(
                id = "raw-ts-throttled",
                transport = C10Transport.RAW_TS,
                networkProfile = "local-throttled",
                segmentCount = 1,
                throttlePeriodMs = 100L,
            ),
            C10NetworkScenario(
                id = "hls-fast",
                transport = C10Transport.HLS,
                networkProfile = "local-fast",
                segmentCount = 4,
            ),
            C10NetworkScenario(
                id = "hls-delayed-first",
                transport = C10Transport.HLS,
                networkProfile = "delayed-first-segment",
                segmentCount = 4,
                firstSegmentDelayMs = 600L,
            ),
            C10NetworkScenario(
                id = "hls-throttled",
                transport = C10Transport.HLS,
                networkProfile = "bounded-segment-delay",
                segmentCount = 4,
                throttlePeriodMs = 100L,
            ),
            C10NetworkScenario(
                id = "hls-transient-stall",
                transport = C10Transport.HLS,
                networkProfile = "short-transient-stall",
                segmentCount = 6,
                throttlePeriodMs = 100L,
                stallSegmentIndex = 2,
                stallBodyDelayMs = 1_600L,
            ),
        )
    }
}

private enum class C10Transport { RAW_TS, HLS }

private data class C10NetworkScenario(
    val id: String,
    val transport: C10Transport,
    val networkProfile: String,
    val segmentCount: Int,
    val firstSegmentDelayMs: Long = 0L,
    val throttlePeriodMs: Long = 0L,
    val stallSegmentIndex: Int? = null,
    val stallBodyDelayMs: Long = 0L,
)

private fun CompetitiveVariant.toLoadControlVariant(): Media3LoadControlVariant = when (this) {
    CompetitiveVariant.A -> Media3LoadControlVariant.A_CURRENT
    CompetitiveVariant.B -> Media3LoadControlVariant.B_LOWER_START_500MS
    CompetitiveVariant.C -> Media3LoadControlVariant.C_LOWER_START_500MS_REBUFFER_3000MS
    CompetitiveVariant.D -> error("C10 A/B/C evidence does not execute variant D")
}

private fun safeField(value: String): String = value
    .replace('\t', ' ')
    .replace('\r', ' ')
    .replace('\n', ' ')
    .take(200)
    .ifBlank { "unknown" }

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
