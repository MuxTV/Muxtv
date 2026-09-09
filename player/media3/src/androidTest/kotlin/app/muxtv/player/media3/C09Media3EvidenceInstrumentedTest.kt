package app.muxtv.player.media3

import android.content.Context
import android.os.Build
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
import app.muxtv.benchmark.competitive.CompetitiveAggregator
import app.muxtv.benchmark.competitive.CompetitiveCorrectness
import app.muxtv.benchmark.competitive.CompetitiveDriverKind
import app.muxtv.benchmark.competitive.CompetitiveEnvironment
import app.muxtv.benchmark.competitive.CompetitiveEvidenceRef
import app.muxtv.benchmark.competitive.CompetitiveExecutionPhase
import app.muxtv.benchmark.competitive.CompetitiveExecutionPlanner
import app.muxtv.benchmark.competitive.CompetitiveMetric
import app.muxtv.benchmark.competitive.CompetitiveScenario
import app.muxtv.benchmark.competitive.CompetitiveScenarioVariant
import app.muxtv.benchmark.competitive.CompetitiveVariant
import app.muxtv.benchmark.competitive.CompetitiveVariantResult
import app.muxtv.benchmark.competitive.CorpusProvenance
import app.muxtv.benchmark.competitive.RepositoryPin
import app.muxtv.player.PlaybackRuntimeTransport
import app.muxtv.testing.media.C09MaterializedPlaybackFixture
import app.muxtv.testing.media.C09PlaybackCorpus
import app.muxtv.testing.media.C09PlaybackTransport
import com.google.common.truth.Truth.assertThat
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith

/**
 * C09 deterministic emulator evidence for the renderer-policy A/B/C comparison.
 *
 * This is deliberately instrumentation-only. It uses the exact immutable C09 corpus, the real
 * Media3 renderer factory for each experimental variant, the existing runtime measurement owner,
 * and C01's authoritative planner/aggregator. It does not mutate the production default or claim
 * vendor/hardware compatibility from emulator results.
 */
@RunWith(AndroidJUnit4::class)
@C09Media3Evidence
@AndroidXOptIn(UnstableApi::class)
class C09Media3EvidenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun rendererVariantsProduceCorrectnessFirstInterleavedEvidence() {
        assertThat(PRODUCTION_MEDIA3_RENDERER_VARIANT).isEqualTo(Media3RendererVariant.A_CURRENT)
        assertThat(C09PlaybackCorpus.verifyIntegrity()).isEmpty()

        val sourceSha = requireSourceSha()
        val source = RepositoryPin(REPOSITORY, sourceSha)
        val corpus = CorpusProvenance(
            manifestSha256 = C09PlaybackCorpus.manifestSha256,
            contentSha256 = C09PlaybackCorpus.contentSha256,
        )
        val environment = environment()
        val corpusDirectory = context.cacheDir.resolve("c09-media3-evidence-corpus").apply {
            deleteRecursively()
            check(mkdirs()) { "unable to create C09 evidence corpus directory" }
        }
        val materialized = C09PlaybackCorpus.materialize(corpusDirectory)

        Log.i(
            TAG,
            listOf(
                "C09_ENV",
                "source_sha=$sourceSha",
                "manifest_sha256=${corpus.manifestSha256}",
                "content_sha256=${corpus.contentSha256}",
                "environment_sha256=${environment.fingerprintSha256}",
                "api=${Build.VERSION.SDK_INT}",
                "model=${safeField(Build.MODEL)}",
                "fingerprint_sha256=${sha256(Build.FINGERPRINT)}",
                "production_variant=${PRODUCTION_MEDIA3_RENDERER_VARIANT.name}",
                "evidence_authority=emulator_non_vendor",
            ).joinToString("\t"),
        )

