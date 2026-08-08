package app.muxtv.feature.guide

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Test

class GuideTimelineMathTest {
    @Test
    fun `half hour ticks align to local wall clock in quarter hour offset zones`() {
        val zone = ZoneId.of("Asia/Kathmandu")
        val viewportStart = localEpochMillis(
            zone = zone,
            hour = 10,
            minute = 7,
        )

        val tick = nextLocalHalfHourEpochMillis(
            epochMillis = viewportStart,
            zoneId = zone,
        )
        val localTick = Instant.ofEpochMilli(tick).atZone(zone)

        assertThat(localTick.hour).isEqualTo(10)
        assertThat(localTick.minute).isEqualTo(30)
        assertThat(localTick.second).isEqualTo(0)
        assertThat(localTick.nano).isEqualTo(0)
    }

    @Test
    fun `quarter hour offset rolls forty five past to next local hour`() {
        val zone = ZoneId.of("Asia/Kathmandu")
        val viewportStart = localEpochMillis(
            zone = zone,
            hour = 10,
            minute = 45,
        )

        val tick = nextLocalHalfHourEpochMillis(
            epochMillis = viewportStart,
            zoneId = zone,
        )
        val localTick = Instant.ofEpochMilli(tick).atZone(zone)

        assertThat(localTick.hour).isEqualTo(11)
        assertThat(localTick.minute).isEqualTo(0)
    }

    @Test
    fun `already aligned local half hour remains unchanged`() {
        val zone = ZoneId.of("Europe/Amsterdam")
        val viewportStart = localEpochMillis(
            zone = zone,
            hour = 10,
            minute = 30,
        )

        val tick = nextLocalHalfHourEpochMillis(
            epochMillis = viewportStart,
            zoneId = zone,
        )

        assertThat(tick).isEqualTo(viewportStart)
    }

    private fun localEpochMillis(
        zone: ZoneId,
        hour: Int,
        minute: Int,
    ): Long = ZonedDateTime.of(
        2026,
        8,
        7,
        hour,
        minute,
        0,
        0,
        zone,
    ).toInstant().toEpochMilli()
}
