package app.muxtv.player.media3

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.muxtv.catalog.PlaybackCandidateIdentity
import app.muxtv.catalog.PlaybackCandidateResolver
import app.muxtv.catalog.MAX_PLAYBACK_CANDIDATES
import app.muxtv.network.MuxTvHttpClients
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@AndroidEntryPoint
@AndroidXOptIn(UnstableApi::class)
class MuxTvPlaybackService : MediaSessionService() {
    @Inject
    lateinit var httpClients: MuxTvHttpClients

    @Inject
    lateinit var firstFrameRecorder: PlaybackFirstFrameRecorder

    @Inject
    lateinit var playbackCandidateResolver: PlaybackCandidateResolver

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSourceFactory: PlaybackMediaSourceFactory
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var firstFrameTracker: PlaybackFirstFrameTracker
    private lateinit var recovery: PlaybackRecoveryOrchestrator

    private var activeSetupId: PlaybackSetupId? = null
    private var activeRequest: PlaybackStartRequest? = null
    private var activeCandidate: PlaybackCandidateIdentity? = null
    private var activeGeneration: Long? = null
    private val callbackGate = PlaybackCallbackGate()
    private var activePlayerListener: Player.Listener? = null
    private var activeFuture: SettableFuture<SessionResult>? = null
    private var activeJob: Job? = null
    private var deadlineJob: Job? = null
    private val cancelledSetupIds = linkedSetOf<PlaybackSetupId>()

