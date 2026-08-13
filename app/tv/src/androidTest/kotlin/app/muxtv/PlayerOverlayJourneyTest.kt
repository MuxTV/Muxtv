package app.muxtv

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.test.core.app.ApplicationProvider
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannel
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlayableVariant
import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.PlaybackVariantResolution
import app.muxtv.catalog.ResolvedPlaybackRequest
import app.muxtv.designsystem.MuxTvTheme
import app.muxtv.feature.player.PlayerFavoriteAction
import app.muxtv.feature.player.PlayerRoute
import app.muxtv.feature.player.PlaybackStartGateway
import app.muxtv.player.PlaybackStartResult
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class PlayerOverlayJourneyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun controlsStayHiddenByDefaultUntilCenterPressRevealsOverlay() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = ReadyCatalog(),
                        controllerConnector = connector,
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                        playbackStartGateway = STARTED_GATEWAY,
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-surface")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-overlay").assertDoesNotExist()
            composeRule.onNodeWithTag("player-primary-action").assertDoesNotExist()
            composeRule.onNodeWithTag("player-surface").assertIsFocused()

            composeRule.onNodeWithTag("player-surface").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-primary-action")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-overlay").assertExists()
            composeRule.onNodeWithTag("player-primary-action").assertIsFocused()
        } finally {
            connector.close()
        }
    }

    @Test
    fun backClosesOverlayFirstAndFallsThroughOnlyWhenHidden() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = ReadyCatalog(),
                        controllerConnector = connector,
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                        playbackStartGateway = STARTED_GATEWAY,
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-surface")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-surface").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-primary-action")
                    .fetchSemanticsNodes().size == 1
            }

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-primary-action")
                    .fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("player-overlay").assertDoesNotExist()
            composeRule.runOnIdle {
                check(!composeRule.activity.isFinishing) {
                    "First Back must close the overlay, not leave the Player destination."
                }
            }

            composeRule.runOnUiThread {
                composeRule.activity.onBackPressedDispatcher.onBackPressed()
            }
            composeRule.waitForIdle()

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.activity.isFinishing
            }
        } finally {
            connector.close()
        }
    }

    @Test
    fun overlayAutoHidesAfterInactivityAndReturnsFocusToSurface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = ReadyCatalog(),
                        controllerConnector = connector,
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                        playbackStartGateway = STARTED_GATEWAY,
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-surface")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-surface").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-primary-action")
                    .fetchSemanticsNodes().size == 1
            }

            composeRule.waitUntil(timeoutMillis = 15_000) {
                composeRule.onAllNodesWithTag("player-primary-action")
                    .fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("player-overlay").assertDoesNotExist()
            composeRule.onNodeWithTag("player-surface").assertIsFocused()
        } finally {
            connector.close()
        }
    }

    @Test
    fun favoriteActionIsPartOfTheUnifiedOverlayControlSurface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = MuxTvMediaControllerConnector(context)
        var favoritePresses = 0

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = ReadyCatalog(),
                        controllerConnector = connector,
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                        playbackStartGateway = STARTED_GATEWAY,
                        favoriteAction = PlayerFavoriteAction(
                            label = "☆ В избранное",
                            enabled = true,
                            onClick = { favoritePresses += 1 },
                        ),
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-surface")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-favorite").assertDoesNotExist()

            composeRule.onNodeWithTag("player-surface").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }
            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-favorite")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-primary-action").assertIsFocused()
            composeRule.onNodeWithTag("player-favorite")
                .assertTextContains("☆ В избранное")
                .performClick()
            composeRule.runOnIdle { check(favoritePresses == 1) }
        } finally {
            connector.close()
        }
    }

    private class ReadyCatalog : PlaybackCatalog {
        override fun observeChannels(query: ChannelQuery): Flow<List<PlayableChannelSummary>> =
            flowOf(listOf(summary()))

        override suspend fun getChannel(
            profileId: String,
            channelId: String,
        ): PlayableChannel = PlayableChannel(
            summary = summary(),
            variants = listOf(
                PlayableVariant(
                    variantId = VARIANT_ID,
                    sourceId = "source-local",
                    sourceName = "Local Provider",
                    locator = LOCATOR,
                    userAgent = null,
                    referrer = null,
                ),
            ),
        )

        override suspend fun resolveVariant(
            profileId: String,
            channelId: String,
            preferredVariantId: String?,
        ): PlaybackVariantResolution = PlaybackVariantResolution.Ready(
            ResolvedPlaybackRequest(
                channelId = CHANNEL_ID,
                variantId = VARIANT_ID,
                locator = LOCATOR,
                requestHeaders = emptyMap(),
                insecureHttpApproved = true,
            ),
        )

        override suspend fun approveInsecurePlayback(
            profileId: String,
            channelId: String,
            variantId: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Applied

        override suspend fun revokeInsecurePlayback(
            profileId: String,
            channelId: String,
            variantId: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Applied

        private fun summary() = PlayableChannelSummary(
            channelId = CHANNEL_ID,
            displayName = "Local Channel",
            logoUrl = null,
            groupTitle = "Test",
            channelNumber = "1",
            isFavorite = false,
            variantCount = 1,
        )
    }

    private companion object {
        val STARTED_GATEWAY = PlaybackStartGateway { _, _, _ -> PlaybackStartResult.Started }
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-local"
        const val VARIANT_ID = "variant-local"
        const val LOCATOR = "http://127.0.0.1/live.m3u8"
    }
}
