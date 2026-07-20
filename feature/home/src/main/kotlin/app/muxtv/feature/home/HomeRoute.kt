package app.muxtv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvFocusSurface

@Composable
fun HomeRoute(
    onOpenChannels: () -> Unit,
    onOpenGuide: () -> Unit,
    onOpenSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.large),
    ) {
        Text("MuxTV", style = MaterialTheme.typography.displayMedium)
        Text(
            "Ваши каналы. Собранные как надо.",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        MuxTvFocusSurface(
            onClick = onOpenChannels,
            modifier = Modifier.fillMaxWidth().height(220.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                Text("Прямой эфир", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Добавьте источник, чтобы MuxTV собрал единый список каналов.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium)) {
            MuxTvFocusSurface(
                onClick = onOpenGuide,
                modifier = Modifier.width(300.dp).height(130.dp),
            ) { Text("Телепрограмма", style = MaterialTheme.typography.titleLarge) }
            MuxTvFocusSurface(
                onClick = onOpenSearch,
                modifier = Modifier.width(300.dp).height(130.dp),
            ) { Text("Поиск", style = MaterialTheme.typography.titleLarge) }
        }
    }
}
