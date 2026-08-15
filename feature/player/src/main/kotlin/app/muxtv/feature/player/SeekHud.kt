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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.player.media3.PlaybackSeekController
import app.muxtv.player.media3.SeekControllerState

/**
 * Transient seek HUD shown while the overlay is hidden: immediate virtual target with a
 * direction arrow. Hidden entirely in [SeekControllerState.Idle]; the target is independent
 * from the actual player position until Media3 confirms the applied seek.
 */
@Composable
fun SeekHud(
    state: SeekControllerState,
    modifier: Modifier = Modifier,
    testTag: String = "player-seek-hud",
) {
    when (state) {
        SeekControllerState.Idle -> Unit
        is SeekControllerState.Pending,
        is SeekControllerState.Applying,
        is SeekControllerState.Completed,
        -> {
            val targetMs = when (state) {
                is SeekControllerState.Pending -> state.targetMs
                is SeekControllerState.Applying -> state.targetMs
                is SeekControllerState.Completed -> state.targetMs
                SeekControllerState.Idle -> 0L
            }
            val direction = when (state) {
                is SeekControllerState.Pending -> state.direction
                is SeekControllerState.Applying -> state.direction
                is SeekControllerState.Completed -> state.direction
                SeekControllerState.Idle -> PlaybackSeekController.DIRECTION_NONE
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

private fun seekDirectionArrow(direction: Int): String = when {
    direction < 0 -> "←"
    direction > 0 -> "→"
    else -> ""
}
