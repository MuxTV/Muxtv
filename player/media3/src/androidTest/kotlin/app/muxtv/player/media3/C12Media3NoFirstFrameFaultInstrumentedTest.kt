package app.muxtv.player.media3

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
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
import java.util.concurrent.atomic.AtomicReference
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith

/** Android fault injection for the C12 timer path; production playback remains untouched. */
@RunWith(AndroidJUnit4::class)
@C12Media3NoFirstFrameEvidence
@AndroidXOptIn(UnstableApi::class)
class C12Media3NoFirstFrameFaultInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun selectedVideoSurfaceReadyWithSuppressedFirstFrameExpiresBAndCOnce() {
        requireSourceSha()
        assertThat(C09PlaybackCorpus.verifyIntegrity()).isEmpty()
        val mediaBytes = materializeAvcFixture()

        ActivityScenario.launch(C12Media3EvidenceActivity::class.java).use { scenario ->
            val holder = scenario.awaitSurfaceHolder()
            MockWebServer().use { server ->
                server.enqueue(
                    MockResponse.Builder()
                        .code(200)
                        .addHeader("Content-Type", "video/mp2t")
                        .addHeader("Content-Length", mediaBytes.size.toString())
                        .body(Buffer().write(mediaBytes))
                        .build(),
                )
                server.start()
                FaultRunner(context).use { runner ->
                    val result = runner.run(
                        url = server.url("/stream.ts").toString(),
                        holder = holder,
                    )
                    assertThat(result.playerErrorCode).isNull()
                    assertThat(result.physicalFirstFrameSeen).isTrue()
                    assertThat(result.selectedVideoSeen).isTrue()
                    assertThat(result.surfaceAvailableSeen).isTrue()
                    assertThat(result.readySeen).isTrue()
                    assertThat(result.bExpired).isTrue()
                    assertThat(result.cExpired).isTrue()
                    assertThat(result.bExpiryCount).isEqualTo(1)
                    assertThat(result.cExpiryCount).isEqualTo(1)
                    assertThat(result.bArmCount).isEqualTo(1)
                    assertThat(result.cArmCount).isEqualTo(1)

                    android.util.Log.i(
                        TAG,
                        listOf(
                            "C12_FAULT",
                            "physical_first_frame=${result.physicalFirstFrameSeen}",
                            "ready=${result.readySeen}",
                            "video_selected=${result.selectedVideoSeen}",
                            "surface_available=${result.surfaceAvailableSeen}",
                            "b_arm_count=${result.bArmCount}",
                            "c_arm_count=${result.cArmCount}",
                            "b_expiry_count=${result.bExpiryCount}",
                            "c_expiry_count=${result.cExpiryCount}",
                            "first_frame_forwarded=false",
                        ).joinToString("\t"),
                    )
                }
            }
        }
    }

    private fun materializeAvcFixture(): ByteArray {
        val directory = context.cacheDir.resolve("c12-no-first-frame-fault").apply {
            deleteRecursively()
            check(mkdirs()) { "unable to create C12 fault corpus directory" }
        }
        val materialized = C09PlaybackCorpus.materialize(directory)
        return checkNotNull(
            materialized.singleOrNull { it.fixture.id == "raw-avc-360p30" },
        ).entrypoint.readBytes()
    }

    private fun requireSourceSha() {
        val sourceSha = InstrumentationRegistry.getArguments().getString("c12SourceSha")
        check(sourceSha != null && sourceSha.matches(Regex("[0-9a-f]{40}"))) {
            "c12SourceSha must be an exact lowercase 40-hex commit"
        }
    }

    private fun ActivityScenario<C12Media3EvidenceActivity>.awaitSurfaceHolder(): SurfaceHolder {
        val latch = CountDownLatch(1)
        val holderRef = AtomicReference<SurfaceHolder?>()
        onActivity { activity ->
            val holder = activity.surfaceView.holder
            if (holder.surface.isValid) {
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
            "C12 fault evidence surface creation timed out"
        }
        return checkNotNull(holderRef.get())
    }

    private inner class FaultRunner(private val context: Context) : Closeable {
        private val thread = HandlerThread("c12-no-first-frame-fault").apply { start() }
        private val handler = Handler(thread.looper)

        fun run(url: String, holder: SurfaceHolder): FaultResult {
            val token = PlaybackAttemptToken(
                setupId = requireNotNull(PlaybackSetupId.parse("c12-fault")),
                generation = 90L,
                candidate = PlaybackCandidateIdentity(
                    channelId = "channel-c12-fault",
                    variantId = "variant-c12-fault",
                ),
                attempt = 0,
            )
            val bothExpired = CountDownLatch(2)
            val b = FaultWatchdogDriver(
                watchdog = PlaybackNoFirstFrameWatchdog(
                    PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S,
                ),
                handler = handler,
                expiredLatch = bothExpired,
            )
            val c = FaultWatchdogDriver(
                watchdog = PlaybackNoFirstFrameWatchdog(
                    PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S,
                ),
                handler = handler,
                expiredLatch = bothExpired,
            )
            b.accept(b.watchdog.activate(token))
            c.accept(c.watchdog.activate(token))
            val drivers = listOf(b, c)
            val readySeen = AtomicBoolean(false)
            val selectedVideoSeen = AtomicBoolean(false)
            val surfaceAvailableSeen = AtomicBoolean(false)
            val physicalFirstFrameSeen = AtomicBoolean(false)
            val errorRef = AtomicReference<PlaybackException?>()
            lateinit var player: ExoPlayer

            fun dispatch(block: (PlaybackNoFirstFrameWatchdog) -> PlaybackNoFirstFrameWatchdogAction) {
                drivers.forEach { driver -> driver.accept(block(driver.watchdog)) }
            }

            onPlayerThread {
                val listener = object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) readySeen.set(true)
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
                        // Fault injection: prove Media3 physically rendered, but deliberately do not
                        // forward this callback to the watchdog. This models the missing completion
                        // signal while preserving real READY/video/surface event mapping.
                        physicalFirstFrameSeen.set(true)
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        errorRef.compareAndSet(null, error)
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

            val expired = bothExpired.await(FAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val error = errorRef.get()
            onPlayerThread { player.release() }
            b.close()
            c.close()
            check(expired) { "C12 B/C fault watchdogs did not both expire in the bounded window" }

            assertThat(b.watchdog.onTimerFired(token))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
            assertThat(c.watchdog.onTimerFired(token))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)

            return FaultResult(
                physicalFirstFrameSeen = physicalFirstFrameSeen.get(),
                readySeen = readySeen.get(),
                selectedVideoSeen = selectedVideoSeen.get(),
                surfaceAvailableSeen = surfaceAvailableSeen.get(),
                bExpired = b.expired.get(),
                cExpired = c.expired.get(),
                bArmCount = b.armCount.get(),
                cArmCount = c.armCount.get(),
                bExpiryCount = b.expiryCount.get(),
                cExpiryCount = c.expiryCount.get(),
                playerErrorCode = error?.errorCode,
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
                "C12 fault player-thread operation timed out"
            }
            failure.get()?.let { throw it }
        }

        override fun close() {
            thread.quitSafely()
            thread.join(TimeUnit.SECONDS.toMillis(PLAYER_THREAD_TIMEOUT_SECONDS))
            check(!thread.isAlive) { "C12 fault player thread did not terminate" }
        }
    }

    private class FaultWatchdogDriver(
        val watchdog: PlaybackNoFirstFrameWatchdog,
        private val handler: Handler,
        private val expiredLatch: CountDownLatch,
    ) {
        val armCount = AtomicInteger(0)
        val expiryCount = AtomicInteger(0)
        val expired = AtomicBoolean(false)
        private var pending: Runnable? = null

        fun accept(action: PlaybackNoFirstFrameWatchdogAction) {
            when (action) {
                PlaybackNoFirstFrameWatchdogAction.None -> Unit
                is PlaybackNoFirstFrameWatchdogAction.Arm -> {
                    cancelPending()
                    armCount.incrementAndGet()
                    val timer = Runnable { accept(watchdog.onTimerFired(action.token)) }
                    pending = timer
                    handler.postDelayed(timer, action.timeoutMillis)
                }
                PlaybackNoFirstFrameWatchdogAction.Disarm -> cancelPending()
                is PlaybackNoFirstFrameWatchdogAction.Expired -> {
                    cancelPending()
                    if (expired.compareAndSet(false, true)) {
                        expiryCount.incrementAndGet()
                        expiredLatch.countDown()
                    }
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

    private data class FaultResult(
        val physicalFirstFrameSeen: Boolean,
        val readySeen: Boolean,
        val selectedVideoSeen: Boolean,
        val surfaceAvailableSeen: Boolean,
        val bExpired: Boolean,
        val cExpired: Boolean,
        val bArmCount: Int,
        val cArmCount: Int,
        val bExpiryCount: Int,
        val cExpiryCount: Int,
        val playerErrorCode: Int?,
    )

    private companion object {
        const val TAG = "C12NoFrameEvidence"
        const val SURFACE_TIMEOUT_SECONDS = 30L
        const val FAULT_TIMEOUT_SECONDS = 14L
        const val PLAYER_THREAD_TIMEOUT_SECONDS = 30L
    }
}
