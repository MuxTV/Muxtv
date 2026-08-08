package app.muxtv.feature.guide

import java.time.Instant
import java.time.ZoneId

internal fun nextLocalHalfHourEpochMillis(
    epochMillis: Long,
    zoneId: ZoneId,
): Long {
    val current = Instant.ofEpochMilli(epochMillis).atZone(zoneId)
    if (
        (current.minute == 0 || current.minute == 30) &&
        current.second == 0 &&
        current.nano == 0
    ) {
        return epochMillis
    }

    val targetLocal = current.toLocalDateTime()
        .withSecond(0)
        .withNano(0)
        .let { local ->
            if (local.minute < 30) {
                local.withMinute(30)
            } else {
                local.plusHours(1).withMinute(0)
            }
        }

    var target = targetLocal.atZone(zoneId)
    if (target.toInstant().toEpochMilli() < epochMillis) {
        target = target.withLaterOffsetAtOverlap()
    }

    return target.toInstant().toEpochMilli()
}
