package app.muxtv.designsystem.component

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun clockText(epochMillis: Long, timeZoneId: String? = null): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    if (timeZoneId != null) {
        format.timeZone = java.util.TimeZone.getTimeZone(timeZoneId)
    }
    return format.format(Date(epochMillis))
}
