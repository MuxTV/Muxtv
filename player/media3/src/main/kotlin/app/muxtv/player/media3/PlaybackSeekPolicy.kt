package app.muxtv.player.media3

/**
 * Baseline seek interaction policy for remote (D-pad) input.
 *
 * One short press moves the virtual target by [STEP_MILLIS]. Repeated presses accumulate
 * additively while the burst stays inside [QUIET_WINDOW_MILLIS]; exactly one actual player seek
 * is applied per burst. After confirmation the HUD lingers for [HUD_LINGER_MILLIS].
 *
 * Acceleration tiers for long/held presses are intentionally absent: they require the #111
 * repeat-event evidence before any constant is copied or invented.
 */
object PlaybackSeekPolicy {
    const val STEP_MILLIS = 10_000L
    const val QUIET_WINDOW_MILLIS = 400L
    const val HUD_LINGER_MILLIS = 1_500L
}
