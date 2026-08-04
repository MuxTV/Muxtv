package app.muxtv

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.media3.DebugDisconnectableMediaSessionService
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.media3.MuxTvPlaybackSessionContract
import app.muxtv.player.media3.PlaybackSessionRequest
import app.muxtv.player.media3.PlaybackSetupId
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSessionServiceSmokeTest {
    @Test
    fun remoteSessionDisconnectInvalidatesControllerAndReconnects() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connector = MuxTvMediaControllerConnector(
            context = context,
            serviceComponent = ComponentName(
                context,
                DebugDisconnectableMediaSessionService::class.java,
            ),
        )

        try {
            val first = connector.connect().get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            val epochBeforeRestart = connector.connectionEpoch.value

            instrumentation.runOnMainSync {
                DebugDisconnectableMediaSessionService.restartActiveSessionForTest()
            }
            awaitConnectionEpochChange(
                connector = connector,
                previousEpoch = epochBeforeRestart,
            )

            val second = connector.connect().get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            val secondConnected = AtomicBoolean(false)
            instrumentation.runOnMainSync {
                secondConnected.set(second.isConnected)
            }

            assertThat(second).isNotSameInstanceAs(first)
            assertThat(secondConnected.get()).isTrue()
        } finally {
            connector.close()
        }
    }

    @Test
    fun setupCancellationRemainsOwnedByTheService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            val controller = connector.connect().get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            instrumentation.waitForIdleSync()
            val setupCommand = MuxTvPlaybackSessionContract.setPlaybackRequestCommand
            val cancelCommand = MuxTvPlaybackSessionContract.cancelPlaybackSetupCommand
            val setupAvailable = AtomicBoolean(false)
            val cancelAvailable = AtomicBoolean(false)

            instrumentation.runOnMainSync {
                setupAvailable.set(controller.isSessionCommandAvailable(setupCommand))
                cancelAvailable.set(controller.isSessionCommandAvailable(cancelCommand))
            }

            assertThat(setupAvailable.get()).isTrue()
            assertThat(cancelAvailable.get()).isTrue()
            assertThat(send(controller, setupCommand, Bundle()).resultCode)
                .isEqualTo(SessionError.ERROR_BAD_VALUE)

            val cancelledBeforeInstall = setupId("20000000-0000-0000-0000-000000000001")
            assertThat(
                send(
                    controller,
                    cancelCommand,
                    MuxTvPlaybackSessionContract.cancelArgs(cancelledBeforeInstall),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            assertThat(
                send(
                    controller,
                    setupCommand,
                    MuxTvPlaybackSessionContract.setupArgs(
                        cancelledBeforeInstall,
                        request("cancelled-before-install"),
                    ),
                ).resultCode,
            ).isEqualTo(SessionError.ERROR_INVALID_STATE)

            val firstInstalled = setupId("20000000-0000-0000-0000-000000000002")
            assertThat(
                send(
                    controller,
                    setupCommand,
                    MuxTvPlaybackSessionContract.setupArgs(
                        firstInstalled,
                        request("first-installed"),
                    ),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            assertThat(
                send(
                    controller,
                    cancelCommand,
                    MuxTvPlaybackSessionContract.cancelArgs(firstInstalled),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)

            val currentInstalled = setupId("20000000-0000-0000-0000-000000000003")
            assertThat(
                send(
                    controller,
                    setupCommand,
                    MuxTvPlaybackSessionContract.setupArgs(
                        currentInstalled,
                        request("current-installed"),
                    ),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            awaitMediaId(controller, "current-installed")

            assertThat(
                send(
                    controller,
                    cancelCommand,
                    MuxTvPlaybackSessionContract.cancelArgs(firstInstalled),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            awaitMediaId(controller, "current-installed")
        } finally {
            connector.close()
        }
    }

    @Test
    fun playbackSessionStateTracksInstalledChannelAndClearsWhenStopped() {
        MockWebServer().use { server ->
            server.start()
            val context = ApplicationProvider.getApplicationContext<Context>()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val connector = MuxTvMediaControllerConnector(context)

            try {
                val controller = runBlocking(Dispatchers.Main.immediate) {
                    connector.awaitController(
                        timeoutMillis = TimeUnit.SECONDS.toMillis(CONNECTION_TIMEOUT_SECONDS),
                    )
                }
                val setupResult = runBlocking(Dispatchers.Main.immediate) {
                    connector.awaitPlaybackRequest(
                        controller = controller,
                        request = PlaybackSessionRequest(
                            profileId = PROFILE_ID,
                            mediaId = "tracked-channel",
                            variantId = "tracked-variant",
                            locator = server.url("/live.m3u8").toString(),
                            insecureHttpApproved = true,
                        ),
                        timeoutMillis = TimeUnit.SECONDS.toMillis(COMMAND_TIMEOUT_SECONDS),
                    )
                }

                assertThat(setupResult.resultCode).isEqualTo(SessionResult.RESULT_SUCCESS)
                val activeState = awaitPlaybackSessionChannel(
                    connector = connector,
                    expectedChannelId = "tracked-channel",
                )
                assertThat(activeState.phase).isEqualTo(PlaybackSessionPhase.BUFFERING)
                assertThat(activeState.isPlaying).isFalse()
                assertThat(activeState.toString()).doesNotContain("tracked-channel")

                instrumentation.runOnMainSync {
                    controller.stop()
                }
                awaitPlaybackSessionIdle(connector)
            } finally {
                connector.close()
            }
        }
    }

    private fun send(
        controller: MediaController,
        command: androidx.media3.session.SessionCommand,
        args: Bundle,
    ): SessionResult {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resultFuture = AtomicReference<Future<SessionResult>>()
        instrumentation.runOnMainSync {
            resultFuture.set(controller.sendCustomCommand(command, args))
        }
        return resultFuture.get().get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }

    private fun awaitMediaId(
        controller: MediaController,
        expected: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val actual = AtomicReference<String?>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            instrumentation.runOnMainSync {
                actual.set(controller.currentMediaItem?.mediaId)
            }
            if (actual.get() == expected) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Expected the current setup to remain installed.")
    }

    private fun awaitPlaybackSessionChannel(
        connector: MuxTvMediaControllerConnector,
        expectedChannelId: String,
    ): PlaybackSessionState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val state = connector.playbackSessionState.value
            if (state.channelId == expectedChannelId) return state
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Expected playback session state to expose the installed channel.")
    }

    private fun awaitPlaybackSessionIdle(connector: MuxTvMediaControllerConnector) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(COMMAND_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val state = connector.playbackSessionState.value
            if (state.phase == PlaybackSessionPhase.IDLE && state.channelId == null) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Expected playback session state to clear after stop.")
    }

    private fun awaitConnectionEpochChange(
        connector: MuxTvMediaControllerConnector,
        previousEpoch: Long,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCONNECT_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            if (connector.connectionEpoch.value > previousEpoch) return
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        throw AssertionError("Remote MediaSession disconnect was not observed.")
    }

    private fun setupId(raw: String): PlaybackSetupId =
        requireNotNull(PlaybackSetupId.parse(raw))

    private fun request(mediaId: String): PlaybackSessionRequest = PlaybackSessionRequest(
        profileId = PROFILE_ID,
        mediaId = mediaId,
        variantId = "variant",
        locator = "https://127.0.0.1/stream.m3u8",
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CONNECTION_TIMEOUT_SECONDS = 20L
        const val DISCONNECT_TIMEOUT_SECONDS = 10L
        const val COMMAND_TIMEOUT_SECONDS = 10L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
