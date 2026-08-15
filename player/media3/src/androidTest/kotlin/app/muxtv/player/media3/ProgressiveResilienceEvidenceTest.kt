package app.muxtv.player.media3

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.muxtv.network.MuxTvHttpClients
import app.muxtv.player.PlaybackFailureCategory
import app.muxtv.testing.http.RangeMediaServer
import app.muxtv.testing.media.PcmMp4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Test
import org.junit.runner.RunWith

/**
 * EP-08 evidence: default Media3 progressive playback behavior against a TorrServer-style
 * byte-range HTTP origin (see [RangeMediaServer]).
 *
 * All scenarios use the real production pieces — [PlaybackMediaSourceFactory] and
 * [MuxTvHttpClients] — plus an ExoPlayer built with stock defaults, exactly as the playback
 * service does today. Media is a deterministic decode-free PCM MP4 ([PcmMp4]), so no vendor
 * codec is required: playback position advances through the audio sink, seeks resolve through
 * the explicit sample tables. Absolute first-frame claims remain physical-device claims.
 *
 * No timing thresholds are asserted: structural invariants (request counts, target deltas,
 * state transitions) are gated with generous deadlines; latency samples are logged as evidence.
 */
@RunWith(AndroidJUnit4::class)
@AndroidXOptIn(UnstableApi::class)
class ProgressiveResilienceEvidenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun coldStartHasNoPreflightAndIssuesGetRequests() {
        RangeMediaServer.start(config(singleTrackMedia(durationSeconds = 60))).use { server ->
            PlayerHarness().use { harness ->
                harness.post { prepare(harness, server.url("/media.mp4")) }
                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                harness.awaitPositionAtLeast(POSITION_ADVANCE_MILLIS, TIMEOUT_POSITION_MILLIS)

                assertThat(server.headRequestCount()).isEqualTo(0)
                assertThat(server.getRequestCount()).isAtLeast(1)
                val first = server.requests().first()
                assertThat(first.method).isEqualTo("GET")
                val firstRange = first.headers["Range"]
                Log.i(
                    TAG,
                    "cold start first request Range header: " +
                        (firstRange ?: "absent (open GET)"),
                )
            }
        }
    }

    @Test
    fun rapidSeekBurstProducesBoundedRangeRequests() {
        RangeMediaServer.start(config(singleTrackMedia(durationSeconds = 60))).use { server ->
            PlayerHarness().use { harness ->
                harness.post { preparePaused(harness, server.url("/media.mp4")) }
                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                val baselineRequests = server.requestCount()

                val appliedCount = AtomicInteger(0)
                val controller = PlaybackSeekController(
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    onApplySeek = { _, targetMs ->
                        harness.handler.post {
                            appliedCount.incrementAndGet()
                            harness.player.seekTo(targetMs)
                        }
                    },
                )
                harness.post {
                    repeat(SEEK_BURST_PRESSES) {
                        val accepted = controller.onDirectionRequested(
                            generation = "evidence-generation",
                            direction = PlaybackSeekController.DIRECTION_FORWARD,
                            currentPositionMs = harness.player.currentPosition,
                            durationMs = harness.player.duration,
                        )
                        assertThat(accepted).isTrue()
                    }
                }

                harness.await(TIMEOUT_APPLY_MILLIS) { appliedCount.get() >= 1 }
                harness.awaitSeekCompleted(TIMEOUT_SEEK_CONFIRM_MILLIS)

                assertThat(appliedCount.get()).isEqualTo(1)
                var finalPosition = -1L
                harness.post { finalPosition = harness.player.currentPosition }
                assertThat(finalPosition).isAtLeast(
                    SEEK_BURST_PRESSES * PlaybackSeekPolicy.STEP_MILLIS - SEEK_TARGET_TOLERANCE_MILLIS,
                )
                assertThat(server.requestCount() - baselineRequests).isAtMost(MAX_REQUESTS_PER_SEEK)
            }
        }
    }

    @Test
    fun seekModeComparisonIsLoggedAsEvidenceOnPcmCorpus() {
        val media = singleTrackMedia(durationSeconds = 60, syncSampleInterval = SYNC_SAMPLE_INTERVAL)
        RangeMediaServer.start(config(media)).use { server ->
            val locator = server.url("/media.mp4")

            val defaultPosition = seekPositionWith(SeekParameters.DEFAULT, locator)
            val closestSyncPosition = seekPositionWith(SeekParameters.CLOSEST_SYNC, locator)

            // The audio-only PCM corpus exposes no distinguishable sync points: audio frames are
            // self-contained, so both modes land on the exact target and the delta is 0. The A/B
            // delta is evidence (logged), not an assertion: EP-08 leaves SeekParameters.DEFAULT in
            // place; the real video-keyframe A/B is deferred to a physical corpus.
            assertThat(Math.abs(defaultPosition - EXACT_SEEK_TARGET_MILLIS))
                .isAtMost(SEEK_TARGET_TOLERANCE_MILLIS)
            assertThat(Math.abs(closestSyncPosition - EXACT_SEEK_TARGET_MILLIS))
                .isAtMost(SEEK_TARGET_TOLERANCE_MILLIS)
            Log.i(
                TAG,
                "seek A/B evidence: DEFAULT=$defaultPosition ms, CLOSEST_SYNC=$closestSyncPosition ms, " +
                    "delta=${Math.abs(defaultPosition - closestSyncPosition)} ms; " +
                    "no observable distinction on PCM corpus -> SeekParameters.DEFAULT stays",
            )
        }
    }

    @Test
    fun transientServerFailureIsRetriedByDefaultMedia3Policy() {
        RangeMediaServer.start(
            config(singleTrackMedia(durationSeconds = 60), failures = mapOf(0 to 503)),
        ).use { server ->
            PlayerHarness().use { harness ->
                harness.post { prepare(harness, server.url("/media.mp4")) }
                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                harness.awaitPositionAtLeast(POSITION_ADVANCE_MILLIS, TIMEOUT_POSITION_MILLIS)

                assertThat(server.failureServedCount()).isEqualTo(1)
                assertThat(server.requestCount()).isAtLeast(2)
                assertThat(server.requestCount()).isAtMost(MAX_DEFAULT_RETRY_REQUESTS)
                assertThat(harness.probe.playerErrorCount).isEqualTo(0)
            }
        }
    }

    @Test
    fun originWithoutRangeSupportPlaysFullBody() {
        RangeMediaServer.start(
            config(singleTrackMedia(durationSeconds = 60), supportRanges = false),
        ).use { server ->
            PlayerHarness().use { harness ->
                harness.post { prepare(harness, server.url("/media.mp4")) }
                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                harness.awaitPositionAtLeast(POSITION_ADVANCE_MILLIS, TIMEOUT_POSITION_MILLIS)

                assertThat(server.requestCount()).isEqualTo(1)
                assertThat(server.nonRangeRequestCount()).isEqualTo(1)
                assertThat(server.rangeRequestCount()).isEqualTo(0)
            }
        }
    }

    @Test
    fun audioTrackSwitchDoesNotRestartHttpMediaRequest() {
        val media = PcmMp4.build(
            tracks = listOf(
                PcmMp4.Track(trackId = 1, sampleCount = SAMPLES_PER_60S),
                PcmMp4.Track(trackId = 2, sampleCount = SAMPLES_PER_60S),
            ),
            samplesPerChunk = SAMPLES_PER_CHUNK,
        )
        RangeMediaServer.start(config(media)).use { server ->
            PlayerHarness().use { harness ->
                harness.post { prepare(harness, server.url("/media.mp4")) }
                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                harness.await(TIMEOUT_TRACKS_MILLIS) {
                    harness.player.currentTracks.groups
                        .count { it.type == C.TRACK_TYPE_AUDIO } >= 2
                }
                val baselineRequests = server.requestCount()

                var positionAtSwitch = -1L
                var targetTrackIndex = -1
                var targetOverride: TrackSelectionOverride? = null
                harness.post {
                    val audioGroups = harness.player.currentTracks.groups
                        .filter { it.type == C.TRACK_TYPE_AUDIO }
                    check(audioGroups.size >= 2) {
                        "two-track fixture must expose two audio groups, got ${audioGroups.size}"
                    }
                    // A group can contain one track (MP4 trak) or several; pick a real
                    // (group, index) pair that is not currently selected.
                    val unselected = audioGroups.firstOrNull { group ->
                        (0 until group.length).none { group.isTrackSelected(it) }
                    }
                    val target = if (unselected != null) {
                        unselected to (0 until unselected.length).first {
                            !unselected.isTrackSelected(it)
                        }
                    } else {
                        val last = audioGroups.last()
                        last to (last.length - 1)
                    }
                    targetTrackIndex = target.second
                    targetOverride = TrackSelectionOverride(
                        target.first.mediaTrackGroup,
                        target.second,
                    )
                    positionAtSwitch = harness.player.currentPosition
                    harness.player.trackSelectionParameters =
                        harness.player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(targetOverride)
                            .build()
                }

                val override = checkNotNull(targetOverride)
                harness.await(TIMEOUT_TRACKS_MILLIS) {
                    harness.player.trackSelectionParameters.overrides[override.mediaTrackGroup]
                        ?.trackIndices?.contains(targetTrackIndex) == true
                }
                harness.awaitPositionAtLeast(
                    positionAtSwitch + POSITION_ADVANCE_MILLIS,
                    TIMEOUT_POSITION_MILLIS,
                )

                assertThat(server.requestCount()).isEqualTo(baselineRequests)
                assertThat(harness.probe.playerErrorCount).isEqualTo(0)
            }
        }
    }

    @Test
    fun seekIntoStalledChunkRebuffersAndRecovers() {
        RangeMediaServer.start(
            config(
                singleTrackMedia(durationSeconds = 90),
                requestDelaysMillis = mapOf(SEEK_REQUEST_INDEX to STALL_DELAY_MILLIS),
            ),
        ).use { server ->
            PlayerHarness().use { harness ->
                harness.post { prepare(harness, server.url("/media.mp4")) }
                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                harness.awaitPositionAtLeast(POSITION_ADVANCE_MILLIS, TIMEOUT_POSITION_MILLIS)

                harness.post { harness.player.seekTo(STALL_SEEK_TARGET_MILLIS) }

                harness.awaitState(Player.STATE_BUFFERING, TIMEOUT_BUFFERING_MILLIS)
                harness.awaitState(Player.STATE_READY, TIMEOUT_RECOVERY_MILLIS)

                assertThat(harness.probe.seekCompletedCount).isAtLeast(1)
                assertThat(harness.probe.rebufferStartCount).isAtLeast(1)
                assertThat(harness.probe.rebufferEndCount).isAtLeast(1)
                assertThat(harness.probe.playerErrorCount).isEqualTo(0)
                assertThat(server.requestCount()).isAtLeast(2)
            }
        }
    }

    @Test
    fun connectionLossFailsThenManualRetryRecovers() {
        // A tiny file would be fully buffered before the origin dies; closing the listener then
        // breaks nothing. For a deterministic failure we seek into a not-yet-loaded region whose
        // range request is still in flight (delayed body) and kill the listener mid-transfer:
        // the active request dies, retries hit a refused connection and the error surfaces.
        RangeMediaServer.start(
            config(
                singleTrackMedia(durationSeconds = 90),
                requestDelaysMillis = mapOf(SEEK_REQUEST_INDEX to CONNECTION_LOSS_STALL_MILLIS),
            ),
        ).use { server ->
            PlayerHarness().use { harness ->
                val locator = server.url("/media.mp4")
                harness.post { prepare(harness, locator) }
                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                harness.awaitPositionAtLeast(POSITION_ADVANCE_MILLIS, TIMEOUT_POSITION_MILLIS)

                harness.post { harness.player.seekTo(STALL_SEEK_TARGET_MILLIS) }
                harness.awaitState(Player.STATE_BUFFERING, TIMEOUT_BUFFERING_MILLIS)
                assertThat(server.requestCount()).isAtLeast(2)

                server.close()

                harness.await(TIMEOUT_ERROR_MILLIS) { harness.probe.playerErrorCount >= 1 }
                var playbackState = -1
                harness.post { playbackState = harness.player.playbackState }
                assertThat(playbackState).isEqualTo(Player.STATE_IDLE)
                var classification: Media3Failure? = null
                harness.post {
                    classification = harness.probe.lastError
                        ?.let(Media3FailureClassifier::classify)
                }
                Log.i(
                    TAG,
                    "connection loss classified as ${classification?.category}, " +
                        "media3 error code ${classification?.media3ErrorCode}",
                )
                assertThat(classification).isNotNull()
                assertThat(checkNotNull(classification).category).isIn(
                    listOf(
                        PlaybackFailureCategory.NETWORK_UNREACHABLE,
                        PlaybackFailureCategory.TIMEOUT,
                        PlaybackFailureCategory.UNKNOWN,
                    ),
                )

                val requestsAtError = server.requestCount()
                server.restartOnSamePort()
                harness.post { harness.player.prepare() }
                harness.post { harness.player.play() }

                harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
                harness.awaitPositionAtLeast(POSITION_ADVANCE_MILLIS, TIMEOUT_POSITION_MILLIS)
                assertThat(server.requestCount()).isGreaterThan(requestsAtError)
            }
        }
    }

    private fun seekPositionWith(seekParameters: SeekParameters, locator: String): Long {
        var position = -1L
        PlayerHarness(seekParameters).use { harness ->
            harness.post { preparePaused(harness, locator) }
            harness.awaitState(Player.STATE_READY, TIMEOUT_PREPARE_MILLIS)
            harness.post { harness.player.seekTo(EXACT_SEEK_TARGET_MILLIS) }
            harness.awaitSeekCompleted(TIMEOUT_SEEK_CONFIRM_MILLIS)
            harness.post { position = harness.player.currentPosition }
            Log.i(TAG, "$seekParameters seek latency samples: ${harness.probe.seekLatenciesMillis}")
        }
        return position
    }

    private fun prepare(harness: PlayerHarness, locator: String) {
        harness.player.setMediaSource(mediaSourceFactory().create(request(locator)))
        harness.player.prepare()
        harness.player.play()
    }

    private fun preparePaused(harness: PlayerHarness, locator: String) {
        harness.player.playWhenReady = false
        harness.player.setMediaSource(mediaSourceFactory().create(request(locator)))
        harness.player.prepare()
    }

    private fun mediaSourceFactory(): PlaybackMediaSourceFactory =
        PlaybackMediaSourceFactory(context, MuxTvHttpClients())

    private fun request(locator: String) = PlaybackSessionRequest(
        profileId = "profile-evidence",
        mediaId = "media-evidence",
        variantId = "variant-evidence",
        locator = locator,
        insecureHttpApproved = true,
        mimeType = "video/mp4",
    )

    private fun config(
        media: ByteArray,
        supportRanges: Boolean = true,
        failures: Map<Int, Int> = emptyMap(),
        requestDelaysMillis: Map<Int, Long> = emptyMap(),
    ) = RangeMediaServer.Config(
        media = media,
        contentType = "video/mp4",
        supportRanges = supportRanges,
        failures = failures,
        requestDelaysMillis = requestDelaysMillis,
    )

    private fun singleTrackMedia(
        durationSeconds: Int,
        syncSampleInterval: Int = 1,
    ): ByteArray {
        val sampleCount = durationSeconds * PcmMp4.SAMPLE_RATE_HZ / PcmMp4.FRAMES_PER_SAMPLE
        return PcmMp4.build(
            tracks = listOf(
                PcmMp4.Track(
                    trackId = 1,
                    sampleCount = sampleCount,
                    syncSampleInterval = syncSampleInterval,
                ),
            ),
            samplesPerChunk = SAMPLES_PER_CHUNK,
        )
    }

    /**
     * Runs an ExoPlayer on its own thread and drives all interaction from the test thread via
     * posted blocks. Media3 1.10 enforces single-thread access, so every read/write happens
     * inside [post]; waits are implemented as chained handler polls that never block the
     * player thread, letting the playback pipeline progress between checks.
     */
    private inner class PlayerHarness(
        seekParameters: SeekParameters? = null,
    ) : AutoCloseable {
        private val thread = HandlerThread("progressive-evidence").apply { start() }
        val handler = Handler(thread.looper)
        val probe = PlaybackResilienceProbe()
        lateinit var player: ExoPlayer
        private val built = CountDownLatch(1)

        init {
            post {
                player = ExoPlayer.Builder(context)
                    .setLooper(thread.looper)
                    .apply { seekParameters?.let { setSeekParameters(it) } }
                    .build()
                    .also { it.addAnalyticsListener(probe) }
                built.countDown()
            }
            check(built.await(30, TimeUnit.SECONDS)) { "player build timed out" }
        }

        fun post(block: () -> Unit) {
            val latch = CountDownLatch(1)
            var failure: Throwable? = null
            handler.post {
                try {
                    block()
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    latch.countDown()
                }
            }
            check(latch.await(60, TimeUnit.SECONDS)) { "player thread operation timed out" }
            failure?.let { throw it }
        }

        fun await(timeoutMillis: Long, condition: () -> Boolean) {
            val latch = CountDownLatch(1)
            val start = SystemClock.elapsedRealtime()
            var result = false
            fun schedule() {
                handler.postDelayed(
                    {
                        val ok = condition()
                        if (ok || SystemClock.elapsedRealtime() - start >= timeoutMillis) {
                            result = ok
                            latch.countDown()
                        } else {
                            schedule()
                        }
                    },
                    POLL_INTERVAL_MILLIS,
                )
            }
            schedule()
            check(latch.await(timeoutMillis + AWAIT_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                "await condition timed out"
            }
            assertThat(result).isTrue()
        }

        fun awaitState(state: Int, timeoutMillis: Long) =
            await(timeoutMillis) { player.playbackState == state }

        fun awaitPositionAtLeast(position: Long, timeoutMillis: Long) =
            await(timeoutMillis) { player.currentPosition >= position }

        fun awaitSeekCompleted(timeoutMillis: Long) =
            await(timeoutMillis) { probe.seekCompletedCount >= 1 }

        override fun close() {
            if (::player.isInitialized) {
                post { player.release() }
            }
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "ProgressiveResilience"

        const val SAMPLES_PER_CHUNK = 16
        const val SAMPLES_PER_60S = 4_800

        const val POLL_INTERVAL_MILLIS = 100L
        const val AWAIT_GRACE_MILLIS = 15_000L

        const val POSITION_ADVANCE_MILLIS = 500L
        const val SEEK_BURST_PRESSES = 5
        const val SEEK_TARGET_TOLERANCE_MILLIS = 1_000L
        const val MAX_REQUESTS_PER_SEEK = 2
        const val MAX_DEFAULT_RETRY_REQUESTS = 6

        const val SYNC_SAMPLE_INTERVAL = 80
        const val EXACT_SEEK_TARGET_MILLIS = 4_400L

        const val SEEK_REQUEST_INDEX = 1
        const val STALL_DELAY_MILLIS = 2_500L
        const val CONNECTION_LOSS_STALL_MILLIS = 10_000L
        const val STALL_SEEK_TARGET_MILLIS = 80_000L

        const val TIMEOUT_PREPARE_MILLIS = 30_000L
        const val TIMEOUT_POSITION_MILLIS = 15_000L
        const val TIMEOUT_APPLY_MILLIS = 5_000L
        const val TIMEOUT_SEEK_CONFIRM_MILLIS = 10_000L
        const val TIMEOUT_TRACKS_MILLIS = 10_000L
        const val TIMEOUT_BUFFERING_MILLIS = 10_000L
        const val TIMEOUT_RECOVERY_MILLIS = 30_000L
        const val TIMEOUT_ERROR_MILLIS = 30_000L
    }
}
