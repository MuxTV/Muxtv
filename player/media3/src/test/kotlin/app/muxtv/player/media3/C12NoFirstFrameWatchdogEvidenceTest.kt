package app.muxtv.player.media3

import app.muxtv.catalog.PlaybackCandidateIdentity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class C12NoFirstFrameWatchdogEvidenceTest {
    @Test
    fun `A B C deterministic corpus exposes utility and false abandonment`() {
        val a = evaluate(PlaybackNoFirstFrameWatchdogVariant.A_CURRENT_GLOBAL_ONLY)
        val b = evaluate(PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S)
        val c = evaluate(PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S)

        assertThat(a).isEqualTo(
            Evidence(
                stuckDetectionMillis = GLOBAL_RECOVERY_DEADLINE_MILLIS,
                usefulFallbackSuccesses = 0,
                falseStops = 0,
                wrongCandidateAbandonments = 0,
                staleTimerActions = 0,
                duplicateExpiryActions = 0,
            ),
        )
        assertThat(b).isEqualTo(
            Evidence(
                stuckDetectionMillis = 10_000L,
                usefulFallbackSuccesses = 1,
                falseStops = 0,
                wrongCandidateAbandonments = 0,
                staleTimerActions = 0,
                duplicateExpiryActions = 0,
            ),
        )
        assertThat(c).isEqualTo(
            Evidence(
                stuckDetectionMillis = 5_000L,
                usefulFallbackSuccesses = 1,
                falseStops = 1,
                wrongCandidateAbandonments = 1,
                staleTimerActions = 0,
                duplicateExpiryActions = 0,
            ),
        )

        assertThat(GLOBAL_RECOVERY_DEADLINE_MILLIS - b.stuckDetectionMillis)
            .isEqualTo(10_000L)
        assertThat(GLOBAL_RECOVERY_DEADLINE_MILLIS - c.stuckDetectionMillis)
            .isEqualTo(15_000L)
    }

    @Test
    fun `buffering surface audio-only and stale ownership never create invalid expiry`() {
        for (variant in PlaybackNoFirstFrameWatchdogVariant.entries) {
            val watchdog = PlaybackNoFirstFrameWatchdog(variant)
            val token = token("guard-${variant.ordinal}", generation = 20L + variant.ordinal)

            watchdog.activate(token)
            assertThat(watchdog.onReadyChanged(token, ready = true))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
            assertThat(watchdog.onSurfaceAvailabilityChanged(token, available = true))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)

            val videoAction = watchdog.onVideoExpectedChanged(token, expected = true)
            if (variant == PlaybackNoFirstFrameWatchdogVariant.A_CURRENT_GLOBAL_ONLY) {
                assertThat(videoAction).isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
                continue
            }
            val firstArm = requireArm(videoAction, token)

            assertThat(watchdog.onReadyChanged(token, ready = false))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Disarm)
            assertThat(watchdog.onTimerFired(token, firstArm.windowId))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)

            val secondArm = requireArm(watchdog.onReadyChanged(token, ready = true), token)
            assertThat(secondArm.windowId == firstArm.windowId).isFalse()
            assertThat(watchdog.onSurfaceAvailabilityChanged(token, available = false))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Disarm)
            assertThat(watchdog.onTimerFired(token, secondArm.windowId))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)

            val thirdArm = requireArm(
                watchdog.onSurfaceAvailabilityChanged(token, available = true),
                token,
            )
            val replacement = token("replacement-${variant.ordinal}", generation = 40L + variant.ordinal)
            assertThat(watchdog.activate(replacement))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Disarm)
            assertThat(watchdog.onTimerFired(token, thirdArm.windowId))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        }
    }

    private fun evaluate(
        variant: PlaybackNoFirstFrameWatchdogVariant,
    ): Evidence {
        val threshold = when (variant) {
            PlaybackNoFirstFrameWatchdogVariant.A_CURRENT_GLOBAL_ONLY -> null
            PlaybackNoFirstFrameWatchdogVariant.B_READY_GATED_10S -> 10_000L
            PlaybackNoFirstFrameWatchdogVariant.C_READY_GATED_5S -> 5_000L
        }

        var falseStops = 0
        var wrongCandidateAbandonments = 0
        var staleTimerActions = 0
        var duplicateExpiryActions = 0
        var usefulFallbackSuccesses = 0

        val healthy = PlaybackNoFirstFrameWatchdog(variant)
        val healthyToken = token("healthy-${variant.ordinal}", generation = 1L + variant.ordinal)
        healthy.activate(healthyToken)
        healthy.onVideoExpectedChanged(healthyToken, expected = true)
        healthy.onSurfaceAvailabilityChanged(healthyToken, available = true)
        val healthyArmAction = healthy.onReadyChanged(healthyToken, ready = true)
        if (threshold == null) {
            assertThat(healthyArmAction).isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
            assertThat(healthy.onRenderedFirstFrame(healthyToken))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        } else {
            val healthyArm = requireArm(healthyArmAction, healthyToken)
            assertThat(healthyArm.timeoutMillis).isEqualTo(threshold)
            if (SLOW_HEALTHY_FIRST_FRAME_MILLIS < threshold) {
                assertThat(healthy.onRenderedFirstFrame(healthyToken))
                    .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Disarm)
                assertThat(healthy.onTimerFired(healthyToken, healthyArm.windowId))
                    .isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
            } else if (
                healthy.onTimerFired(healthyToken, healthyArm.windowId) ==
                PlaybackNoFirstFrameWatchdogAction.Expired(healthyToken)
            ) {
                falseStops += 1
                wrongCandidateAbandonments += 1
            }
        }

        val stuck = PlaybackNoFirstFrameWatchdog(variant)
        val stuckToken = token("stuck-${variant.ordinal}", generation = 10L + variant.ordinal)
        stuck.activate(stuckToken)
        stuck.onReadyChanged(stuckToken, ready = true)
        stuck.onVideoExpectedChanged(stuckToken, expected = true)
        val stuckArmAction = stuck.onSurfaceAvailabilityChanged(stuckToken, available = true)
        if (threshold == null) {
            assertThat(stuckArmAction).isEqualTo(PlaybackNoFirstFrameWatchdogAction.None)
        } else {
            val stuckArm = requireArm(stuckArmAction, stuckToken)
            assertThat(stuckArm.timeoutMillis).isEqualTo(threshold)
            assertThat(stuck.onTimerFired(stuckToken, stuckArm.windowId))
                .isEqualTo(PlaybackNoFirstFrameWatchdogAction.Expired(stuckToken))
            usefulFallbackSuccesses += 1
            if (
                stuck.onTimerFired(stuckToken, stuckArm.windowId) !=
                PlaybackNoFirstFrameWatchdogAction.None
            ) {
                duplicateExpiryActions += 1
            }

            val replacement = token("replacement-${variant.ordinal}", generation = 30L + variant.ordinal)
            stuck.activate(replacement)
            if (
                stuck.onTimerFired(stuckToken, stuckArm.windowId) !=
                PlaybackNoFirstFrameWatchdogAction.None
            ) {
                staleTimerActions += 1
            }
        }

        return Evidence(
            stuckDetectionMillis = threshold ?: GLOBAL_RECOVERY_DEADLINE_MILLIS,
            usefulFallbackSuccesses = usefulFallbackSuccesses,
            falseStops = falseStops,
            wrongCandidateAbandonments = wrongCandidateAbandonments,
            staleTimerActions = staleTimerActions,
            duplicateExpiryActions = duplicateExpiryActions,
        )
    }

    private fun requireArm(
        action: PlaybackNoFirstFrameWatchdogAction,
        token: PlaybackAttemptToken,
    ): PlaybackNoFirstFrameWatchdogAction.Arm {
        assertThat(action).isInstanceOf(PlaybackNoFirstFrameWatchdogAction.Arm::class.java)
        val arm = action as PlaybackNoFirstFrameWatchdogAction.Arm
        assertThat(arm.token).isEqualTo(token)
        assertThat(arm.windowId > 0L).isTrue()
        return arm
    }

    private fun token(
        setup: String,
        generation: Long,
    ) = PlaybackAttemptToken(
        setupId = requireNotNull(PlaybackSetupId.parse(setup)),
        generation = generation,
        candidate = PlaybackCandidateIdentity(
            channelId = "channel-c12-evidence",
            variantId = "variant-$generation",
        ),
        attempt = 0,
    )

    private data class Evidence(
        val stuckDetectionMillis: Long,
        val usefulFallbackSuccesses: Int,
        val falseStops: Int,
        val wrongCandidateAbandonments: Int,
        val staleTimerActions: Int,
        val duplicateExpiryActions: Int,
    )

    private companion object {
        const val GLOBAL_RECOVERY_DEADLINE_MILLIS = 20_000L
        const val SLOW_HEALTHY_FIRST_FRAME_MILLIS = 7_000L
    }
}
