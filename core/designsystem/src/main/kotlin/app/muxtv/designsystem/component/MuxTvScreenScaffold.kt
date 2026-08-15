package app.muxtv.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens

/**
 * Standard TV screen frame: bounded safe insets, screen title row and the
 * top-right utility clock. One consistent geometry across destinations.
 */
@Composable
fun MuxTvScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    horizontalInset: Dp = TvTokens.Spacing.screenInset,
    verticalInset: Dp = 28.dp,
    showClock: Boolean = true,
    titleTestTag: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = horizontalInset, vertical = verticalInset),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier,
                    ),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showClock) {
                MuxTvClock()
            }
        }
        Spacer(Modifier.height(TvTokens.Spacing.medium))
        content()
    }
}
