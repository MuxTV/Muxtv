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
    fun ownAppControllerConnectsAndMalformedSetupIsRejected() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val connector = MuxTvMediaControllerConnector(context)

        try {
            val controller: MediaController = connector.connect().get(20, TimeUnit.SECONDS)
            val command = MuxTvPlaybackSessionContract.setPlaybackRequestCommand
            val commandAvailable = AtomicBoolean(false)
            val resultFuture = AtomicReference<Future<SessionResult>>()

            instrumentation.runOnMainSync {
                commandAvailable.set(controller.isSessionCommandAvailable(command))
                resultFuture.set(controller.sendCustomCommand(command, Bundle()))
            }

            assertThat(commandAvailable.get()).isTrue()
            val result = resultFuture.get().get(10, TimeUnit.SECONDS)
            assertThat(result.resultCode).isEqualTo(SessionError.ERROR_BAD_VALUE)
        } finally {
            connector.close()
        }
    }
}
