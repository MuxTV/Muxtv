package app.muxtv.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgrammeTimingTest {
    @Test
    fun `null when window empty or moment outside`() {
        assertThat(programmeProgressFraction(1_500, 1_000, 1_000)).isNull()
        assertThat(programmeProgressFraction(500, 1_000, 3_000)).isNull()
        assertThat(programmeProgressFraction(4_000, 1_000, 3_000)).isNull()
    }

    @Test
    fun `midpoint and bounds clamp correctly`() {
        assertThat(programmeProgressFraction(2_000, 1_000, 3_000)).isWithin(0.01f).of(0.5f)
        assertThat(programmeProgressFraction(1_000, 1_000, 3_000)).isWithin(0.01f).of(0f)
        assertThat(programmeProgressFraction(3_000, 1_000, 3_000)).isWithin(0.01f).of(1f)
    }
}
