package app.muxtv.common

/**
 * Fraction of a programme window already elapsed, or null when the window is
 * empty or the moment lies outside it. Progress must never be rendered without
 * valid timing data.
 */
fun programmeProgressFraction(
    nowEpochMillis: Long,
    startEpochMillis: Long,
    endEpochMillis: Long,
): Float? {
    if (endEpochMillis <= startEpochMillis) return null
    if (nowEpochMillis < startEpochMillis || nowEpochMillis > endEpochMillis) return null
    val total = endEpochMillis - startEpochMillis
    return ((nowEpochMillis - startEpochMillis).toDouble() / total.toDouble())
        .toFloat()
        .coerceIn(0f, 1f)
}
