package app.muxtv.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.database.DatabaseDefaults
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.channels.ChannelsRoute
import app.muxtv.feature.home.HomeRoute

@Composable
fun AppNavigation(
    playbackCatalog: PlaybackCatalog,
    modifier: Modifier = Modifier,
) {
    val backStack = remember { mutableStateListOf(AppDestination.initial) }
    fun open(destination: AppDestination) {
        if (backStack.lastOrNull() != destination) backStack.add(destination)
    }

    val current = backStack.lastOrNull() ?: AppDestination.initial
    Column(modifier = modifier.fillMaxSize()) {
        NavigationRow(current = current.topLevelDestination(), onOpen = ::open)
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryProvider = { destination ->
                NavEntry(destination) {
                    when (destination) {
                        AppDestination.Home -> HomeRoute(
                            onOpenChannels = { open(AppDestination.Channels) },
                            onOpenGuide = { open(AppDestination.Guide) },
                            onOpenSearch = { open(AppDestination.Search) },
                        )

                        AppDestination.Channels -> ChannelsRoute(
                            playbackCatalog = playbackCatalog,
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            onOpenChannel = { channelId ->
                                open(AppDestination.Player(channelId))
                            },
                        )

                        AppDestination.Guide -> PlaceholderRoute("Телепрограмма")
                        AppDestination.Search -> PlaceholderRoute("Поиск")
                        is AppDestination.Player -> PlaceholderRoute(
                            title = "Воспроизведение",
                            message = "Канал ${destination.channelId} выбран. " +
                                "MediaController-backed экран подключается следующим срезом.",
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun NavigationRow(
    current: AppDestination,
    onOpen: (AppDestination) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = 56.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        AppDestination.topLevel.forEach { destination ->
            val label = when (destination) {
                AppDestination.Home -> "Главная"
                AppDestination.Channels -> "Каналы"
                AppDestination.Guide -> "Программа"
                AppDestination.Search -> "Поиск"
                is AppDestination.Player -> error("Player is not a top-level destination.")
            }
            MuxTvActionButton(
                text = if (destination == current) "• $label" else label,
                onClick = { onOpen(destination) },
            )
        }
        Text(
            text = "Основной",
            modifier = Modifier.padding(start = 24.dp, top = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaceholderRoute(
    title: String,
    message: String = "Раздел заложен в навигацию и будет реализован на следующем этапе.",
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun AppDestination.topLevelDestination(): AppDestination = when (this) {
    is AppDestination.Player -> AppDestination.Channels
    else -> this
}
