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
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import app.muxtv.player.media3.DebugDisconnectableMediaSessionService
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.media3.MuxTvPlaybackSessionContract
import app.muxtv.player.media3.PlaybackSetupId
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

        } finally {
            connector.close()
        }
    }

    @Test
    fun identityOnlyStartFailsClosedWhenChannelIsAbsent() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val connector = MuxTvMediaControllerConnector(context)
        try {
            val controller = runBlocking(Dispatchers.Main.immediate) {
                connector.awaitController(
                    timeoutMillis = TimeUnit.SECONDS.toMillis(CONNECTION_TIMEOUT_SECONDS),
                )
            }
            val result = runBlocking(Dispatchers.Main.immediate) {
                connector.awaitPlaybackStart(
                    controller = controller,
                    request = request("missing-channel"),
                    timeoutMillis = TimeUnit.SECONDS.toMillis(COMMAND_TIMEOUT_SECONDS),
                )
            }

            assertThat(result).isEqualTo(
                PlaybackStartResult.Rejected(PlaybackStartFailure.ChannelUnavailable),
            )
            assertThat(connector.playbackSessionState.value.phase)
                .isEqualTo(PlaybackSessionPhase.IDLE)
        } finally {
            connector.close()
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

    private fun request(mediaId: String): PlaybackStartRequest = PlaybackStartRequest(
        profileId = PROFILE_ID,
        channelId = mediaId,
        preferredVariantId = "variant",
    )

    private companion object {
        const val PROFILE_ID = "profile-main"
        const val CONNECTION_TIMEOUT_SECONDS = 20L
        const val DISCONNECT_TIMEOUT_SECONDS = 10L
        const val COMMAND_TIMEOUT_SECONDS = 10L
        const val POLL_INTERVAL_MILLIS = 50L
    }
}
