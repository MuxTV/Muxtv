package app.muxtv.player.media3

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.testing.media.C09PlaybackCorpus
import com.google.common.truth.Truth.assertThat
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Bounded C12 Android evidence for the Media3 event mapping used by the no-first-frame policy.
 *
 * This does not wire the watchdog into [MuxTvPlaybackService]. It proves the candidate runtime
 * signals against local deterministic video: READY/BUFFERING, selected video tracks, surface
 * availability and first-frame ordering. Both preregistered B/C timers are driven for healthy
 * playback so any false expiry fails the evidence run.
 */
@RunWith(AndroidJUnit4::class)
@C12Media3NoFirstFrameEvidence
@AndroidXOptIn(UnstableApi::class)
class C12Media3NoFirstFrameEvidenceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun healthyRawTsAndHlsNeverExpireReadyGatedWatchdogs() {
        assertThat(C09PlaybackCorpus.verifyIntegrity()).isEmpty()
        val sourceSha = requireSourceSha()
        val mediaBytes = avcFixture("healthy")
        Log.i(
            TAG,
            listOf(
                "C12_ENV",
                "source_sha=$sourceSha",
                "api=${Build.VERSION.SDK_INT}",
                "model=${safeField(Build.MODEL)}",
                "production_renderer=${PRODUCTION_MEDIA3_RENDERER_VARIANT.name}",
                "evidence_authority=emulator_non_vendor",
            ).joinToString("\t"),
        )

