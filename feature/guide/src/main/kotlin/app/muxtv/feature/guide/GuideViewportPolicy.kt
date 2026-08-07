package app.muxtv.feature.guide

import app.muxtv.catalog.GuideChannelWindowQuery
import app.muxtv.catalog.GuideProgrammeWindowQuery

internal object GuideViewportPolicy {
    const val CHANNEL_LIMIT: Int = GuideChannelWindowQuery.DEFAULT_LIMIT
    const val DEFAULT_TIME_SPAN_MILLIS: Long = 6L * 60L * 60L * 1_000L
    const val MAX_PROGRAMME_ATTEMPTS: Int = 4

    fun timeSpanMillis(attemptIndex: Int): Long {
        require(attemptIndex in 0 until MAX_PROGRAMME_ATTEMPTS)
        return when (attemptIndex) {
            0 -> DEFAULT_TIME_SPAN_MILLIS
            1 -> 3L * 60L * 60L * 1_000L
            2 -> 90L * 60L * 1_000L
            else -> 45L * 60L * 1_000L
        }.also { span ->
            require(span <= GuideProgrammeWindowQuery.MAX_SPAN_MILLIS)
        }
    }
}
