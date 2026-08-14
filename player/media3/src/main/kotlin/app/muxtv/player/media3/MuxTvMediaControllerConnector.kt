package app.muxtv.player.media3

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import app.muxtv.player.ExternalPlaybackLeaseId
import app.muxtv.player.ExternalPlaybackStartFailure
import app.muxtv.player.ExternalPlaybackStartResult
import app.muxtv.player.PlaybackSessionPhase
import app.muxtv.player.PlaybackSessionState
import app.muxtv.player.PlaybackSessionStateSource
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@AndroidXOptIn(UnstableApi::class)
class MuxTvMediaControllerConnector(
    context: Context,
    private val serviceComponent: ComponentName = ComponentName(
        context.applicationContext,
        MuxTvPlaybackService::class.java,
    ),
) : AutoCloseable, PlaybackSessionStateSource {
    private val applicationContext = context.applicationContext
    private val applicationHandler = Handler(Looper.getMainLooper())
    private val applicationExecutor = Executor { command ->
        applicationHandler.post(command)
    }
    private val mutableConnectionEpoch = MutableStateFlow(0L)
    val connectionEpoch: StateFlow<Long> = mutableConnectionEpoch.asStateFlow()

    private val mutablePlaybackSessionState = MutableStateFlow(PlaybackSessionState.Idle)
    override val playbackSessionState: StateFlow<PlaybackSessionState> =
        mutablePlaybackSessionState.asStateFlow()

    private var observedController: MediaController? = null
    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publishPlaybackSessionState(player)
        }
    }
    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (connections.disconnected(controller)) {
                if (observedController === controller) {
                    controller.removeListener(playerListener)
                    observedController = null
                    mutablePlaybackSessionState.value = PlaybackSessionState.Idle
                }
                mutableConnectionEpoch.update { epoch -> epoch + 1L }
            }
        }
    }
    private val connections = ControllerConnectionRegistry<MediaController>(
        releasePending = ::releasePending,
        releaseConnected = ::releaseConnected,
    )

    fun connect(): ListenableFuture<MediaController> = connections.acquire {
        val token = SessionToken(applicationContext, serviceComponent)
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
        val controller = connect().awaitCancellable(
            timeoutMillis = timeoutMillis,
            cancelFutureOnCancellation = false,
        )
        observePlaybackSession(controller)
        controller
    } catch (timeout: TimeoutCancellationException) {
        throw MediaControllerOperationException(connectionFailureFor(timeout))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        throw MediaControllerOperationException(connectionFailureFor(error))
    }

    fun sendPlaybackStart(
        controller: MediaController,
        request: PlaybackStartRequest,
    ): ListenableFuture<SessionResult> = sendPlaybackRequest(
        controller = controller,
        setupId = PlaybackSetupId.create(),
        request = request,
    )

    fun sendPlaybackRequest(
        controller: MediaController,
        setupId: PlaybackSetupId,
        request: PlaybackStartRequest,
    ): ListenableFuture<SessionResult> = controller.sendCustomCommand(
        MuxTvPlaybackSessionContract.setPlaybackRequestCommand,
        MuxTvPlaybackSessionContract.setupArgs(setupId, request),
    )

    suspend fun awaitPlaybackStart(
        controller: MediaController,
        request: PlaybackStartRequest,
        timeoutMillis: Long,
    ): PlaybackStartResult {
        val setupId = PlaybackSetupId.create()
        return try {
            val result = awaitPlaybackSetup(
                future = sendPlaybackRequest(
                    controller = controller,
                    setupId = setupId,
                    request = request,
                ),
                timeoutMillis = timeoutMillis,
                cancelSetup = { postCancel(controller, setupId) },
            )
            MuxTvPlaybackSessionContract.parseResult(result)
                ?: PlaybackStartResult.Rejected(PlaybackStartFailure.CommandFailed)
        } catch (timeout: TimeoutCancellationException) {
            throw MediaControllerOperationException(commandFailureFor(timeout))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MediaControllerOperationException(commandFailureFor(error))
        }
    }

    fun sendExternalPlaybackRequest(
        controller: MediaController,
        setupId: PlaybackSetupId,
        leaseId: ExternalPlaybackLeaseId,
    ): ListenableFuture<SessionResult> = controller.sendCustomCommand(
        ExternalPlaybackSessionContract.setExternalPlaybackRequestCommand,
        ExternalPlaybackSessionContract.setupArgs(setupId, leaseId),
    )

    suspend fun awaitExternalPlaybackStart(
        controller: MediaController,
        leaseId: ExternalPlaybackLeaseId,
        timeoutMillis: Long,
    ): ExternalPlaybackStartResult = awaitExternalPlaybackStart(
        controller = controller,
        leaseId = leaseId,
        setupId = PlaybackSetupId.create(),
        timeoutMillis = timeoutMillis,
    )

    suspend fun awaitExternalPlaybackStart(
        controller: MediaController,
        leaseId: ExternalPlaybackLeaseId,
        setupId: PlaybackSetupId,
        timeoutMillis: Long,
    ): ExternalPlaybackStartResult {
        return try {
            val result = awaitPlaybackSetup(
                future = sendExternalPlaybackRequest(
                    controller = controller,
                    setupId = setupId,
                    leaseId = leaseId,
                ),
                timeoutMillis = timeoutMillis,
                cancelSetup = { postCancel(controller, setupId) },
            )
            ExternalPlaybackSessionContract.parseResult(result)
                ?: ExternalPlaybackStartResult.Rejected(
                    reason = if (
                        result.resultCode == SessionResult.RESULT_ERROR_INVALID_STATE
                    ) {
                        ExternalPlaybackStartFailure.LeaseUnavailable
                    } else {
                        ExternalPlaybackStartFailure.PlaybackFailed
                    },
                )
        } catch (timeout: TimeoutCancellationException) {
            throw MediaControllerOperationException(commandFailureFor(timeout))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw MediaControllerOperationException(commandFailureFor(error))
        }
    }

    fun cancelSetup(
        controller: MediaController,
        setupId: PlaybackSetupId,
    ) {
        postCancel(controller, setupId)
    }

    override fun close() {
        connections.close()
        runOnApplicationLooper {
            observedController = null
            mutablePlaybackSessionState.value = PlaybackSessionState.Idle
        }
    }

    private fun observePlaybackSession(controller: MediaController) {
        runOnApplicationLooper {
            if (observedController !== controller) {
                observedController?.removeListener(playerListener)
                observedController = controller
                controller.addListener(playerListener)
            }
            publishPlaybackSessionState(controller)
        }
    }

    private fun publishPlaybackSessionState(player: Player) {
        val rawPhase = when (player.playbackState) {
            Player.STATE_BUFFERING -> PlaybackSessionPhase.BUFFERING
            Player.STATE_READY -> PlaybackSessionPhase.READY
            Player.STATE_ENDED -> PlaybackSessionPhase.ENDED
            else -> PlaybackSessionPhase.IDLE
        }
        val mediaId = player.currentMediaItem?.mediaId?.takeIf(String::isNotBlank)
        val channelId = mediaId?.takeUnless {
            it.startsWith(PlaybackSessionRequest.EXTERNAL_MEDIA_ID_PREFIX)
        }
        val phase = if (rawPhase == PlaybackSessionPhase.IDLE || channelId == null) {
            PlaybackSessionPhase.IDLE
        } else {
            rawPhase
        }

        mutablePlaybackSessionState.value = PlaybackSessionState(
            channelId = channelId.takeIf { phase != PlaybackSessionPhase.IDLE },
            phase = phase,
            isPlaying = phase == PlaybackSessionPhase.READY && player.isPlaying,
        )
    }

    private fun postCancel(
        controller: MediaController,
        setupId: PlaybackSetupId,
    ) {
        runOnApplicationLooper {
            runCatching {
                controller.sendCustomCommand(
                    MuxTvPlaybackSessionContract.cancelPlaybackSetupCommand,
                    MuxTvPlaybackSessionContract.cancelArgs(setupId),
                )
            }
        }
    }

    private fun releasePending(future: ListenableFuture<MediaController>) {
        runOnApplicationLooper {
            MediaController.releaseFuture(future)
        }
    }

    private fun releaseConnected(controller: MediaController) {
        runOnApplicationLooper {
            if (observedController === controller) {
                controller.removeListener(playerListener)
                observedController = null
                mutablePlaybackSessionState.value = PlaybackSessionState.Idle
            }
            controller.release()
        }
    }

    private fun runOnApplicationLooper(action: () -> Unit) {
        if (Looper.myLooper() == applicationHandler.looper) {
            action()
        } else {
            applicationHandler.post(action)
        }
    }
}
