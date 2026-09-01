package app.muxtv

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import app.muxtv.feature.player.PlaybackStartGateway
import app.muxtv.feature.player.PlayerLocalNetworkPermissionOutcome
import app.muxtv.feature.player.PlayerRoute
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import app.muxtv.player.media3.Media3PlaybackSessionGateway
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test

class PlayerLocalNetworkPermissionTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun grantRetriesExactVariantBeforeIndependentHttpApproval() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = LocalNetworkJourneyCatalog()
        val connector = MuxTvMediaControllerConnector(context)
        val sessionGateway = Media3PlaybackSessionGateway(connector)
        var permissionRequests = 0

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = catalog,
                        playbackSessionGateway = sessionGateway,
                        playbackSurface = { _, modifier ->
                            Box(modifier = modifier.testTag("lan-test-video-surface")) {}
                        },
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                        playbackStartGateway = catalog.playbackStartGateway,
                        requestLocalNetworkPermission = {
                            permissionRequests += 1
                            PlayerLocalNetworkPermissionOutcome.GRANTED
                        },
                    )
                }
            }

            awaitLocalNetworkAction("Разрешить доступ")
            composeRule.onNodeWithText("journey-secret", substring = true).assertDoesNotExist()
            composeRule.runOnIdle {
                check(catalog.startRequests == listOf(PlaybackStartRequest(PROFILE_ID, CHANNEL_ID)))
            }

            composeRule.onNodeWithTag("player-local-network-action").pressEnter()

            awaitHttpApproval()
            composeRule.runOnIdle {
                check(permissionRequests == 1)
                check(catalog.startRequests.size == 2)
                check(catalog.startRequests[0].preferredVariantId == null)
                check(catalog.startRequests[1].preferredVariantId == VARIANT_ID)
            }

            composeRule.onNodeWithTag("player-http-approve").pressEnter()

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-surface")
                    .fetchSemanticsNodes().size == 1
            }
            composeRule.runOnIdle {
                check(catalog.approvalCalls == 1)
                check(catalog.startRequests.size >= 3)
                check(catalog.startRequests.drop(1).all { request ->
                    request.preferredVariantId == VARIANT_ID
                })
            }
        } finally {
            connector.close()
        }
    }

    @Test
    fun temporaryDenialStaysRecoverableWithoutRestartingPlayback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = LocalNetworkJourneyCatalog()
        val connector = MuxTvMediaControllerConnector(context)
        val sessionGateway = Media3PlaybackSessionGateway(connector)
        var permissionRequests = 0

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = catalog,
                        playbackSessionGateway = sessionGateway,
                        playbackSurface = { _, modifier -> Box(modifier = modifier) {} },
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                        playbackStartGateway = catalog.playbackStartGateway,
                        requestLocalNetworkPermission = {
                            permissionRequests += 1
                            PlayerLocalNetworkPermissionOutcome.DENIED
                        },
                    )
                }
            }

            awaitLocalNetworkAction("Разрешить доступ")
            composeRule.onNodeWithTag("player-local-network-action").pressEnter()

            composeRule.waitUntil(timeoutMillis = 20_000) {
                composeRule.onAllNodesWithTag("player-local-network-action")
                    .fetchSemanticsNodes().singleOrNull()?.config
                    ?.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
                    ?.any { it.text.contains("Повторить") } == true
            }
            composeRule.onNodeWithText("Доступ к локальной сети не предоставлен.").assertExists()
            composeRule.onNodeWithTag("player-local-network-action")
                .assertIsFocused()
                .assertTextContains("Повторить")
            composeRule.runOnIdle {
                check(permissionRequests == 1)
                check(catalog.startRequests.size == 1)
            }
        } finally {
            connector.close()
        }
    }

    @Test
    fun permanentDenialUsesSettingsThenRetriesExactVariant() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val catalog = LocalNetworkJourneyCatalog()
        val connector = MuxTvMediaControllerConnector(context)
        val sessionGateway = Media3PlaybackSessionGateway(connector)
        var permissionRequests = 0
        var settingsRequests = 0

        try {
            composeRule.setContent {
                MuxTvTheme {
                    PlayerRoute(
                        playbackCatalog = catalog,
                        playbackSessionGateway = sessionGateway,
                        playbackSurface = { _, modifier -> Box(modifier = modifier) {} },
                        profileId = PROFILE_ID,
                        channelId = CHANNEL_ID,
                        onBack = {},
                        playbackStartGateway = catalog.playbackStartGateway,
                        requestLocalNetworkPermission = {
                            permissionRequests += 1
                            PlayerLocalNetworkPermissionOutcome.PERMANENTLY_DENIED
                        },
                        openLocalNetworkPermissionSettings = {
                            settingsRequests += 1
                            true
                        },
                    )
                }
            }

            awaitLocalNetworkAction("Разрешить доступ")
            composeRule.onNodeWithTag("player-local-network-action").pressEnter()

            awaitLocalNetworkAction("Открыть настройки")
            composeRule.onNodeWithText(
                "Доступ к локальной сети отключён для MuxTV. Разрешите его в настройках Android.",
            ).assertExists()
            composeRule.onNodeWithTag("player-local-network-action").pressEnter()

            awaitHttpApproval()
            composeRule.runOnIdle {
                check(permissionRequests == 1)
                check(settingsRequests == 1)
                check(catalog.startRequests.size == 2)
                check(catalog.startRequests[1].preferredVariantId == VARIANT_ID)
            }
        } finally {
            connector.close()
        }
    }

    private fun awaitLocalNetworkAction(text: String) {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-local-network-action")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-local-network-action")
            .assertIsFocused()
            .assertTextContains(text)
    }

    private fun awaitHttpApproval() {
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTag("player-http-approve")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("player-http-approve")
            .assertIsFocused()
            .assertTextContains("Разрешить для этого адреса")
    }

    private class LocalNetworkJourneyCatalog : PlaybackCatalog {
        var approvalCalls: Int = 0
        var httpApproved: Boolean = false
        val startRequests = mutableListOf<PlaybackStartRequest>()

        val playbackStartGateway = PlaybackStartGateway { _, request, _ ->
            startRequests += request
            when {
                startRequests.size == 1 ->
                    PlaybackStartResult.LocalNetworkPermissionRequired(VARIANT_ID)

                !httpApproved ->
                    PlaybackStartResult.InsecureHttpApprovalRequired(
                        displayOrigin = DISPLAY_ORIGIN,
                        variantId = VARIANT_ID,
                    )

                else -> PlaybackStartResult.Started
            }
        }

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
                    sourceName = "Local provider",
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
                insecureHttpApproved = httpApproved,
            ),
        )

        override suspend fun approveInsecurePlayback(
            profileId: String,
            channelId: String,
            variantId: String,
        ): PlaybackAccessMutationResult {
            check(variantId == VARIANT_ID)
            approvalCalls += 1
            httpApproved = true
            return PlaybackAccessMutationResult.Applied
        }

        override suspend fun revokeInsecurePlayback(
            profileId: String,
            channelId: String,
            variantId: String,
        ): PlaybackAccessMutationResult = PlaybackAccessMutationResult.Unchanged

        private fun summary() = PlayableChannelSummary(
            channelId = CHANNEL_ID,
            displayName = "Local channel",
            logoUrl = null,
            groupTitle = "Test",
            channelNumber = "1",
            isFavorite = false,
            variantCount = 1,
        )
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.pressEnter() {
        performKeyInput {
            keyDown(Key.Enter)
            keyUp(Key.Enter)
        }
    }

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CHANNEL_ID = "channel-local"
        const val VARIANT_ID = "variant-local"
        const val DISPLAY_ORIGIN = "http://192.168.1.20:8080"
        const val LOCATOR = "$DISPLAY_ORIGIN/private/live.m3u8?token=journey-secret"
    }
}
