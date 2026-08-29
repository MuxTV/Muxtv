package app.muxtv.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.catalog.SourceManagement
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.feature.doctor.DoctorExportStatus
import app.muxtv.feature.player.PlaybackStartGateway
import app.muxtv.player.PlaybackObservationReader
import app.muxtv.player.media3.Media3PlaybackSessionGateway
import app.muxtv.player.media3.MuxTvMediaControllerConnector

/**
 * App-level instrumentation composition fixture for tests that intentionally bind the real
 * Media3 adapter. The production navigation surface consumes [app.muxtv.player.PlaybackSessionGateway];
 * this overload keeps the adapter construction in the app test composition root rather than
 * leaking Media3 back into feature code.
 */
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
    sourceManagement: SourceManagement,
    sourceOnboarding: SourceOnboarding,
    playbackObservationReader: PlaybackObservationReader = PlaybackObservationReader { emptyList() },
    doctorExportStatus: DoctorExportStatus = DoctorExportStatus.IDLE,
    onExportDoctorReport: (String) -> Unit = {},
    playbackStartGateway: PlaybackStartGateway? = null,
    modifier: Modifier = Modifier,
) {
    val playbackSessionGateway = remember(controllerConnector) {
        Media3PlaybackSessionGateway(controllerConnector)
    }
    AppNavigation(
        playbackCatalog = playbackCatalog,
        channelBrowseRepository = channelBrowseRepository,
        channelPreferencesRepository = channelPreferencesRepository,
        channelSearchRepository = channelSearchRepository,
        guideWindowRepository = guideWindowRepository,
        recentChannelsRepository = recentChannelsRepository,
        epgGuideRepository = epgGuideRepository,
        controllerConnector = controllerConnector,
        playbackSessionGateway = playbackSessionGateway,
        sourceManagement = sourceManagement,
        sourceOnboarding = sourceOnboarding,
        playbackObservationReader = playbackObservationReader,
        doctorExportStatus = doctorExportStatus,
        onExportDoctorReport = onExportDoctorReport,
        playbackStartGateway = playbackStartGateway,
        modifier = modifier,
    )
}
