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
    fun releasedControllerIsInvalidatedAndNextConnectionCanUseTheSession() {
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
            val command = MuxTvPlaybackSessionContract.setPlaybackRequestCommand
            val commandAvailable = AtomicBoolean(false)
            val resultFuture = AtomicReference<Future<SessionResult>>()

            instrumentation.runOnMainSync {
                commandAvailable.set(second.isSessionCommandAvailable(command))
                resultFuture.set(second.sendCustomCommand(command, Bundle()))
            }

            assertThat(second).isNotSameInstanceAs(first)
            assertThat(commandAvailable.get()).isTrue()
            val result = resultFuture.get().get(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertThat(result.resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        } finally {
            connector.close()
        }
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

    private companion object {
        const val CONNECTION_TIMEOUT_SECONDS = 20L
        const val COMMAND_TIMEOUT_SECONDS = 10L
    }
}
