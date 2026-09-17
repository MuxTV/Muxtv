package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackNoFirstFrameWatchdogTest {
    @Test
    fun `B arms only while ready video and surface are all eligible`() {
        val watchdog = PlaybackNoFirstFrameWatchdog(
            PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S,
        )
        val token = token("setup-a", generation = 1L)

        assertThat(watchdog.activate(token))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        assertThat(watchdog.onReadyChanged(token, ready = true))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        assertThat(watchdog.onVideoExpectedChanged(token, expected = true))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        assertThat(watchdog.onSurfaceAvailabilityChanged(token, available = true))
            .isEqualTo(
                PlaybackNoFirstFrameWatchdogAction.Arm(
                    token = token,
                    timeoutMillis = 10_000L,
                ),
            )

        assertThat(watchdog.onReadyChanged(token, ready = false))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Disarm)
        assertThat(watchdog.onReadyChanged(token, ready = true))
            .isEqualTo(
                PlaybackNoFirstFrameWatchdogAction.Arm(
                    token = token,
                    timeoutMillis = 10_000L,
                ),
            )
    }

    @Test
    fun `stale timer from previous ready window is inert after rearm in same attempt`() {
        val watchdog = PlaybackNoFirstFrameWatchdog(
            PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S,
        )
        val token = token("setup-window-race", generation = 2L)

        watchdog.activate(token)
        watchdog.onReadyChanged(token, ready = true)
        watchdog.onVideoExpectedChanged(token, expected = true)
        val firstArm = watchdog.onSurfaceAvailabilityChanged(token, available = true)
        assertThat(firstArm).isInstanceOf(PlaybackNoFirstFrameWatchdogAction.Arm::class.java)
        val firstWindowId = (firstArm as PlaybackNoFirstFrameWatchdogAction.Arm).windowId

        assertThat(watchdog.onReadyChanged(token, ready = false))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Disarm)
        val secondArm = watchdog.onReadyChanged(token, ready = true)
        assertThat(secondArm).isInstanceOf(PlaybackNoFirstFrameWatchdogAction.Arm::class.java)
        val secondWindowId = (secondArm as PlaybackNoFirstFrameWatchdogAction.Arm).windowId
        assertThat(secondWindowId).isNotEqualTo(firstWindowId)

        assertThat(watchdog.onTimerFired(token, firstWindowId))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        assertThat(watchdog.onTimerFired(token, secondWindowId))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Expired(token))
    }

    @Test
    fun `first frame before ready permanently prevents arming for that attempt`() {
        val watchdog = PlaybackNoFirstFrameWatchdog(
            PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S,
        )
        val token = token("setup-frame-first", generation = 3L)

        watchdog.activate(token)
        watchdog.onVideoExpectedChanged(token, expected = true)
        watchdog.onSurfaceAvailabilityChanged(token, available = true)
        assertThat(watchdog.onRenderedFirstFrame(token))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)

        assertThat(watchdog.onReadyChanged(token, ready = true))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        assertThat(watchdog.onTimerFired(token))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
    }

    @Test
    fun `expiry is one shot and stale attempt timers are inert`() {
        val watchdog = PlaybackNoFirstFrameWatchdog(
            PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S,
        )
        val oldToken = token("setup-old", generation = 4L)

        watchdog.activate(oldToken)
        watchdog.onReadyChanged(oldToken, ready = true)
        watchdog.onVideoExpectedChanged(oldToken, expected = true)
        assertThat(watchdog.onSurfaceAvailabilityChanged(oldToken, available = true))
            .isEqualTo(
                PlaybackNoFirstFrameWatchdogAction.Arm(
                    token = oldToken,
                    timeoutMillis = 5_000L,
                ),
            )
        assertThat(watchdog.onTimerFired(oldToken))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Expired(oldToken))
        assertThat(watchdog.onTimerFired(oldToken))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)

        val newToken = token("setup-new", generation = 5L)
        assertThat(watchdog.activate(newToken))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        assertThat(watchdog.onTimerFired(oldToken))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
    }

    @Test
    fun `A never creates a per-attempt watchdog deadline`() {
        val watchdog = PlaybackNoFirstFrameWatchdog(
            PlaybackNoFirstFrameWatchdogVariant.A_CURRENT_GLOBAL_ONLY,
        )
        val token = token("setup-a-current", generation = 6L)

        watchdog.activate(token)
        watchdog.onReadyChanged(token, ready = true)
        watchdog.onVideoExpectedChanged(token, expected = true)
        assertThat(watchdog.onSurfaceAvailabilityChanged(token, available = true))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        assertThat(watchdog.onTimerFired(token))
            .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
    }

    private fun token(
        setup: String,
        generation: Long,
    ) = PlaybackAttemptToken(
        setupId = requireNotNull(PlaybackSetupId.parse(setup)),
        generation = generation,
        candidate = PlaybackCandidateIdentity(
            channelId = "channel-c12",
            variantId = "variant-$generation",
        ),
        attempt = (generation - 1L).toInt(),
    )
}
