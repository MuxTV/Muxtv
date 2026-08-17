package app.muxtv.player.media3

/**
 * Baseline semantic seek interaction policy shared by UI intent projection and service authority.
 *
 * One short press moves the virtual target by [STEP_MILLIS]. The service-owned scheduler coalesces
 * repeated requests inside [QUIET_WINDOW_MILLIS] and applies one actual player seek per burst.
 * After confirmation presentation may retain the HUD for [HUD_LINGER_MILLIS].
 *
 * Acceleration tiers for long/held presses are intentionally absent: they require the #111
 * repeat-event evidence before any constant is copied or invented.
 */
object PlaybackSeekPolicy {
    const val DIRECTION_NONE = 0
    const val DIRECTION_BACKWARD = -1
    const val DIRECTION_FORWARD = 1
    const val STEP_MILLIS = 10_000L
    const val QUIET_WINDOW_MILLIS = 400L
    const val HUD_LINGER_MILLIS = 1_500L
}
