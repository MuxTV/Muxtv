package app.muxtv.feature.player

import app.muxtv.player.AudioTrackUiModel
import app.muxtv.player.SubtitleTrackUiModel
import java.util.Locale

/**
 * Pure UI label formatting for the overlay actions and the timeline.
 *
 * Full-text formatting lives in the track models themselves; these helpers only build compact
 * overlay summaries and time strings. The full human label is always available in the track
 * selection sheet.
 */
object TrackLabelFormatter {

    const val MAX_COMPACT_LABEL_LENGTH = 48

    fun audioActionLabel(models: List<AudioTrackUiModel>): String {
        val selected = models.firstOrNull { it.selected } ?: return "Аудио"
        return "Аудио · ${selected.primaryLabel.compact()}"
    }

    fun subtitleActionLabel(
        models: List<SubtitleTrackUiModel>,
        textDisabled: Boolean,
    ): String {
        if (textDisabled) return "Субтитры"
        val selected = models.firstOrNull { it.selected } ?: return "Субтитры"
        return "Субтитры · ${selected.primaryLabel.compact()}"
    }

    fun formatPlaybackTime(positionMs: Long): String {
        if (positionMs < 0L) return "–:––"
        val totalSeconds = positionMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    private fun String.compact(): String = if (length <= MAX_COMPACT_LABEL_LENGTH) {
        this
    } else {
        take(MAX_COMPACT_LABEL_LENGTH).trimEnd() + "…"
    }
}
