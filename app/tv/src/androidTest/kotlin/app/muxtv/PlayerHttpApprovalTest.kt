package app.muxtv

import android.content.Context
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import app.muxtv.feature.player.PlayerRoute
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class PlayerHttpApprovalTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exactOriginIsConfirmedBeforeThePlaybackRequestIsInstalled() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = ApprovalJourneyCatalog()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = catalog,
                        controllerConnector = connector,
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                    )
                }
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-http-approve")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-http-approve")
                .assertIsFocused()
                .assertTextContains("Разрешить для этого адреса")
            composeRule.onNodeWithText(
                "Разрешить воспроизведение только для http://127.0.0.1:80?",
            ).assertExists()
            composeRule.onNodeWithText("private-path", substring = true).assertDoesNotExist()
            composeRule.onNodeWithText("private-query", substring = true).assertDoesNotExist()
            check(catalog.approvalCalls == 0)
            check(catalog.readyResolutionCalls == 0)

            composeRule.onNodeWithTag("player-http-approve").performKeyInput {
                keyDown(Key.Enter)
                keyUp(Key.Enter)
            }

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-primary-action")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.onNodeWithTag("player-primary-action").assertIsFocused()
            check(catalog.approvalCalls == 1)
            check(catalog.readyResolutionCalls >= 1)
        } finally {
            connector.close()
        }
    }

    private class ApprovalJourneyCatalog : PlaybackCatalog {
        var approvalCalls: Int = 0
        var readyResolutionCalls: Int = 0
        private var approved: Boolean = false

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
                    sourceId = "source-http",
                    sourceName = "HTTP Provider",
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
        ): PlaybackVariantResolution {
            return if (approved) {
                readyResolutionCalls += 1
                PlaybackVariantResolution.Ready(
                    ResolvedPlaybackRequest(
                        channelId = CHANNEL_ID,
                        variantId = VARIANT_ID,
                        locator = LOCATOR,
                        requestHeaders = emptyMap(),
                        insecureHttpApproved = true,
                    ),
                )
            } else {
                PlaybackVariantResolution.InsecureTransportApprovalRequired(
                    channelId = CHANNEL_ID,
                    variantId = VARIANT_ID,
                    displayOrigin = "http://127.0.0.1:80",
                )
            }
        }

        override suspend fun approveInsecurePlayback(
            profileId: String,
            channelId: String,
            variantId: String,
        ): PlaybackAccessMutationResult {
            approvalCalls += 1
            approved = true
            return PlaybackAccessMutationResult.Applied
        }

        override suspend fun revokeInsecurePlayback(
            profileId: String,
            channelId: String,
            variantId: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unchanged

        private fun summary() = PlayableChannelSummary(
            channelId = CHANNEL_ID,
            displayName = "HTTP Channel",
            logoUrl = null,
            groupTitle = "Test",
            channelNumber = "1",
            isFavorite = false,
            variantCount = 1,
        )
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-http"
        const val VARIANT_ID = "variant-http"
        const val LOCATOR = "http://127.0.0.1/private-path/live.m3u8?token=private-query"
    }
}
