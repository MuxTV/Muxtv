package app.muxtv.player.media3

import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.player.PlaybackRuntimeMeasurementSnapshot
import app.muxtv.player.PlaybackRuntimeTransport
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@AndroidXOptIn(UnstableApi::class)
class PlaybackRuntimeMeasurementInstrumentedTest {
    @Test
    fun analyticsListenerRegistersAndResolvesTheInstalledGenerationSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val failure = AtomicReference<Throwable?>()
        val observedGeneration = AtomicReference<Long?>()
        val observedSnapshot = AtomicReference<PlaybackRuntimeMeasurementSnapshot?>()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val state = PlaybackRuntimeMeasurementState()
            state.activate(
                generation = TEST_GENERATION,
                transport = PlaybackRuntimeTransport.HLS,
                deviceSummary = null,
            )
            val listener = PlaybackRuntimeAnalyticsListener(
                state = state,
                eventGeneration = { eventTime -> eventTime.playbackRuntimeGeneration() },
            )
            val player = ExoPlayer.Builder(context).build()
            try {
                player.addAnalyticsListener(listener)
                player.setMediaItem(
                    MediaItem.Builder()
                        .setMediaId(TEST_MEDIA_ID)
                        .setUri(TEST_URI)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setExtras(playbackSeekMetadataExtras(TEST_GENERATION))
                                .build(),
                        )
                        .build(),
                )

                val timeline = player.currentTimeline
                assertThat(timeline.windowCount).isEqualTo(1)
                val eventTime = AnalyticsListener.EventTime(
                    SystemClock.elapsedRealtime(),
                    timeline,
                    0,
                    null,
                    0L,
                    timeline,
                    0,
                    null,
                    0L,
                    0L,
                )
                observedGeneration.set(eventTime.playbackRuntimeGeneration())
                listener.onPlaybackStateChanged(eventTime, Player.STATE_READY)
                observedSnapshot.set(state.snapshot())
                player.removeAnalyticsListener(listener)
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                player.release()
            }
        }

        failure.get()?.let { throwable ->
            throw AssertionError("Runtime measurement listener must be safe on the canonical device lane", throwable)
        }
        assertThat(observedGeneration.get()).isEqualTo(TEST_GENERATION)
        val snapshot = observedSnapshot.get()
        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.transport).isEqualTo(PlaybackRuntimeTransport.HLS)
        assertThat(snapshot.rebufferCount).isEqualTo(0)
        assertThat(snapshot.completedRebufferDurationMillis).isEqualTo(0L)
        assertThat(snapshot.firstFrameLatencyMillis).isNull()
    }

    private companion object {
        const val TEST_GENERATION = 17L
        const val TEST_MEDIA_ID = "runtime-measurement-test"
        const val TEST_URI = "https://example.invalid/runtime-measurement-test"
    }
}