    override fun onCreate() {
        super.onCreate()
        mediaSourceFactory = PlaybackMediaSourceFactory(this, httpClients)
        firstFrameTracker = PlaybackFirstFrameTracker(
            elapsedRealtimeNanos = SystemClock::elapsedRealtimeNanos,
            publish = firstFrameRecorder::record,
        )
        recovery = PlaybackRecoveryOrchestrator(
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            maxAttempts = MAX_ATTEMPTS,
            maxRecoveryDurationMillis = MAX_RECOVERY_DURATION_MILLIS,
        )
        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSession.Builder(this, player)
            .setId(SESSION_ID)
            .setCallback(SessionCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (::mediaSession.isInitialized) mediaSession else null

    override fun onDestroy() {
        cancelActiveSetup(completeCancelled = true)
        serviceScope.cancel()
        removeActivePlayerListener()
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    private fun startSetup(command: PlaybackSetupCommand): ListenableFuture<SessionResult> {
        if (command.id in cancelledSetupIds) {
            return Futures.immediateFuture(MuxTvPlaybackSessionContract.cancelled())
        }
        cancelActiveSetup(completeCancelled = true)
        val future = SettableFuture.create<SessionResult>()
        activeSetupId = command.id
        activeRequest = command.request
        activeFuture = future
        val deadlineAtMillis = SystemClock.elapsedRealtime() + MAX_RECOVERY_DURATION_MILLIS
        deadlineJob = serviceScope.launch {
            delay((deadlineAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (activeSetupId == command.id) {
                recovery.cancel()
                activeJob?.cancel()
                activeJob = null
                clearInstalled()
                complete(
                    PlaybackStartResult.Rejected(
                        PlaybackStartFailure.RecoveryExhausted,
                    ),
                )
            }
        }
        activeJob = serviceScope.launch {
            val candidates = try {
                playbackCandidateResolver.getCandidates(
                    profileId = command.request.profileId,
                    channelId = command.request.channelId,
                    preferredVariantId = command.request.preferredVariantId,
                    limit = MAX_ATTEMPTS,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (activeSetupId != command.id) return@launch
            processAction(
                ownerSetupId = command.id,
                initial = recovery.start(
                    request = command.request,
                    candidates = candidates,
                    deadlineAtMillis = deadlineAtMillis,
                ),
            )
        }
        return future
    }

    private fun processCallback(
        token: PlaybackAttemptToken,
        action: PlaybackRecoveryAction,
    ) {
        if (action == PlaybackRecoveryAction.Ignored ||
            !token.matches(activeSetupId, activeGeneration, activeCandidate) ||
            !callbackGate.consume(token)
        ) return
        activeJob?.cancel()
        activeJob = serviceScope.launch { processAction(token.setupId, action) }
    }

    private suspend fun processAction(
        ownerSetupId: PlaybackSetupId,
        initial: PlaybackRecoveryAction,
    ) {
        var action = initial
        while (true) {
            if (activeSetupId != ownerSetupId) return
            when (action) {
                is PlaybackRecoveryAction.ResolveCandidate -> {
                    clearInstalled()
                    activeGeneration = action.generation
                    activeCandidate = action.candidate
                    val request = activeRequest ?: return
                    val resolution = try {
                        playbackCandidateResolver.resolveCandidate(
                            profileId = request.profileId,
                            candidate = action.candidate,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    if (activeSetupId != ownerSetupId ||
                        activeGeneration != action.generation ||
                        activeCandidate != action.candidate
                    ) return
                    action = recovery.onCandidateResolved(
                        generation = action.generation,
                        candidate = action.candidate,
                        resolution = resolution,
                    )
                }

                is PlaybackRecoveryAction.Install -> {
                    if (activeGeneration != action.generation ||
                        activeCandidate != action.candidate
                    ) return
                    activeGeneration = action.generation
                    install(ownerSetupId, action)
                    return
                }

                is PlaybackRecoveryAction.ApprovalRequired -> {
                    complete(
                        PlaybackStartResult.InsecureHttpApprovalRequired(
                            displayOrigin = action.displayOrigin,
                            variantId = action.candidate.variantId,
                        ),
                    )
                    return
                }

                is PlaybackRecoveryAction.Succeeded -> {
                    complete(PlaybackStartResult.Started)
                    return
                }

                is PlaybackRecoveryAction.Failed -> {
                    clearInstalled()
                    val failure = when (action.failure) {
                        PlaybackRecoveryFailure.NoCandidates ->
                            PlaybackStartFailure.ChannelUnavailable
                        PlaybackRecoveryFailure.AccessUnavailable ->
                            PlaybackStartFailure.AccessUnavailable
                        PlaybackRecoveryFailure.BudgetExhausted ->
                            PlaybackStartFailure.RecoveryExhausted
                    }
                    complete(PlaybackStartResult.Rejected(failure))
                    return
                }

                PlaybackRecoveryAction.Cancelled,
                PlaybackRecoveryAction.Ignored,
                -> return
            }
        }
    }

    private fun install(
        setupId: PlaybackSetupId,
        action: PlaybackRecoveryAction.Install,
    ) {
        if (activeSetupId != setupId) return
        val request = activeRequest ?: return
        val resolved = action.request
        val sessionRequest = PlaybackSessionRequest(
            profileId = request.profileId,
            mediaId = resolved.channelId,
            variantId = resolved.variantId,
            locator = resolved.locator,
            requestHeaders = resolved.requestHeaders,
            insecureHttpApproved = resolved.insecureHttpApproved,
        )
        val token = PlaybackAttemptToken(setupId, action.generation, action.candidate)
        try {
            clearInstalled()
            callbackGate.activate(token)
            activePlayerListener = createPlayerListener(token).also(player::addListener)
            player.setMediaSource(mediaSourceFactory.create(sessionRequest))
            firstFrameTracker.activate(setupId, request.profileId, request.channelId)
            player.prepare()
            player.play()
        } catch (_: Exception) {
            if (callbackGate.isCurrent(token)) {
                processCallback(
                    token,
                    recovery.onPlayerError(action.generation, action.candidate),
                )
            }
        }
    }

    private fun createPlayerListener(token: PlaybackAttemptToken): Player.Listener =
        object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!token.matches(activeSetupId, activeGeneration, activeCandidate) ||
                    !callbackGate.isCurrent(token)
                ) return
                firstFrameTracker.onRenderedFirstFrame(
                    setupId = token.setupId,
                    currentMediaId = player.currentMediaItem?.mediaId,
                ) ?: return
                processCallback(
                    token,
                    recovery.onRenderedFirstFrame(token.generation, token.candidate),
                )
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!token.matches(activeSetupId, activeGeneration, activeCandidate) ||
                    !callbackGate.isCurrent(token)
                ) return
                processCallback(
                    token,
                    recovery.onPlayerError(token.generation, token.candidate),
                )
            }
        }

    private fun complete(result: PlaybackStartResult) {
        deadlineJob?.cancel()
        deadlineJob = null
        activeFuture?.set(MuxTvPlaybackSessionContract.result(result))
        activeFuture = null
        activeJob = null
        if (result !is PlaybackStartResult.Started) {
            activeSetupId = null
            activeRequest = null
            activeCandidate = null
            activeGeneration = null
            callbackGate.clear()
        }
    }

    private fun cancelActiveSetup(completeCancelled: Boolean) {
        recovery.cancel()
        activeJob?.cancel()
        activeJob = null
        deadlineJob?.cancel()
        deadlineJob = null
        if (completeCancelled) activeFuture?.set(MuxTvPlaybackSessionContract.cancelled())
        activeFuture = null
        activeSetupId = null
        activeRequest = null
        activeCandidate = null
        activeGeneration = null
        callbackGate.clear()
        if (::player.isInitialized) clearInstalled()
    }

    private fun clearInstalled() {
        removeActivePlayerListener()
        callbackGate.clear()
        firstFrameTracker.clearActive()
        player.stop()
        player.clearMediaItems()
    }

    private fun removeActivePlayerListener() {
        activePlayerListener?.let(player::removeListener)
        activePlayerListener = null
    }

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val base = MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            if (controller.packageName != packageName) return base
            return MediaSession.ConnectionResult.accept(
                base.availableSessionCommands.buildUpon()
                    .add(MuxTvPlaybackSessionContract.setPlaybackRequestCommand)
                    .add(MuxTvPlaybackSessionContract.cancelPlaybackSetupCommand)
                    .build(),
                base.availablePlayerCommands,
            )
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (controller.packageName != packageName) {
                return Futures.immediateFuture(MuxTvPlaybackSessionContract.permissionDenied())
            }
            return when (customCommand.customAction) {
                MuxTvPlaybackSessionContract.ACTION_SET_PLAYBACK_REQUEST -> {
                    val command = MuxTvPlaybackSessionContract.parseSetupArgs(args)
                        ?: return Futures.immediateFuture(MuxTvPlaybackSessionContract.badValue())
                    startSetup(command)
                }
                MuxTvPlaybackSessionContract.ACTION_CANCEL_PLAYBACK_SETUP -> {
                    val id = MuxTvPlaybackSessionContract.parseCancelArgs(args)
                        ?: return Futures.immediateFuture(MuxTvPlaybackSessionContract.badValue())
                    cancelledSetupIds.add(id)
                    while (cancelledSetupIds.size > MAX_CANCELLED_SETUP_IDS) {
                        cancelledSetupIds.remove(cancelledSetupIds.first())
                    }
                    if (id == activeSetupId) cancelActiveSetup(completeCancelled = true)
                    Futures.immediateFuture(MuxTvPlaybackSessionContract.result(PlaybackStartResult.Started))
                }
                else -> Futures.immediateFuture(MuxTvPlaybackSessionContract.notSupported())
            }
        }
    }

    private companion object {
        const val SESSION_ID = "muxtv-main-playback"
        const val MAX_ATTEMPTS = MAX_PLAYBACK_CANDIDATES
        const val MAX_RECOVERY_DURATION_MILLIS = 20_000L
        const val MAX_CANCELLED_SETUP_IDS = 64
    }
}
