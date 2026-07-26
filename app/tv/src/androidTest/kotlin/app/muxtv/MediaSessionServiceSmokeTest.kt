package app.muxtv

import android.content.Context
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.muxtv.player.media3.MuxTvMediaControllerConnector
import app.muxtv.player.media3.MuxTvPlaybackSessionContract
import app.muxtv.player.media3.PlaybackSessionRequest
import app.muxtv.player.media3.PlaybackSetupId
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaSessionServiceSmokeTest {
    @Test
    fun releasedControllerReconnectsAndSetupCancellationRemainsOwned() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            val first = connector.connect().get(CONNECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            instrumentation.runOnMainSync(first::release)

            val second = awaitDifferentController(
                connector = connector,
                previous = first,
            )
            val setupCommand = MuxTvPlaybackSessionContract.setPlaybackRequestCommand
            val cancelCommand = MuxTvPlaybackSessionContract.cancelPlaybackSetupCommand
            val setupAvailable = AtomicBoolean(false)
            val cancelAvailable = AtomicBoolean(false)

            instrumentation.runOnMainSync {
                setupAvailable.set(second.isSessionCommandAvailable(setupCommand))
                cancelAvailable.set(second.isSessionCommandAvailable(cancelCommand))
            }

            assertThat(second).isNotSameInstanceAs(first)
            assertThat(setupAvailable.get()).isTrue()
            assertThat(cancelAvailable.get()).isTrue()
            assertThat(send(second, setupCommand, Bundle()).resultCode)
                .isEqualTo(SessionError.ERROR_BAD_VALUE)

            val cancelledBeforeInstall = setupId("20000000-0000-0000-0000-000000000001")
            assertThat(
                send(
                    second,
                    cancelCommand,
                    MuxTvPlaybackSessionContract.cancelArgs(cancelledBeforeInstall),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            assertThat(
                send(
                    second,
                    setupCommand,
                    MuxTvPlaybackSessionContract.setupArgs(
                        cancelledBeforeInstall,
                        request("cancelled-before-install"),
                    ),
                ).resultCode,
            ).isEqualTo(SessionError.INFO_CANCELLED)

            val firstInstalled = setupId("20000000-0000-0000-0000-000000000002")
            assertThat(
                send(
                    second,
                    setupCommand,
                    MuxTvPlaybackSessionContract.setupArgs(
                        firstInstalled,
                        request("first-installed"),
                    ),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            assertThat(
                send(
                    second,
                    cancelCommand,
                    MuxTvPlaybackSessionContract.cancelArgs(firstInstalled),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)

            val currentInstalled = setupId("20000000-0000-0000-0000-000000000003")
            assertThat(
                send(
                    second,
                    setupCommand,
                    MuxTvPlaybackSessionContract.setupArgs(
                        currentInstalled,
                        request("current-installed"),
                    ),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            awaitMediaId(second, "current-installed")

            assertThat(
                send(
                    second,
                    cancelCommand,
                    MuxTvPlaybackSessionContract.cancelArgs(firstInstalled),
                ).resultCode,
            ).isEqualTo(SessionResult.RESULT_SUCCESS)
            awaitMediaId(second, "current-installed")
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
            Thread.sleep(50)
        }
        throw AssertionError("Expected the current setup to remain installed.")
    }

    private fun awaitDifferentController(
        connector: MuxTvMediaControllerConnector,
        previous: MediaController,
    ): MediaController {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONNECTION_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val candidate = connector.connect().get(5, TimeUnit.SECONDS)
            if (candidate !== previous) return candidate
            Thread.sleep(50)
        }
        throw AssertionError("Connector did not create a fresh MediaController after disconnect.")
    }

    private fun setupId(raw: String): PlaybackSetupId =
        requireNotNull(PlaybackSetupId.parse(raw))

    private fun request(mediaId: String): PlaybackSessionRequest = PlaybackSessionRequest(
        mediaId = mediaId,
        variantId = "variant",
        locator = "https://127.0.0.1/stream.m3u8",
    )

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 20L
        const val COMMAND_TIMEOUT_SECONDS = 10L
    }
}
