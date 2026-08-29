package app.muxtv.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.player.PlaybackSeekDirection

/** Presentation-only seek state. Actual player mutation remains owned by the playback service. */
internal sealed interface SeekPresentationState {
    data object Idle : SeekPresentationState

    data class Pending(
        val targetMs: Long,
        val direction: PlaybackSeekDirection,
    ) : SeekPresentationState

    data class Applying(
        val targetMs: Long,
        val direction: PlaybackSeekDirection,
    ) : SeekPresentationState

    data class Completed(
        val targetMs: Long,
        val direction: PlaybackSeekDirection,
    ) : SeekPresentationState
}

/**
 * Transient seek HUD shown while the overlay is hidden: immediate virtual target with a
 * direction arrow. Hidden entirely in [SeekPresentationState.Idle]; the target remains
 * presentation-only until the stable playback session reports the applied timeline.
 */
@Composable
internal fun SeekHud(
    state: SeekPresentationState,
    modifier: Modifier = Modifier,
    testTag: String = "player-seek-hud",
) {
    when (state) {
        SeekPresentationState.Idle -> Unit
        is SeekPresentationState.Pending,
        is SeekPresentationState.Applying,
        is SeekPresentationState.Completed,
        -> {
            val targetMs = when (state) {
                is SeekPresentationState.Pending -> state.targetMs
                is SeekPresentationState.Applying -> state.targetMs
                is SeekPresentationState.Completed -> state.targetMs
                SeekPresentationState.Idle -> 0L
            }
            val direction = when (state) {
                is SeekPresentationState.Pending -> state.direction
                is SeekPresentationState.Applying -> state.direction
                is SeekPresentationState.Completed -> state.direction
                SeekPresentationState.Idle -> null
            }
            Row(
                modifier = modifier
                    .clip(RoundedCornerShape(TvTokens.Shape.cardCorner))
                    .background(TvTokens.Color.surfaceRaised.copy(alpha = 0.94f))
                    .padding(
                        horizontal = TvTokens.Spacing.large,
                        vertical = TvTokens.Spacing.small,
                    )
                    .semantics(mergeDescendants = true) { }
                    .testTag(testTag),
                horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.xSmall),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = seekDirectionArrow(direction),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = TrackLabelFormatter.formatPlaybackTime(targetMs),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
        }
    }
}

private fun seekDirectionArrow(direction: PlaybackSeekDirection?): String = when (direction) {
    PlaybackSeekDirection.BACKWARD -> "←"
    PlaybackSeekDirection.FORWARD -> "→"
    null -> ""
}
