package app.muxtv.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvClockTextTest {
    @Test
    fun `formats as two digit hours and minutes in explicit zone`() {
        val text = clockText(epochMillis = 0L, timeZoneId = "UTC")
        assertThat(text).matches("\\d{2}:\\d{2}")
    }

    @Test
    fun `midnight utc is zero zero`() {
        assertThat(clockText(epochMillis = 0L, timeZoneId = "UTC")).isEqualTo("00:00")
    }
}