        ActivityScenario.launch(C12Media3EvidenceActivity::class.java).use { scenario ->
            val holder = scenario.awaitSurfaceHolder()
            C12Transport.entries.forEachIndexed { index, transport ->
                C12Origin.start(
                    transport = transport,
                    mediaBytes = mediaBytes,
                    firstBodyDelayMillis = DELAYED_FIRST_BODY_MILLIS,
                ).use { origin ->
                    PlayerRunner(context).use { runner ->
                        val sample = runner.runHealthy(
                            url = origin.entrypointUrl(),
                            holder = holder,
                            generation = index + 1L,
                        )
                        assertThat(sample.playerErrorCode).isNull()
                        assertThat(sample.firstFrameSeen).isTrue()
                        assertThat(sample.selectedVideoSeen).isTrue()
                        assertThat(sample.surfaceAvailableSeen).isTrue()
                        assertThat(sample.bufferingSeen).isTrue()
                        assertThat(sample.bExpired).isFalse()
                        assertThat(sample.cExpired).isFalse()
                        Log.i(
                            TAG,
                            listOf(
                                "C12_SAMPLE",
                                "transport=${transport.name}",
                                "first_frame_ms=${sample.firstFrameMillis}",
                                "ready_ms=${sample.readyMillis}",
                                "first_frame_before_ready=${sample.firstFrameBeforeReady}",
                                "buffering_seen=${sample.bufferingSeen}",
                                "video_selected=${sample.selectedVideoSeen}",
                                "surface_available=${sample.surfaceAvailableSeen}",
                                "b_arm_count=${sample.bArmCount}",
                                "c_arm_count=${sample.cArmCount}",
                                "b_expired=${sample.bExpired}",
                                "c_expired=${sample.cExpired}",
                            ).joinToString("\t"),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun readySelectedVideoWithoutSurfaceDoesNotArmBeforeAttach() {
        assertThat(C09PlaybackCorpus.verifyIntegrity()).isEmpty()
        requireSourceSha()
        val mediaBytes = avcFixture("surface")

        ActivityScenario.launch(C12Media3EvidenceActivity::class.java).use { scenario ->
            val holder = scenario.awaitSurfaceHolder()
            C12Origin.start(
                transport = C12Transport.RAW_TS,
                mediaBytes = mediaBytes,
                firstBodyDelayMillis = 0L,
            ).use { origin ->
                PlayerRunner(context).use { runner ->
                    val sample = runner.runAttachSurfaceAfterReadyVideo(
                        url = origin.entrypointUrl(),
                        holder = holder,
                        generation = 20L,
                    )
                    assertThat(sample.readyVideoWithoutSurfaceSeen).isTrue()
                    assertThat(sample.bArmCountBeforeAttach).isEqualTo(0)
                    assertThat(sample.cArmCountBeforeAttach).isEqualTo(0)
                    assertThat(sample.surfaceAvailableSeen).isTrue()
                    assertThat(sample.firstFrameSeen).isTrue()
                    assertThat(sample.bExpired).isFalse()
                    assertThat(sample.cExpired).isFalse()
                    assertThat(sample.playerErrorCode).isNull()
                    Log.i(
                        TAG,
                        listOf(
                            "C12_SURFACE",
                            "ready_without_surface=${sample.readyVideoWithoutSurfaceSeen}",
                            "b_arm_before_attach=${sample.bArmCountBeforeAttach}",
                            "c_arm_before_attach=${sample.cArmCountBeforeAttach}",
                            "surface_available=${sample.surfaceAvailableSeen}",
                            "first_frame=${sample.firstFrameSeen}",
                            "b_expired=${sample.bExpired}",
                            "c_expired=${sample.cExpired}",
                        ).joinToString("\t"),
                    )
                }
            }
        }
    }

    @Test
    fun deterministicNoVideoProjectionNeverArms() {
        requireSourceSha()
        val token = token("c12-no-video", generation = 50L)
        for (variant in listOf(
            PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S,
            PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S,
        )) {
            val watchdog = PlaybackNoFirstFrameWatchdog(variant)
            watchdog.activate(token)
            assertThat(watchdog.onReadyChanged(token, ready = true))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
            assertThat(watchdog.onSurfaceAvailabilityChanged(token, available = true))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
            assertThat(watchdog.onTimerFired(token))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        }
        Log.i(TAG, "C12_NOVIDEO\tready=true\tsurface=true\tvideo_selected=false\texpiry=false")
    }

    private fun avcFixture(suffix: String): ByteArray {
        val directory = context.cacheDir.resolve("c12-no-first-frame-$suffix").apply {
            deleteRecursively()
            check(mkdirs()) { "unable to create C12 evidence corpus directory" }
        }
        val materialized = C09PlaybackCorpus.materialize(directory)
        return checkNotNull(
            materialized.singleOrNull { it.fixture.id == "raw-avc-360p30" },
        ).entrypoint.readBytes()
    }

    private fun requireSourceSha(): String {
        val sourceSha = InstrumentationRegistry.getArguments().getString("c12SourceSha")
        check(sourceSha != null && sourceSha.matches(Regex("[0-9a-f]{40}"))) {
            "c12SourceSha must be an exact lowercase 40-hex commit"
        }
        return sourceSha
    }

    private fun ActivityScenario<C12Media3EvidenceActivity>.awaitSurfaceHolder(): SurfaceHolder {
        val latch = CountDownLatch(1)
        val holderRef = AtomicReference<SurfaceHolder?>()
        onActivity { activity ->
            val holder = activity.surfaceView.holder
            if (holder.surface?.isValid == true) {
                holderRef.set(holder)
                latch.countDown()
            } else {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(createdHolder: SurfaceHolder) {
                            holderRef.compareAndSet(null, createdHolder)
                            latch.countDown()
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
            "C12 evidence surface creation timed out"
        }
        return checkNotNull(holderRef.get()).also { holder ->
            check(holder.surface?.isValid == true) { "C12 evidence surface is invalid" }
        }
    }

    private inner class PlayerRunner(
        private val context: Context,
    ) : Closeable {
        private val thread = HandlerThread("c12-no-first-frame-evidence").apply { start() }
        private val handler = Handler(thread.looper)

        fun runHealthy(
            url: String,
            holder: SurfaceHolder,
            generation: Long,
        ): HealthySample {
            val token = token("c12-healthy-$generation", generation)
            val b = WatchdogDriver(
                PlaybackNoFirstFrameWatchdog(PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S),
                handler,
            )
            val c = WatchdogDriver(
                PlaybackNoFirstFrameWatchdog(PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S),
                handler,
            )
            b.accept(b.watchdog.activate(token))
            c.accept(c.watchdog.activate(token))
            val drivers = listOf(b, c)
            val completion = CountDownLatch(1)
            val errorRef = AtomicReference<PlaybackException?>()
            val firstFrameSeen = AtomicBoolean(false)
            val selectedVideoSeen = AtomicBoolean(false)
            val surfaceAvailableSeen = AtomicBoolean(false)
            val bufferingSeen = AtomicBoolean(false)
            val startedAtNanos = SystemClock.elapsedRealtimeNanos()
            val firstFrameMillis = AtomicLong(-1L)
            val readyMillis = AtomicLong(-1L)
            lateinit var player: ExoPlayer

            fun dispatch(block: (PlaybackNoFirstFrameWatchdog) -> PlaybackNoFirstFrameWatchdogAction) {
                drivers.forEach { driver -> driver.accept(block(driver.watchdog)) }
            }

            onPlayerThread {
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_BUFFERING) bufferingSeen.set(true)
                        if (playbackState == Player.STATE_READY) {
                            readyMillis.compareAndSet(-1L, elapsedMillisSince(startedAtNanos))
                        }
                        dispatch { it.onReadyChanged(token, playbackState == Player.STATE_READY) }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        val selectedVideo = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_VIDEO && group.isSelected
                        }
                        if (selectedVideo) selectedVideoSeen.set(true)
                        dispatch { it.onVideoExpectedChanged(token, selectedVideo) }
                    }

                    override fun onSurfaceSizeChanged(width: Int, height: Int) {
                        val available = width > 0 && height > 0
                        if (available) surfaceAvailableSeen.set(true)
                        dispatch { it.onSurfaceAvailabilityChanged(token, available) }
                    }

                    override fun onRenderedFirstFrame() {
                        firstFrameSeen.set(true)
                        firstFrameMillis.compareAndSet(-1L, elapsedMillisSince(startedAtNanos))
                        dispatch { it.onRenderedFirstFrame(token) }
                        completion.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errorRef.compareAndSet(null, error)
                        completion.countDown()
                    }
                }
                player = ExoPlayer.Builder(
                    context,
                    createMedia3RenderersFactory(context, PRODUCTION_MEDIA3_RENDERER_VARIANT),
                )
                    .setLooper(thread.looper)
                    .build()
                player.addListener(listener)
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.setVideoSurfaceHolder(holder)
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
            }

            val completed = completion.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val error = errorRef.get()
            onPlayerThread { player.release() }
            b.close()
            c.close()
            check(completed) { "C12 healthy playback did not reach first frame or error" }

            val first = firstFrameMillis.get().takeIf { it >= 0L }
            val ready = readyMillis.get().takeIf { it >= 0L }
            return HealthySample(
                firstFrameSeen = firstFrameSeen.get(),
                selectedVideoSeen = selectedVideoSeen.get(),
                surfaceAvailableSeen = surfaceAvailableSeen.get(),
                bufferingSeen = bufferingSeen.get(),
                firstFrameMillis = first,
                readyMillis = ready,
                firstFrameBeforeReady = first != null && ready != null && first <= ready,
                bArmCount = b.armCount.get(),
                cArmCount = c.armCount.get(),
                bExpired = b.expired.get(),
                cExpired = c.expired.get(),
                playerErrorCode = error?.errorCode,
            )
        }

        fun runAttachSurfaceAfterReadyVideo(
            url: String,
            holder: SurfaceHolder,
            generation: Long,
        ): SurfaceAttachSample {
            val token = token("c12-surface-$generation", generation)
            val b = WatchdogDriver(
                PlaybackNoFirstFrameWatchdog(PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S),
                handler,
            )
            val c = WatchdogDriver(
                PlaybackNoFirstFrameWatchdog(PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S),
                handler,
            )
            b.accept(b.watchdog.activate(token))
            c.accept(c.watchdog.activate(token))
            val drivers = listOf(b, c)
            val ready = AtomicBoolean(false)
            val selectedVideo = AtomicBoolean(false)
            val surfaceAvailable = AtomicBoolean(false)
            val surfaceAvailableSeen = AtomicBoolean(false)
            val firstFrameSeen = AtomicBoolean(false)
            val readyVideoWithoutSurface = CountDownLatch(1)
            val completion = CountDownLatch(1)
            val errorRef = AtomicReference<PlaybackException?>()
            lateinit var player: ExoPlayer

            fun maybeSignalReadyWithoutSurface() {
                if (ready.get() && selectedVideo.get() && !surfaceAvailable.get()) {
                    readyVideoWithoutSurface.countDown()
                }
            }

            fun dispatch(block: (PlaybackNoFirstFrameWatchdog) -> PlaybackNoFirstFrameWatchdogAction) {
                drivers.forEach { driver -> driver.accept(block(driver.watchdog)) }
            }

            onPlayerThread {
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        ready.set(playbackState == Player.STATE_READY)
                        dispatch { it.onReadyChanged(token, playbackState == Player.STATE_READY) }
                        maybeSignalReadyWithoutSurface()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        val hasSelectedVideo = tracks.groups.any { group ->
                            group.type == C.TRACK_TYPE_VIDEO && group.isSelected
                        }
                        selectedVideo.set(hasSelectedVideo)
                        dispatch { it.onVideoExpectedChanged(token, hasSelectedVideo) }
                        maybeSignalReadyWithoutSurface()
                    }

                    override fun onSurfaceSizeChanged(width: Int, height: Int) {
                        val available = width > 0 && height > 0
                        surfaceAvailable.set(available)
                        if (available) surfaceAvailableSeen.set(true)
                        dispatch { it.onSurfaceAvailabilityChanged(token, available) }
                        maybeSignalReadyWithoutSurface()
                    }

                    override fun onRenderedFirstFrame() {
                        firstFrameSeen.set(true)
                        dispatch { it.onRenderedFirstFrame(token) }
                        completion.countDown()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errorRef.compareAndSet(null, error)
                        completion.countDown()
                    }
                }
                player = ExoPlayer.Builder(
                    context,
                    createMedia3RenderersFactory(context, PRODUCTION_MEDIA3_RENDERER_VARIANT),
                )
                    .setLooper(thread.looper)
                    .build()
                player.addListener(listener)
                player.repeatMode = Player.REPEAT_MODE_ONE
                player.setMediaItem(MediaItem.fromUri(url))
                player.prepare()
                player.play()
            }

            val noSurfaceReady = readyVideoWithoutSurface.await(
                READY_WITHOUT_SURFACE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS,
            )
            val bArmBeforeAttach = b.armCount.get()
            val cArmBeforeAttach = c.armCount.get()
            if (noSurfaceReady) {
                onPlayerThread { player.setVideoSurfaceHolder(holder) }
            }
            val completed = noSurfaceReady && completion.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val error = errorRef.get()
            onPlayerThread { player.release() }
            b.close()
            c.close()
            check(noSurfaceReady) { "C12 player did not reach READY + selected video without a surface" }
            check(completed) { "C12 surface attach did not reach first frame or error" }

            return SurfaceAttachSample(
                readyVideoWithoutSurfaceSeen = true,
                bArmCountBeforeAttach = bArmBeforeAttach,
                cArmCountBeforeAttach = cArmBeforeAttach,
                surfaceAvailableSeen = surfaceAvailableSeen.get(),
                firstFrameSeen = firstFrameSeen.get(),
                bExpired = b.expired.get(),
                cExpired = c.expired.get(),
                playerErrorCode = error?.errorCode,
            )
        }

        private fun elapsedMillisSince(startedAtNanos: Long): Long {
            val elapsedNanos = (SystemClock.elapsedRealtimeNanos() - startedAtNanos).coerceAtLeast(1L)
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
                "C12 player-thread operation timed out"
            }
            failure.get()?.let { throw it }
        }

        override fun close() {
            thread.quitSafely()
            thread.join(TimeUnit.SECONDS.toMillis(PLAYER_THREAD_TIMEOUT_SECONDS))
            check(!thread.isAlive) { "C12 player thread did not terminate" }
        }
    }

