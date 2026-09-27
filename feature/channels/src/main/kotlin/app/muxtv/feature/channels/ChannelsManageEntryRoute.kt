package app.muxtv.feature.channels

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.PlaybackSessionStateSource

/**
 * Adds the dedicated channel-management entry without changing the normal
 * Channels browse/filter surface or its focus-restoration implementation.
 */
@Composable
fun ChannelsRoute(
    channelBrowseRepository: ChannelBrowseRepository,
    channelPreferencesRepository: ChannelPreferencesRepository,
    epgGuideRepository: EpgGuideRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    onManageChannels: () -> Unit,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        ChannelsRoute(
            channelBrowseRepository = channelBrowseRepository,
            epgGuideRepository = epgGuideRepository,
            playbackSessionStateSource = playbackSessionStateSource,
            profileId = profileId,
            onOpenChannel = onOpenChannel,
            modifier = Modifier.fillMaxSize(),
            railFocusRequester = railFocusRequester,
            channelPreferencesRepository = channelPreferencesRepository,
        )
        MuxTvActionButton(
            text = "Управление",
            onClick = onManageChannels,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 88.dp, end = TvTokens.Spacing.screenInset)
                .testTag(CHANNELS_MANAGE_TEST_TAG),
        )
    }
}

internal const val CHANNELS_MANAGE_TEST_TAG = "channels-manage"
