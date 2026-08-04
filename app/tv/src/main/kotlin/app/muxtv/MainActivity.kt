package app.muxtv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.SourceRefreshStore
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.sources.SourceEntryOnboarding
import app.muxtv.feature.sources.SourcePlaybackApprovalActions
import app.muxtv.navigation.AppNavigation
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var playbackCatalog: PlaybackCatalog

    @Inject
    lateinit var channelPreferencesRepository: ChannelPreferencesRepository

    @Inject
    lateinit var channelSearchRepository: ChannelSearchRepository

    @Inject
    lateinit var epgGuideRepository: EpgGuideRepository

    @Inject
    lateinit var controllerConnector: MuxTvMediaControllerConnector

    @Inject
    lateinit var sourceRefreshStore: SourceRefreshStore

    @Inject
    lateinit var sourceRefreshScheduler: SourceRefreshScheduler

    @Inject
    lateinit var sourceEntryOnboarding: SourceEntryOnboarding

    @Inject
    lateinit var sourcePlaybackApprovalActions: SourcePlaybackApprovalActions

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MuxTvTheme {
                AppNavigation(
                    playbackCatalog = playbackCatalog,
                    channelPreferencesRepository = channelPreferencesRepository,
                    channelSearchRepository = channelSearchRepository,
                    epgGuideRepository = epgGuideRepository,
                    controllerConnector = controllerConnector,
                    sourceRefreshStore = sourceRefreshStore,
                    sourceRefreshScheduler = sourceRefreshScheduler,
                    sourceEntryOnboarding = sourceEntryOnboarding,
                    sourcePlaybackApprovalActions = sourcePlaybackApprovalActions,
                )
            }
        }
    }
}