        val failures = mutableListOf<String>()
        ActivityScenario.launch(C09Media3EvidenceActivity::class.java).use { activityScenario ->
            val surface = activityScenario.awaitSurface()
            PlayerRunner(context, surface).use { runner ->
                materialized.forEachIndexed { fixtureIndex, fixture ->
                    val scenario = scenario(
                        fixture = fixture,
                        fixtureIndex = fixtureIndex,
                        source = source,
                        corpus = corpus,
                    )
                    val correctnessSamples = linkedMapOf<CompetitiveVariant, C09Sample>()
                    CompetitiveExecutionPlanner.planCorrectness(scenario).forEach { slot ->
                        val sample = runner.run(
                            fixture = fixture,
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
                        failures += "${fixture.fixture.id}:correctness:${correctnessFailures.joinToString(",") { it.name }}"
                        Log.i(
                            TAG,
                            "C09_SCENARIO_SKIPPED\tfixture=${fixture.fixture.id}\treason=correctness_gate\tvariants=" +
                                correctnessFailures.joinToString(",") { it.name },
                        )
                        return@forEachIndexed
                    }

                    val performanceSamples = mutableListOf<C09Sample>()
                    CompetitiveExecutionPlanner.planPerformance(scenario, correctness).forEach { slot ->
                        val sample = runner.run(
                            fixture = fixture,
                            phase = slot.phase,
                            round = slot.round,
                            ordinal = slot.ordinal,
                            variant = slot.variant,
                        )
                        performanceSamples += sample
                        logSample(sample)
                    }

                    val unstable = performanceSamples.filterNot(C09Sample::success)
                    if (unstable.isNotEmpty()) {
                        failures += "${fixture.fixture.id}:performance:${unstable.size}"
                        Log.i(
                            TAG,
                            "C09_SCENARIO_SKIPPED\tfixture=${fixture.fixture.id}\treason=performance_run_failed\tcount=${unstable.size}",
                        )
                        return@forEachIndexed
                    }

                    val measuredByVariant = performanceSamples
                        .filter { it.phase == CompetitiveExecutionPhase.MEASURED }
                        .groupBy(C09Sample::variant)
                    val countsValid = scenario.variants.all { variant ->
                        measuredByVariant[variant.id]?.size == MEASURED_ROUNDS
                    }
                    if (!countsValid) {
                        failures += "${fixture.fixture.id}:measured-count"
                        Log.i(
                            TAG,
                            "C09_SCENARIO_SKIPPED\tfixture=${fixture.fixture.id}\treason=measured_count",
                        )
                        return@forEachIndexed
                    }

                    val results = scenario.variants.map { variantSpec ->
                        val correctnessSample = checkNotNull(correctnessSamples[variantSpec.id])
                        val measured = checkNotNull(measuredByVariant[variantSpec.id])
                        CompetitiveVariantResult(
                            schemaVersion = 1,
                            scenarioId = scenario.scenarioId,
                            variant = variantSpec.id,
                            source = source,
                            corpus = corpus,
                            environmentFingerprintSha256 = environment.fingerprintSha256,
                            correctness = CompetitiveCorrectness(
                                passed = true,
                                digestSha256 = sha256(correctnessSample.canonical()),
                                resultCount = 1L,
                            ),
                            metrics = listOf(
                                CompetitiveMetric(
                                    metricId = "tune_to_first_frame_ms",
                                    unit = "ms",
                                    samples = measured.map { sample ->
                                        checkNotNull(sample.firstFrameLatencyMillis)
                                    },
                                ),
                            ),
                            evidenceRefs = listOf(CompetitiveEvidenceRef(EVIDENCE_REF)),
                            redactionPassed = true,
                        )
                    }
                    val report = CompetitiveAggregator.aggregate(
                        scenario = scenario,
                        environment = environment,
                        results = results,
                    )
                    report.variantReports.forEach { variantReport ->
                        val metric = variantReport.metrics.single { it.metricId == "tune_to_first_frame_ms" }
                        Log.i(
                            TAG,
                            listOf(
                                "C09_REPORT",
                                "fixture=${fixture.fixture.id}",
                                "variant=${variantReport.variant.name}",
                                "samples=${metric.sampleCount}",
                                "min_ms=${metric.minimum}",
                                "median_ms=${metric.median}",
                                "p90_ms=${metric.p90}",
                                "p95_ms=${metric.p95}",
                                "p99_ms=${metric.p99}",
                                "max_ms=${metric.maximum}",
                                "delta_bp=${metric.deltaBasisPointsFromBaseline}",
                                "seed=${scenario.seed}",
                                "environment_sha256=${report.environmentFingerprintSha256}",
                            ).joinToString("\t"),
                        )
                    }
                }
            }
        }

        assertThat(failures).named("C09 evidence failures").isEmpty()
    }

