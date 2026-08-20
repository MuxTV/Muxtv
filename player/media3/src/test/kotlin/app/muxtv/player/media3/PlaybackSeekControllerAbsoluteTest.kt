package app.muxtv.player.media3

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSeekControllerAbsoluteTest {
    @Test
    fun `absolute request uses the same bounded apply scheduler`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = PlaybackSeekController(
            scope = this,
            onApplySeek = { generation, targetMs -> applied += generation to targetMs },
        )

        val accepted = controller.onTargetRequested(
            generation = GENERATION,
            targetMs = 42_000L,
            currentPositionMs = 5_000L,
            durationMs = 60_000L,
        )

        assertThat(accepted).isTrue()
        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(42_000L, PlaybackSeekController.DIRECTION_FORWARD))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).containsExactly(GENERATION to 42_000L)
    }

    @Test
    fun `absolute request supersedes pending relative request in one burst`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = PlaybackSeekController(
            scope = this,
            onApplySeek = { generation, targetMs -> applied += generation to targetMs },
        )

        controller.onDirectionRequested(
            generation = GENERATION,
            direction = PlaybackSeekController.DIRECTION_FORWARD,
            currentPositionMs = 5_000L,
            durationMs = 60_000L,
        )
        advanceTimeBy(50L)
        runCurrent()
        controller.onTargetRequested(
            generation = GENERATION,
            targetMs = 30_000L,
            currentPositionMs = 5_000L,
            durationMs = 60_000L,
        )

        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(30_000L, PlaybackSeekController.DIRECTION_FORWARD))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).containsExactly(GENERATION to 30_000L)
    }

    @Test
    fun `relative request after absolute request accumulates from pending absolute target`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = PlaybackSeekController(
            scope = this,
            onApplySeek = { generation, targetMs -> applied += generation to targetMs },
        )

        controller.onTargetRequested(
            generation = GENERATION,
            targetMs = 20_000L,
            currentPositionMs = 5_000L,
            durationMs = 60_000L,
        )
        controller.onDirectionRequested(
            generation = GENERATION,
            direction = PlaybackSeekController.DIRECTION_FORWARD,
            currentPositionMs = 5_000L,
            durationMs = 60_000L,
        )

        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(30_000L, PlaybackSeekController.DIRECTION_FORWARD))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).containsExactly(GENERATION to 30_000L)
    }

    @Test
    fun `absolute target clamps to finite duration`() = runTest {
        val applied = mutableListOf<Pair<Any, Long>>()
        val controller = PlaybackSeekController(
            scope = this,
            onApplySeek = { generation, targetMs -> applied += generation to targetMs },
        )

        controller.onTargetRequested(
            generation = GENERATION,
            targetMs = 120_000L,
            currentPositionMs = 5_000L,
            durationMs = 60_000L,
        )

        assertThat(controller.state.value)
            .isEqualTo(SeekControllerState.Pending(60_000L, PlaybackSeekController.DIRECTION_FORWARD))

        advanceTimeBy(PlaybackSeekPolicy.QUIET_WINDOW_MILLIS)
        runCurrent()

        assertThat(applied).containsExactly(GENERATION to 60_000L)
    }

    private companion object {
        const val GENERATION = 11L
    }
}
