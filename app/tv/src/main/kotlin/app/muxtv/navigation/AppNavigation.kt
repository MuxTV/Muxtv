package app.muxtv.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.catalog.SourceManagement
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.database.DatabaseDefaults
import app.muxtv.designsystem.component.MuxTvNavigationRail
import app.muxtv.designsystem.component.MuxTvNavigationRailItem
import app.muxtv.designsystem.icon.MuxTvIcons
import app.muxtv.feature.channels.ChannelsRoute
import app.muxtv.feature.channels.ManageChannelsRoute
import app.muxtv.feature.doctor.DoctorExportStatus
import app.muxtv.feature.doctor.DoctorRoute
import app.muxtv.feature.guide.GuideRoute
import app.muxtv.feature.home.HomeRoute
import app.muxtv.feature.player.PlaybackStartGateway
import app.muxtv.feature.search.SearchRoute
import app.muxtv.feature.settings.SettingsRoute
import app.muxtv.feature.sources.AddSourceRoute
import app.muxtv.feature.sources.SourcesRoute
import app.muxtv.player.PlaybackObservationReader
import app.muxtv.player.PlaybackSessionGateway
import app.muxtv.player.media3.Media3PlaybackSurface
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Composable
fun AppNavigation(
    playbackCatalog: PlaybackCatalog,
    channelBrowseRepository: ChannelBrowseRepository,
    channelPreferencesRepository: ChannelPreferencesRepository,
    channelSearchRepository: ChannelSearchRepository,
    guideWindowRepository: GuideWindowRepository,
    recentChannelsRepository: RecentChannelsRepository,
    epgGuideRepository: EpgGuideRepository,
    controllerConnector: MuxTvMediaControllerConnector,
    playbackSessionGateway: PlaybackSessionGateway,
    sourceManagement: SourceManagement,
    sourceOnboarding: SourceOnboarding,
    playbackObservationReader: PlaybackObservationReader = PlaybackObservationReader { emptyList() },
    doctorExportStatus: DoctorExportStatus = DoctorExportStatus.IDLE,
    onExportDoctorReport: (String) -> Unit = {},
    playbackStartGateway: PlaybackStartGateway? = null,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(AppDestination.initial)
    val railFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var railFocused by remember { mutableStateOf(false) }

    fun open(destination: AppDestination) {
        if (backStack.lastOrNull() != destination) backStack.add(destination)
    }

    fun goBack() {
        if (backStack.size > 1) backStack.removeLastOrNull()
    }

    fun openDoctorFromPlayer() {
        if (backStack.lastOrNull() is AppDestination.Player) {
            backStack.removeLastOrNull()
        }
        open(AppDestination.Doctor)
    }

    val current = backStack.lastOrNull() as? AppDestination ?: AppDestination.initial
    val railVisible = current !is AppDestination.Player && current != AppDestination.AddSource

    BackHandler(enabled = railVisible && railFocused) {
        // Returning focus to the content group preserves spatial provenance and
        // lets focusRestorer() choose the exact item that previously owned focus.
        focusManager.moveFocus(FocusDirection.Right)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true },
    ) {
        NavDisplay(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = railContentReservation(railVisible))
                .focusRestorer(),
            backStack = backStack,
            onBack = ::goBack,
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = { key ->
                val destination = key as AppDestination
                NavEntry(key) {
                    when (destination) {
                        AppDestination.Home -> HomeRoute(
                            channelBrowseRepository = channelBrowseRepository,
                            recentChannelsRepository = recentChannelsRepository,
                            epgGuideRepository = epgGuideRepository,
                            playbackSessionStateSource = controllerConnector,
                            hasSources = rememberHasSources(sourceManagement),
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            onOpenChannel = { channelId ->
                                open(AppDestination.Player(channelId))
                            },
                            onOpenChannels = { open(AppDestination.Channels) },
                            onOpenGuide = { open(AppDestination.Guide) },
                            onAddSource = { open(AppDestination.AddSource) },
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.Channels -> ChannelsRoute(
                            channelBrowseRepository = channelBrowseRepository,
                            epgGuideRepository = epgGuideRepository,
                            playbackSessionStateSource = controllerConnector,
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            onOpenChannel = { channelId ->
                                open(AppDestination.Player(channelId))
                            },
                            onManageChannels = { open(AppDestination.ManageChannels) },
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.ManageChannels -> ManageChannelsRoute(
                            channelBrowseRepository = channelBrowseRepository,
                            channelPreferencesRepository = channelPreferencesRepository,
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.Guide -> GuideRoute(
                            repository = guideWindowRepository,
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            onOpenChannel = { channelId ->
                                open(AppDestination.Player(channelId))
                            },
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.Search -> SearchRoute(
                            repository = channelSearchRepository,
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            onOpenChannel = { channelId ->
                                open(AppDestination.Player(channelId))
                            },
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.Settings -> SettingsRoute(
                            onOpenSources = { open(AppDestination.Sources) },
                            onOpenDoctor = { open(AppDestination.Doctor) },
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.Sources -> SourcesRoute(
                            sourceManagement = sourceManagement,
                            topNavigationFocusRequester = null,
                            onAddSource = { open(AppDestination.AddSource) },
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.Doctor -> DoctorRoute(
                            observationReader = playbackObservationReader,
                            exportStatus = doctorExportStatus,
                            onExport = onExportDoctorReport,
                            railFocusRequester = railFocusRequester,
                        )

                        AppDestination.AddSource -> AddSourceRoute(
                            onboarding = sourceOnboarding,
                            onCompleted = ::goBack,
                            onBack = ::goBack,
                        )

                        is AppDestination.Player -> PlayerFavoriteRoute(
                            playbackCatalog = playbackCatalog,
                            channelPreferencesRepository = channelPreferencesRepository,
                            playbackSessionGateway = playbackSessionGateway,
                            playbackSurface = { session, surfaceModifier ->
                                Media3PlaybackSurface(
                                    session = session,
                                    modifier = surfaceModifier,
                                )
                            },
                            profileId = DatabaseDefaults.PRIMARY_PROFILE_ID,
                            channelId = destination.channelId,
                            onBack = ::goBack,
                            onOpenDoctor = ::openDoctorFromPlayer,
                            playbackStartGateway = playbackStartGateway,
                        )
                    }
                }
            },
        )

        if (railVisible) {
            MuxTvNavigationRail(
                items = topLevelRailItems(selected = current.topLevelDestination()),
                onSelect = { key -> open(destinationByRailKey(key)) },
                railFocusRequester = railFocusRequester,
                modifier = Modifier.fillMaxHeight(),
                onRailFocusChanged = { railFocused = it },
            )
        }
    }
}

@Composable
private fun rememberHasSources(sourceManagement: SourceManagement): Flow<Boolean> =
    remember(sourceManagement) {
        sourceManagement.observeOverviews().map { overviews -> overviews.isNotEmpty() }
    }

@Composable
private fun topLevelRailItems(selected: AppDestination): List<MuxTvNavigationRailItem> =
    AppDestination.topLevel.map { destination ->
        MuxTvNavigationRailItem(
            key = destination.navigationKey(),
            label = destination.navigationLabel(),
            icon = destination.navigationIcon(),
            selected = destination == selected,
            testTag = destination.navigationTestTag(),
        )
    }

private fun AppDestination.navigationKey(): String = when (this) {
    AppDestination.Home -> "home"
    AppDestination.Channels -> "channels"
    AppDestination.Guide -> "guide"
    AppDestination.Search -> "search"
    AppDestination.Settings -> "settings"
    AppDestination.ManageChannels -> error("ManageChannels is not a top-level destination.")
    AppDestination.Sources -> error("Sources is not a top-level destination.")
    AppDestination.Doctor -> error("Doctor is not a top-level destination.")
    AppDestination.AddSource -> error("AddSource is not a top-level destination.")
    is AppDestination.Player -> error("Player is not a top-level destination.")
}

private fun destinationByRailKey(key: String): AppDestination = when (key) {
    "home" -> AppDestination.Home
    "channels" -> AppDestination.Channels
    "guide" -> AppDestination.Guide
    "search" -> AppDestination.Search
    "settings" -> AppDestination.Settings
    else -> error("Unknown top-level navigation key: $key")
}

private fun AppDestination.navigationLabel(): String = when (this) {
    AppDestination.Home -> "Главная"
    AppDestination.Channels -> "Эфир"
    AppDestination.Guide -> "Программа"
    AppDestination.Search -> "Поиск"
    AppDestination.Settings -> "Настройки"
    AppDestination.ManageChannels -> error("ManageChannels is not a top-level destination.")
    AppDestination.Sources -> error("Sources is not a top-level destination.")
    AppDestination.Doctor -> error("Doctor is not a top-level destination.")
    AppDestination.AddSource -> error("AddSource is not a top-level destination.")
    is AppDestination.Player -> error("Player is not a top-level destination.")
}

private fun AppDestination.navigationIcon() = when (this) {
    AppDestination.Home -> MuxTvIcons.Home
    AppDestination.Channels -> MuxTvIcons.LiveTv
    AppDestination.Guide -> MuxTvIcons.Guide
    AppDestination.Search -> MuxTvIcons.Search
    AppDestination.Settings -> MuxTvIcons.Settings
    AppDestination.ManageChannels -> error("ManageChannels is not a top-level destination.")
    AppDestination.Sources -> error("Sources is not a top-level destination.")
    AppDestination.Doctor -> error("Doctor is not a top-level destination.")
    AppDestination.AddSource -> error("AddSource is not a top-level destination.")
    is AppDestination.Player -> error("Player is not a top-level destination.")
}

private fun AppDestination.navigationTestTag(): String = when (this) {
    AppDestination.Home -> "nav-home"
    AppDestination.Channels -> "nav-channels"
    AppDestination.Guide -> "nav-guide"
    AppDestination.Search -> "nav-search"
    AppDestination.Settings -> "nav-settings"
    AppDestination.ManageChannels -> error("ManageChannels is not a top-level destination.")
    AppDestination.Sources -> error("Sources is not a top-level destination.")
    AppDestination.Doctor -> error("Doctor is not a top-level destination.")
    AppDestination.AddSource -> error("AddSource is not a top-level destination.")
    is AppDestination.Player -> error("Player is not a top-level destination.")
}

private fun AppDestination.topLevelDestination(): AppDestination = when (this) {
    AppDestination.ManageChannels -> AppDestination.Channels
    AppDestination.Sources, AppDestination.Doctor, AppDestination.AddSource -> AppDestination.Settings
    is AppDestination.Player -> AppDestination.Channels
    else -> this
}