    private fun scenario(
        fixture: C09MaterializedPlaybackFixture,
        fixtureIndex: Int,
        source: RepositoryPin,
        corpus: CorpusProvenance,
    ): CompetitiveScenario = CompetitiveScenario(
        schemaVersion = 1,
        scenarioId = "c09-${fixture.fixture.id}",
        seed = BASE_SEED + fixtureIndex,
        warmupRounds = WARMUP_ROUNDS,
        measuredRounds = MEASURED_ROUNDS,
        baselineVariant = CompetitiveVariant.A,
        corpus = corpus,
        variants = listOf(
            variant(CompetitiveVariant.A, "A_CURRENT", source),
            variant(CompetitiveVariant.B, "B_DECODER_FALLBACK", source),
            variant(CompetitiveVariant.C, "C_DECODER_FALLBACK_SYNC_QUEUEING", source),
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
        benchmarkRef = "c09-media3-renderer-policy",
    )

    private fun environment(): CompetitiveEnvironment = CompetitiveEnvironment(
        schemaVersion = 1,
        environmentId = "android-tv-api-${Build.VERSION.SDK_INT}",
        buildMode = "debug",
        baselineProfileState = "not-applied",
        networkProfile = "local-file",
        runtime = mapOf(
            "android_sdk" to Build.VERSION.SDK_INT.toString(),
            "android_release" to safeField(Build.VERSION.RELEASE),
            "vm_version" to safeField(System.getProperty("java.vm.version") ?: "unknown"),
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
        val value = InstrumentationRegistry.getArguments().getString("c09SourceSha")
            ?: error("c09SourceSha instrumentation argument is required")
        require(value.matches(Regex("[0-9a-f]{40}"))) {
            "c09SourceSha must be an exact lowercase 40-hex commit"
        }
        return value
    }

    private fun ActivityScenario<C09Media3EvidenceActivity>.awaitSurface(): Surface {
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
            "C09 evidence surface creation timed out"
        }
        return checkNotNull(surfaceRef.get()).also { surface ->
            check(surface.isValid) { "C09 evidence surface is invalid" }
        }
    }

    private fun logSample(sample: C09Sample) {
        Log.i(
            TAG,
            listOf(
                "C09_SAMPLE",
                "fixture=${sample.fixtureId}",
                "phase=${sample.phase.name}",
                "round=${sample.round}",
                "ordinal=${sample.ordinal}",
                "variant=${sample.variant.name}",
                "success=${sample.success}",
                "outcome=${sample.outcome}",
                "first_frame_ms=${sample.firstFrameLatencyMillis ?: -1L}",
                "decoder=${safeField(sample.decoderName ?: "none")}",
                "decoder_init_ms=${sample.decoderInitializationDurationMillis ?: -1L}",
                "decoder_init_failures=${sample.decoderInitializationFailureCount}",
                "codec_errors=${sample.videoCodecErrorCount}",
                "dropped_frames=${sample.droppedVideoFrameCount}",
                "rebuffer_count=${sample.rebufferCount}",
                "rebuffer_ms=${sample.completedRebufferDurationMillis}",
                "player_error_code=${sample.playerErrorCode ?: -1}",
                "timed_out=${sample.timedOut}",
            ).joinToString("\t"),
        )
    }

    private class PlayerRunner(
        private val context: Context,
        private val surface: Surface,
    ) : AutoCloseable {
        private val thread = HandlerThread("c09-media3-evidence").apply { start() }
        private val handler = Handler(thread.looper)
        private var generation = 0L

        fun run(
            fixture: C09MaterializedPlaybackFixture,
            phase: CompetitiveExecutionPhase,
            round: Int,
            ordinal: Int,
            variant: CompetitiveVariant,
        ): C09Sample {
            generation += 1L
            val runGeneration = generation
            val state = PlaybackRuntimeMeasurementState().apply {
                activate(
                    generation = runGeneration,
                    transport = fixture.fixture.transport.toRuntimeTransport(),
                    deviceSummary = null,
                )
            }
            val completion = CountDownLatch(1)
            val errorRef = AtomicReference<PlaybackException?>()
            val startedAtNanos = AtomicReference<Long?>()
            lateinit var player: ExoPlayer

            onPlayerThread {
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        val started = startedAtNanos.get() ?: return
                        val elapsedNanos = (SystemClock.elapsedRealtimeNanos() - started).coerceAtLeast(1L)
                        val elapsedMillis = ((elapsedNanos + 999_999L) / 1_000_000L).coerceAtLeast(1L)
                        state.onFirstFrame(runGeneration, elapsedMillis)
                        completion.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errorRef.compareAndSet(null, error)
                        completion.countDown()
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED && state.snapshot()?.firstFrameLatencyMillis == null) {
                            completion.countDown()
                        }
                    }
                }
                player = ExoPlayer.Builder(
                    context,
                    createMedia3RenderersFactory(context, variant.toRendererVariant()),
                )
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
                player.setMediaItem(MediaItem.fromUri(fixture.entrypoint.toURI().toString()))
                player.prepare()
                player.play()
            }

            val completed = completion.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val snapshot = state.snapshot()
            val playerError = errorRef.get()
            onPlayerThread { player.release() }

