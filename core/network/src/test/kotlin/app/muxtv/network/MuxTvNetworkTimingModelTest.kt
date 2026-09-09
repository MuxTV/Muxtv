package app.muxtv.network

import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Modifier
import org.junit.Assert.assertThrows
import org.junit.Test

class MuxTvNetworkTimingModelTest {
    @Test
    fun `timing taxonomy is closed and stable`() {
        assertThat(MuxTvNetworkClientKind.entries).containsExactly(
            MuxTvNetworkClientKind.SOURCE,
            MuxTvNetworkClientKind.PLAYBACK,
        ).inOrder()
        assertThat(MuxTvNetworkPhase.entries).containsExactly(
            MuxTvNetworkPhase.DNS,
            MuxTvNetworkPhase.CONNECT,
            MuxTvNetworkPhase.TLS,
            MuxTvNetworkPhase.CONNECTION_ACQUIRE,
            MuxTvNetworkPhase.REQUEST,
            MuxTvNetworkPhase.TTFB,
            MuxTvNetworkPhase.RESPONSE_BODY,
            MuxTvNetworkPhase.TOTAL,
        ).inOrder()
        assertThat(MuxTvNetworkOutcome.entries).containsExactly(
            MuxTvNetworkOutcome.SUCCEEDED,
            MuxTvNetworkOutcome.FAILED,
        ).inOrder()
    }

    @Test
    fun `observation cannot carry arbitrary secret bearing text`() {
        val observation = MuxTvNetworkTimingObservation(
            clientKind = MuxTvNetworkClientKind.SOURCE,
            phase = MuxTvNetworkPhase.DNS,
            outcome = MuxTvNetworkOutcome.SUCCEEDED,
            durationNanos = 123L,
        )

        assertThat(observation.durationNanos).isEqualTo(123L)
        val instanceFields = MuxTvNetworkTimingObservation::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
        assertThat(instanceFields.map { it.name }).containsExactly(
            "clientKind",
            "phase",
            "outcome",
            "durationNanos",
        )
        instanceFields.forEach { field ->
            assertThat(CharSequence::class.java.isAssignableFrom(field.type)).isFalse()
            assertThat(Throwable::class.java.isAssignableFrom(field.type)).isFalse()
        }
    }

    @Test
    fun `missing phase is explicit rather than fabricated as zero`() {
        val missing = MuxTvNetworkTimingObservation(
            clientKind = MuxTvNetworkClientKind.SOURCE,
            phase = MuxTvNetworkPhase.TLS,
            outcome = MuxTvNetworkOutcome.SUCCEEDED,
            durationNanos = null,
        )
        val measuredZero = MuxTvNetworkTimingObservation(
            clientKind = MuxTvNetworkClientKind.SOURCE,
            phase = MuxTvNetworkPhase.TLS,
            outcome = MuxTvNetworkOutcome.SUCCEEDED,
            durationNanos = 0L,
        )

        assertThat(missing.durationNanos).isNull()
        assertThat(measuredZero.durationNanos).isEqualTo(0L)
    }

    @Test
    fun `negative duration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            MuxTvNetworkTimingObservation(
                clientKind = MuxTvNetworkClientKind.SOURCE,
                phase = MuxTvNetworkPhase.TOTAL,
                outcome = MuxTvNetworkOutcome.FAILED,
                durationNanos = -1L,
            )
        }
    }

    @Test
    fun `duration calculation is monotonic and never negative`() {
        assertThat(muxTvNetworkDurationNanos(startNanos = 100L, endNanos = 175L)).isEqualTo(75L)
        assertThat(muxTvNetworkDurationNanos(startNanos = 175L, endNanos = 100L)).isEqualTo(0L)
    }
}
