package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSeekControllerTest {
    @Test
    fun `single press schedules one apply after quiet window and completes after confirmation`() =
        runTest {
            val applied = mutableListOf<Pair<Any, Long>>()
            val controller = controller(applied)

            val accepted = controller.onDirectionRequested(
                generation = GEN_A,
                direction = PlaybackSeekController.DIRECTION_FORWARD,
                currentPositionMs = 1_000L,
                durationMs = 60_000L,
            )

            assertThat(accepted).isTrue()
            assertThat(controller.state.value)
                .isEqualTo(SeekControllerState.Pending(11_000L, 1))
            assertThat(applied).isEmpty()

            advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
            runCurrent()

            assertThat(applied).containsExactly(GEN_A to 11_000L)
            assertThat(controller.state.value)
                .isEqualTo(SeekControllerState.Applying(11_000L, 1))

            controller.onSeekConfirmed(GEN_A)

            assertThat(controller.state.value)
                .isEqualTo(SeekControllerState.Completed(11_000L, 1))

            advanceTimeBy(PlaybackSeekPolicy.HUD_LINGER_MILLIS)
            runCurrent()

            assertThat(controller.state.value).isEqualTo(SeekControllerState.Idle)
        }

    @Test
    fun `ten event burst produces exactly one apply with additive target`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        repeat(10) {
            controller.onDirectionRequested(
                generation = GEN_A,
                direction = PlaybackSeekController.DIRECTION_FORWARD,
                currentPositionMs = 5_000L,
                durationMs = 600_000L,
            )
            advanceTimeBy(10L)
            runCurrent()
        }
        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(105_000L, 1))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).hasSize(1)
        assertThat(applied.single()).isEqualTo(GEN_A to 105_000L)
        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Applying(105_000L, 1))
    }

    @Test
    fun `direction reversal accumulates back to the intermediate target`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 10_000L, durationMs = 600_000L)
        advanceTimeBy(10L)
        runCurrent()
        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 10_000L, durationMs = 600_000L)
        advanceTimeBy(10L)
        runCurrent()
        controller.onDirectionRequested(GEN_A, -1, currentPositionMs = 10_000L, durationMs = 600_000L)

        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(20_000L, 1))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied.single()).isEqualTo(GEN_A to 20_000L)
    }

    @Test
    fun `target clamps to duration end and to zero`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 55_000L, durationMs = 60_000L)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()
        assertThat(applied.last()).isEqualTo(GEN_A to 60_000L)

        controller.onSeekConfirmed(GEN_A)
        controller.onDirectionRequested(GEN_A, -1, currentPositionMs = 4_000L, durationMs = 60_000L)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()
        assertThat(applied.last()).isEqualTo(GEN_A to 0L)

        advanceTimeBy(PlaybackSeekPolicy.HUD_LINGER_MILLIS)
        runCurrent()
    }

    @Test
    fun `unknown duration rejects seek input`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        val accepted = controller.onDirectionRequested(
            generation = GEN_A,
            direction = PlaybackSeekController.DIRECTION_FORWARD,
            currentPositionMs = 1_000L,
            durationMs = null,
        )

        assertThat(accepted).isFalse()
        assertThat(controller.state.value).isEqualTo(SeekControllerState.Idle)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()
        assertThat(applied).isEmpty()
    }

    @Test
    fun `stale generation cannot seek new session`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        controller.reset()

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).isEmpty()
        assertThat(controller.state.value).isEqualTo(SeekControllerState.Idle)

        controller.onSeekConfirmed(GEN_A)
        assertThat(controller.state.value).isEqualTo(SeekControllerState.Idle)
    }

    @Test
    fun `new generation discards pending burst of previous generation`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        advanceTimeBy(200L)
        runCurrent()
        controller.onDirectionRequested(GEN_B, 1, currentPositionMs = 40_000L, durationMs = 60_000L)

        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(50_000L, 1))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).hasSize(1)
        assertThat(applied.single()).isEqualTo(GEN_B to 50_000L)
    }

    @Test
    fun `presses during applying are ignored`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()
        assertThat(controller.state.value).isEqualTo(SeekControllerState.Applying(11_000L, 1))

        val accepted = controller.onDirectionRequested(
            generation = GEN_A,
            direction = PlaybackSeekController.DIRECTION_FORWARD,
            currentPositionMs = 11_000L,
            durationMs = 60_000L,
        )

        assertThat(accepted).isFalse()
        assertThat(applied).hasSize(1)
        assertThat(controller.state.value).isEqualTo(SeekControllerState.Applying(11_000L, 1))
    }

    @Test
    fun `confirmed seek from foreign generation is ignored`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        controller.onSeekConfirmed(GEN_B)

        assertThat(controller.state.value).isEqualTo(SeekControllerState.Applying(11_000L, 1))
    }

    @Test
    fun `burst after completion starts from the confirmed position`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()
        controller.onSeekConfirmed(GEN_A)
        advanceTimeBy(PlaybackSeekPolicy.HUD_LINGER_MILLIS)
        runCurrent()
        assertThat(controller.state.value).isEqualTo(SeekControllerState.Idle)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 11_000L, durationMs = 60_000L)

        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(21_000L, 1))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()
    }

    @Test
    fun `unrelated discontinuity during pending does not cancel the scheduled apply`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        advanceTimeBy(100L)
        runCurrent()

        controller.onSeekConfirmed(GEN_A)

        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(11_000L, 1))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).containsExactly(GEN_A to 11_000L)
    }

    @Test
    fun `unconfirmed apply falls back to idle after the bounded timeout`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(controller.state.value).isEqualTo(SeekControllerState.Applying(11_000L, 1))
        assertThat(applied).hasSize(1)

        advanceTimeBy(PlaybackSeekController.APPLY_TIMEOUT_MILLIS + PlaybackSeekPolicy.HUD_LINGER_MILLIS)
        runCurrent()

        assertThat(controller.state.value).isEqualTo(SeekControllerState.Idle)
    }

    @Test
    fun `confirmation after the fallback timeout is ignored`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = controller(applied)

        controller.onDirectionRequested(GEN_A, 1, currentPositionMs = 1_000L, durationMs = 60_000L)
        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()
        advanceTimeBy(PlaybackSeekController.APPLY_TIMEOUT_MILLIS + PlaybackSeekPolicy.HUD_LINGER_MILLIS)
        runCurrent()

        controller.onSeekConfirmed(GEN_A)

        assertThat(controller.state.value).isEqualTo(SeekControllerState.Idle)
    }

    private fun TestScope.controller(
        applied: MutableList<Pair<Any, Long>>,
    ): PlaybackSeekController = PlaybackSeekController(
        scope = this,
        onApplySeek = { generation, targetMs -> applied += generation to targetMs },
    )

    private companion object {
        val GEN_A = "session-a"
        val GEN_B = "session-b"
    }
}