            val firstFrame = snapshot?.firstFrameLatencyMillis
            val decoderName = snapshot?.videoDecoderName
            val success = completed &&
                firstFrame != null &&
                decoderName != null &&
                playerError == null &&
                (snapshot?.decoderInitializationFailureCount ?: 0) == 0
            val outcome = when {
                !completed -> "no-first-frame-timeout"
                playerError != null && isDecoderInitializationFailure(playerError) -> "decoder-init-failure"
                playerError != null -> "player-error"
                firstFrame == null -> "no-first-frame"
                decoderName == null -> "decoder-not-observed"
                else -> "useful-playback"
            }

            return C09Sample(
                fixtureId = fixture.fixture.id,
                phase = phase,
                round = round,
                ordinal = ordinal,
                variant = variant,
                success = success,
                outcome = outcome,
                firstFrameLatencyMillis = firstFrame,
                decoderName = decoderName,
                decoderInitializationDurationMillis = snapshot?.videoDecoderInitializationDurationMillis,
                decoderInitializationFailureCount = snapshot?.decoderInitializationFailureCount ?: 0,
                videoCodecErrorCount = snapshot?.videoCodecErrorCount ?: 0,
                droppedVideoFrameCount = snapshot?.droppedVideoFrameCount ?: 0,
                rebufferCount = snapshot?.rebufferCount ?: 0,
                completedRebufferDurationMillis = snapshot?.completedRebufferDurationMillis ?: 0L,
                playerErrorCode = playerError?.errorCode,
                timedOut = !completed,
            )
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
                "C09 player-thread operation timed out"
            }
            failure.get()?.let { throw it }
        }

        override fun close() {
            thread.quitSafely()
            thread.join(TimeUnit.SECONDS.toMillis(PLAYER_THREAD_TIMEOUT_SECONDS))
            check(!thread.isAlive) { "C09 player thread did not terminate" }
        }
    }

    private data class C09Sample(
        val fixtureId: String,
        val phase: CompetitiveExecutionPhase,
        val round: Int,
        val ordinal: Int,
        val variant: CompetitiveVariant,
        val success: Boolean,
        val outcome: String,
        val firstFrameLatencyMillis: Long?,
        val decoderName: String?,
        val decoderInitializationDurationMillis: Long?,
        val decoderInitializationFailureCount: Int,
        val videoCodecErrorCount: Int,
        val droppedVideoFrameCount: Int,
        val rebufferCount: Int,
        val completedRebufferDurationMillis: Long,
        val playerErrorCode: Int?,
        val timedOut: Boolean,
    ) {
        fun canonical(): String = listOf(
            fixtureId,
            phase.name,
            round.toString(),
            ordinal.toString(),
            variant.name,
            success.toString(),
            outcome,
            firstFrameLatencyMillis?.toString() ?: "-1",
            decoderName ?: "none",
            decoderInitializationDurationMillis?.toString() ?: "-1",
            decoderInitializationFailureCount.toString(),
            videoCodecErrorCount.toString(),
            droppedVideoFrameCount.toString(),
            rebufferCount.toString(),
            completedRebufferDurationMillis.toString(),
            playerErrorCode?.toString() ?: "-1",
            timedOut.toString(),
        ).joinToString("\u0000")
    }

    private companion object {
        const val TAG = "C09Media3Evidence"
        const val REPOSITORY = "MuxTV/Muxtv"
        const val EVIDENCE_REF = "c09-media3-evidence/raw.tsv"
        const val BASE_SEED = 20260909L
        const val WARMUP_ROUNDS = 2
        const val MEASURED_ROUNDS = 20
        const val SURFACE_TIMEOUT_SECONDS = 30L
        const val RUN_TIMEOUT_SECONDS = 15L
        const val PLAYER_THREAD_TIMEOUT_SECONDS = 30L
    }
}

private fun CompetitiveVariant.toRendererVariant(): Media3RendererVariant = when (this) {
    CompetitiveVariant.A -> Media3RendererVariant.A_CURRENT
    CompetitiveVariant.B -> Media3RendererVariant.B_DECODER_FALLBACK
    CompetitiveVariant.C -> Media3RendererVariant.C_DECODER_FALLBACK_SYNC_QUEUEING
    CompetitiveVariant.D -> error("C09 A/B/C evidence does not execute variant D")
}

private fun C09PlaybackTransport.toRuntimeTransport(): PlaybackRuntimeTransport = when (this) {
    C09PlaybackTransport.MPEG_TS -> PlaybackRuntimeTransport.MPEG_TS_LIVE
    C09PlaybackTransport.HLS -> PlaybackRuntimeTransport.HLS
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
