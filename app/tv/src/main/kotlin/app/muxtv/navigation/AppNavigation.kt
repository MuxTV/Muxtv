package app.muxtv.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.DatabaseDefaults
import app.muxtv.database.SourceRefreshStore
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.feature.channels.ChannelsRoute
import app.muxtv.feature.home.HomeRoute
import app.muxtv.feature.player.PlayerRoute
import app.muxtv.feature.sources.AddSourceRoute
import app.muxtv.feature.sources.SourceEntryOnboarding
import app.muxtv.feature.sources.SourcePlaybackApprovalActions
import app.muxtv.feature.sources.SourcesRoute
import app.muxtv.player.media3.MuxTvMediaControllerConnector

@Composable
fun AppNavigation(
    playbackCatalog: PlaybackCatalog,
    epgGuideRepository: EpgGuideRepository,
    controllerConnector: MuxTvMediaControllerConnector,
    sourceRefreshStore: SourceRefreshStore,
    sourceRefreshScheduler: SourceRefreshScheduler,
    sourceEntryOnboarding: SourceEntryOnboarding,
    sourcePlaybackApprovalActions: SourcePlaybackApprovalActions =
        SourcePlaybackApprovalActions.Unavailable,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(AppDestination.initial)
    val initialNavigationFocusRequester = remember { FocusRequester() }

    fun open(destination: AppDestination) {
        if (backStack.lastOrNull() != destination) backStack.add(destination)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    val current = backStack.lastOrNull() as? AppDestination ?: AppDestination.initial

    LaunchedEffect(Unit) {
        withFrameNanos { }
        initialNavigationFocusRequester.requestFocus()
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (current !is AppDestination.Player && current != AppDestination.AddSource) {
            NavigationRow(
                current = current.topLevelDestination(),
                initialFocusRequester = initialNavigationFocusRequester,
                onOpen = ::open,
            )
        }
        NavDisplay(
            modifier = Modifier.fillMaxWidth().weight(1f),
            backStack = backStack,
            onBack = ::goBack,
            entryProvider = { key ->
                val destination = key as AppDestination
                NavEntry(key) {
                    when (destination) {
                        AppDestination.Home -> HomeRoute(
                            onOpenChannels = { open(AppDestination.Channels) },
                            onOpenGuide = { open(AppDestination.Guide) },
                            onOpenSearch = { open(AppDestination.Search) },
                        )

                        AppDestination.Channels -> ChannelsRoute(
                            playbackCatalog = playbackCatalog,
                            epgGuideRepository = epgGuideRepository,
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            onOpenChannel = { channelId ->
                                open(AppDestination.Player(channelId))
                            },
                        )

                        AppDestination.Guide -> PlaceholderRoute("Телепрограмма")
                        AppDestination.Search -> PlaceholderRoute("Поиск")
                        AppDestination.Sources -> SourcesRoute(
                            refreshStore = sourceRefreshStore,
                            refreshScheduler = sourceRefreshScheduler,
                            playbackApprovalActions = sourcePlaybackApprovalActions,
                            onAddSource = { open(AppDestination.AddSource) },
                        )

                        AppDestination.AddSource -> AddSourceRoute(
                            onboarding = sourceEntryOnboarding,
                            onCompleted = ::goBack,
                            onBack = ::goBack,
                        )

                        is AppDestination.Player -> PlayerRoute(
                            playbackCatalog = playbackCatalog,
                            controllerConnector = controllerConnector,
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            channelId = destination.channelId,
                            onBack = ::goBack,
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
    initialFocusRequester: FocusRequester,
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
                AppDestination.Sources -> "Источники"
                AppDestination.AddSource -> error("AddSource is not a top-level destination.")
                is AppDestination.Player -> error("Player is not a top-level destination.")
            }
            val focusModifier = if (destination == current) {
                Modifier.focusRequester(initialNavigationFocusRequester)
            } else {
                Modifier
            }
            MuxTvActionButton(
                text = if (destination == current) "• $label" else label,
                onClick = { onOpen(destination) },
                modifier = Modifier
                    .testTag(destination.navigationTestTag())
                    .then(focusModifier),
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

private fun AppDestination.navigationTestTag(): String = when (this) {
    AppDestination.Home -> "nav-home"
    AppDestination.Channels -> "nav-channels"
    AppDestination.Guide -> "nav-guide"
    AppDestination.Search -> "nav-search"
    AppDestination.Sources -> "nav-sources"
    AppDestination.AddSource -> error("AddSource is not a top-level destination.")
    is AppDestination.Player -> error("Player is not a top-level destination.")
}

private fun AppDestination.topLevelDestination(): AppDestination = when (this) {
    AppDestination.AddSource -> AppDestination.Sources
    is AppDestination.Player -> AppDestination.Channels
    else -> this
}
