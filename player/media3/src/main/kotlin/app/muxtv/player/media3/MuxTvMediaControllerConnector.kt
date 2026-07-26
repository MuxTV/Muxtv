package app.muxtv.player.media3

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

@AndroidXOptIn(UnstableApi::class)
class MuxTvMediaControllerConnector(
    context: Context,
) : AutoCloseable {
    private val applicationContext = context.applicationContext
    private val applicationHandler = Handler(Looper.getMainLooper())
    private val applicationExecutor = Executor { command ->
        applicationHandler.post(command)
    }
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            connections.disconnected(controller)
        }
    }
    private val connections = ControllerConnectionRegistry<MediaController>(
        releasePending = ::releasePending,
        releaseConnected = ::releaseConnected,
    )

    fun connect(): ListenableFuture<MediaController> = connections.acquire {
        val token = SessionToken(
            applicationContext,
            ComponentName(applicationContext, MuxTvPlaybackService::class.java),
        )
        val future = MediaController.Builder(applicationContext, token)
            .setApplicationLooper(applicationHandler.looper)
            .setListener(controllerListener)
            .buildAsync()
        future.addListener(
            {
                connections.complete(
                    future = future,
                    result = runCatching { future.get() },
                )
            },
            applicationExecutor,
        )
        future
    }

    suspend fun awaitController(timeoutMillis: Long): MediaController = try {
        connect().awaitCancellable(
            timeoutMillis = timeoutMillis,
            cancelFutureOnCancellation = false,
        )
    } catch (timeout: TimeoutCancellationException) {
        throw MediaControllerOperationException(connectionFailureFor(timeout))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw MediaControllerOperationException(connectionFailureFor(error))
    }

    fun sendPlaybackRequest(
        controller: MediaController,
        request: PlaybackSessionRequest,
    ): ListenableFuture<SessionResult> = controller.sendCustomCommand(
        MuxTvPlaybackSessionContract.setPlaybackRequestCommand,
        request.toBundle(),
    )

    suspend fun awaitPlaybackRequest(
        controller: MediaController,
        request: PlaybackSessionRequest,
        timeoutMillis: Long,
    ): SessionResult = try {
        sendPlaybackRequest(controller, request).awaitCancellable(
            timeoutMillis = timeoutMillis,
            cancelFutureOnCancellation = true,
        )
    } catch (timeout: TimeoutCancellationException) {
        throw MediaControllerOperationException(commandFailureFor(timeout))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw MediaControllerOperationException(commandFailureFor(error))
    }

    override fun close() {
        connections.close()
    }

    private fun releasePending(future: ListenableFuture<MediaController>) {
        runOnApplicationLooper {
            MediaController.releaseFuture(future)
        }
    }

    private fun releaseConnected(controller: MediaController) {
        runOnApplicationLooper(controller::release)
    }

    private fun runOnApplicationLooper(action: () -> Unit) {
        if (Looper.myLooper() == applicationHandler.looper) {
            action()
        } else {
            applicationHandler.post(action)
        }
    }
}