    private class WatchdogDriver(
        val watchdog: PlaybackNoFirstFrameWatchdog,
        private val handler: Handler,
    ) {
        val armCount = AtomicInteger(0)
        val expired = AtomicBoolean(false)
        private var pending: Runnable? = null

        fun accept(action: PlaybackNoFirstFrameWatchdogAction) {
            when (action) {
                PlaybackNoFirstFrameWatchdogAction.None -> Unit
                is PlaybackNoFirstFrameWatchdogAction.Arm -> {
                    cancelPending()
                    armCount.incrementAndGet()
                    val timer = Runnable {
                        accept(watchdog.onTimerFired(action.token))
                    }
                    pending = timer
                    handler.postDelayed(timer, action.timeoutMillis)
                }
                PlaybackNoFirstFrameWatchdogAction.Disarm -> cancelPending()
                is PlaybackNoFirstFrameWatchdogAction.Expired -> {
                    cancelPending()
                    expired.set(true)
                }
            }
        }

        fun close() {
            cancelPending()
        }

        private fun cancelPending() {
            pending?.let(handler::removeCallbacks)
            pending = null
        }
    }

    private class C12Origin private constructor(
        private val transport: C12Transport,
        private val mediaBytes: ByteArray,
        private val firstBodyDelayMillis: Long,
    ) : Closeable {
        private val server = MockWebServer().also { mockServer ->
            mockServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    return when {
                        path == "/stream.ts" -> mediaResponse(delayMillis = firstBodyDelayMillis)
                        path == "/live.m3u8" -> MockResponse.Builder()
                            .code(200)
                            .addHeader("Content-Type", "application/x-mpegURL")
                            .body(hlsPlaylist())
                            .build()
                        path == "/segment-0.ts" -> mediaResponse(delayMillis = firstBodyDelayMillis)
                        path == "/segment-1.ts" || path == "/segment-2.ts" -> mediaResponse(delayMillis = 0L)
                        else -> MockResponse.Builder().code(404).build()
                    }
                }
            }
        }

        fun entrypointUrl(): String = when (transport) {
            C12Transport.RAW_TS -> server.url("/stream.ts").toString()
            C12Transport.HLS -> server.url("/live.m3u8").toString()
        }

        private fun mediaResponse(delayMillis: Long): MockResponse {
            var response = MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "video/mp2t")
                .addHeader("Content-Length", mediaBytes.size.toString())
                .body(Buffer().write(mediaBytes))
            if (delayMillis > 0L) {
                response = response.bodyDelay(delayMillis, TimeUnit.MILLISECONDS)
            }
            return response.build()
        }

        private fun hlsPlaylist(): String = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-TARGETDURATION:1")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            repeat(3) { index ->
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
                transport: C12Transport,
                mediaBytes: ByteArray,
                firstBodyDelayMillis: Long,
            ): C12Origin = C12Origin(transport, mediaBytes, firstBodyDelayMillis).also {
                it.server.start()
            }
        }
    }

    private data class HealthySample(
        val firstFrameSeen: Boolean,
        val selectedVideoSeen: Boolean,
        val surfaceAvailableSeen: Boolean,
        val bufferingSeen: Boolean,
        val firstFrameMillis: Long?,
        val readyMillis: Long?,
        val firstFrameBeforeReady: Boolean,
        val bArmCount: Int,
        val cArmCount: Int,
        val bExpired: Boolean,
        val cExpired: Boolean,
        val playerErrorCode: Int?,
    )

    private data class SurfaceAttachSample(
        val readyVideoWithoutSurfaceSeen: Boolean,
        val bArmCountBeforeAttach: Int,
        val cArmCountBeforeAttach: Int,
        val surfaceAvailableSeen: Boolean,
        val firstFrameSeen: Boolean,
        val bExpired: Boolean,
        val cExpired: Boolean,
        val playerErrorCode: Int?,
    )

    private enum class C12Transport {
        RAW_TS,
        HLS,
    }

    private fun token(setup: String, generation: Long) = PlaybackAttemptToken(
        setupId = requireNotNull(PlaybackSetupId.parse(setup)),
        generation = generation,
        candidate = PlaybackCandidateIdentity(
            channelId = "channel-c12-evidence",
            variantId = "variant-$generation",
        ),
        attempt = 0,
    )

    private fun safeField(value: String?): String = value
        ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
        ?.take(80)
        ?: "unknown"

    private companion object {
        const val TAG = "C12NoFrameEvidence"
        const val DELAYED_FIRST_BODY_MILLIS = 600L
        const val SURFACE_TIMEOUT_SECONDS = 30L
        const val READY_WITHOUT_SURFACE_TIMEOUT_SECONDS = 10L
        const val RUN_TIMEOUT_SECONDS = 15L
        const val PLAYER_THREAD_TIMEOUT_SECONDS = 30L
    }
}
