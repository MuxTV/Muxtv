package app.muxtv.player.media3

import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.C
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
import app.muxtv.common.tracing.MuxTvTrace
import app.muxtv.common.tracing.MuxTvTraceSection
import app.muxtv.network.MuxTvHttpClients
import app.muxtv.player.ExternalPlaybackClaimResult
import app.muxtv.player.ExternalPlaybackDescriptor
import app.muxtv.player.ExternalPlaybackLeaseRegistry
import app.muxtv.player.ExternalPlaybackStartFailure
import app.muxtv.player.ExternalPlaybackStartResult
import app.muxtv.player.PlaybackFailureCategory
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import app.muxtv.player.PlaybackObservationRecorder
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import java.net.URI
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

    @Inject
    lateinit var playbackLocalNetworkAccessGate: PlaybackLocalNetworkAccessGate

    @Inject
    lateinit var playbackObservationRecorder: PlaybackObservationRecorder

    @Inject
    lateinit var externalLeaseRegistry: ExternalPlaybackLeaseRegistry

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
    private var activeSeekGeneration: Long? = null
    private var seekGenerationCounter = 0L
    private var activeExternal: ActiveExternalSetup? = null
    private var activeAttemptNumber = 0
    private var lastFailure: Media3Failure? = null
    private val callbackGate = PlaybackCallbackGate()
    private var activePlayerListener: Player.Listener? = null
    private var activeFuture: SettableFuture<SessionResult>? = null
    private var activeJob: Job? = null
    private var deadlineJob: Job? = null
    private val cancelledSetupIds = linkedSetOf<PlaybackSetupId>()
    private val mediaSeekController = PlaybackSeekController(
        scope = serviceScope,
        onApplySeek = { generation, targetMs ->
            if (activeSeekGeneration == generation) {
                player.seekTo(targetMs)
            }
        },
    )
    private val seekConfirmationListener = object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            val generation = newPosition.mediaItem?.playbackSeekToken()?.generation ?: return
            mediaSeekController.onSeekConfirmed(generation)
        }
    }

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
        player.addListener(seekConfirmationListener)
        val sessionPlayer = MuxTvSessionPlayer(
            player = player,
            onSeekIntent = ::handleSessionSeekIntent,
        )
        mediaSession = MediaSession.Builder(this, sessionPlayer)
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
        if (::player.isInitialized) {
            player.removeListener(seekConfirmationListener)
            player.release()
        }
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
        activeAttemptNumber = 0
        lastFailure = null
        val deadlineAtMillis = SystemClock.elapsedRealtime() + MAX_RECOVERY_DURATION_MILLIS
        deadlineJob = serviceScope.launch {
            delay((deadlineAtMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            if (activeSetupId == command.id) {
                recordRecoveryFailure(PlaybackFailureCategory.TIMEOUT)
                recovery.cancel()
                activeJob?.cancel()
                activeJob = null
                clearInstalled()
                complete(
                    PlaybackStartResult.Rejected(
                        reason = PlaybackStartFailure.RecoveryExhausted,
                        observationAvailable = hasPlaybackAttemptEvidence(
                            failure = PlaybackRecoveryFailure.DeadlineExceeded,
                            attemptNumber = activeAttemptNumber,
                        ),
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

    private fun startExternalSetup(
        command: ExternalPlaybackSetupCommand,
    ): ListenableFuture<SessionResult> {
        if (command.id in cancelledSetupIds) {
            return Futures.immediateFuture(ExternalPlaybackSessionContract.cancelled())
        }
        cancelActiveSetup(completeCancelled = true)
        val future = SettableFuture.create<SessionResult>()
        activeSetupId = command.id
        activeFuture = future
        activeAttemptNumber = 0
        lastFailure = null
        val claim = externalLeaseRegistry.claim(
            leaseId = command.leaseId,
            nowEpochMillis = System.currentTimeMillis(),
        )
        if (claim !is ExternalPlaybackClaimResult.Claimed) {
            completeExternal(
                ExternalPlaybackStartResult.Rejected(
                    ExternalPlaybackStartFailure.LeaseUnavailable,
                ),
            )
            return future
        }
        val descriptor = claim.descriptor
        if (!isValidExternalDescriptor(descriptor)) {
            completeExternal(
                ExternalPlaybackStartResult.Rejected(
                    ExternalPlaybackStartFailure.InvalidDescriptor,
                ),
            )
            return future
        }
        if (descriptor.isCleartext && !descriptor.cleartextApproved) {
            completeExternal(
                ExternalPlaybackStartResult.Rejected(
                    ExternalPlaybackStartFailure.CleartextNotApproved,
                ),
            )
            return future
        }
        activeExternal = ActiveExternalSetup(
            setupId = command.id,
            sessionId = claim.sessionId,
        )
        activeAttemptNumber = 1
        recordObservation(
            kind = PlaybackObservationKind.EXTERNAL_SETUP_STARTED,
            attemptLimit = EXTERNAL_ATTEMPT_LIMIT,
        )
        installExternal(
            setupId = command.id,
            sessionId = claim.sessionId,
            descriptor = descriptor,
        )
        return future
    }

    private fun isValidExternalDescriptor(descriptor: ExternalPlaybackDescriptor): Boolean {
        val uri = runCatching { URI(descriptor.locator) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        if (uri.userInfo != null) return false
        if (uri.host.isNullOrBlank()) return false
        return true
    }

    private fun installExternal(
        setupId: PlaybackSetupId,
        sessionId: String,
        descriptor: ExternalPlaybackDescriptor,
    ) {
        val mediaId = PlaybackSessionRequest.EXTERNAL_MEDIA_ID_PREFIX + sessionId
        val sessionRequest = PlaybackSessionRequest(
            profileId = EXTERNAL_PROFILE_ID,
            mediaId = mediaId,
            variantId = EXTERNAL_VARIANT_ID,
            locator = descriptor.locator,
            displayName = descriptor.displayTitle,
            insecureHttpApproved = descriptor.cleartextApproved,
            mimeType = descriptor.mimeType,
        )
        try {
            clearInstalled()
            val seekGeneration = nextSeekGeneration()
            activeSeekGeneration = seekGeneration
            activePlayerListener = object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    if (activeExternal?.setupId != setupId) return
                    if (player.currentMediaItem?.mediaId != mediaId) return
                    MuxTvTrace.global.section(MuxTvTraceSection.FIRST_FRAME) {
                        activeAttemptNumber = 1
                        recordObservation(
                            kind = PlaybackObservationKind.EXTERNAL_FIRST_FRAME,
                            attemptLimit = EXTERNAL_ATTEMPT_LIMIT,
                        )
                        completeExternal(ExternalPlaybackStartResult.Started)
                        removeActivePlayerListener()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (activeExternal?.setupId != setupId) return
                    activeAttemptNumber = 1
                    recordExternalFailure(Media3FailureClassifier.classify(error))
                    completeExternal(
                        ExternalPlaybackStartResult.Rejected(
                            reason = ExternalPlaybackStartFailure.PlaybackFailed,
                            observationAvailable = true,
                        ),
                    )
                }
            }.also(player::addListener)
            MuxTvTrace.global.section(MuxTvTraceSection.PLAYER_PREPARE) {
                player.setMediaSource(mediaSourceFactory.create(sessionRequest, seekGeneration))
                player.prepare()
            }
            player.play()
        } catch (_: Exception) {
            if (activeExternal?.setupId == setupId) {
                recordExternalFailure(
                    Media3Failure(
                        category = PlaybackFailureCategory.UNKNOWN,
                        media3ErrorCode = PlaybackException.ERROR_CODE_UNSPECIFIED,
                    ),
                )
                completeExternal(
                    ExternalPlaybackStartResult.Rejected(
                        reason = ExternalPlaybackStartFailure.PlaybackFailed,
                        observationAvailable = true,
                    ),
                )
            }
        }
    }

    private fun completeExternal(result: ExternalPlaybackStartResult) {
        deadlineJob?.cancel()
        deadlineJob = null
        activeFuture?.set(ExternalPlaybackSessionContract.result(result))
        activeFuture = null
        activeJob = null
        if (result !is ExternalPlaybackStartResult.Started) {
            activeSetupId = null
            activeExternal = null
            activeAttemptNumber = 0
            lastFailure = null
            callbackGate.clear()
            clearInstalled()
        }
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
                    activeAttemptNumber = action.attempt + 1
                    recordObservation(PlaybackObservationKind.ATTEMPT_STARTED)
                    val request = activeRequest ?: return
                    val resolution = try {
                        resolvePlaybackCandidateForRecovery(
                            resolver = playbackCandidateResolver,
                            request = request,
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
                    val localNetworkResult = PlaybackLocalNetworkAccessDecision.requiredResult(
                        candidate = action.candidate,
                        resolution = resolution,
                        gate = playbackLocalNetworkAccessGate,
                    )
                    if (localNetworkResult != null) {
                        recovery.cancel()
                        complete(localNetworkResult)
                        return
                    }
                    val resolutionAccepted = resolution.matches(action.candidate)
                    action = recovery.onCandidateResolved(
                        generation = action.generation,
                        candidate = action.candidate,
                        resolution = resolution,
                    )
                    if (!resolutionAccepted &&
                        action != PlaybackRecoveryAction.Ignored
                    ) {
                        recordAttemptFailure(
                            Media3Failure(
                                category = PlaybackFailureCategory.CREDENTIAL_ACCESS,
                                media3ErrorCode = PlaybackException.ERROR_CODE_UNSPECIFIED,
                            ),
                        )
                    }
                }

                is PlaybackRecoveryAction.Install -> {
                    if (activeGeneration != action.generation ||
                        activeCandidate != action.candidate
                    ) return
                    activeGeneration = action.generation
                    activeAttemptNumber = action.attempt + 1
                    install(ownerSetupId, action)
                    return
                }

                is PlaybackRecoveryAction.ApprovalRequired -> {
                    activeAttemptNumber = action.attempt + 1
                    recordObservation(PlaybackObservationKind.APPROVAL_REQUIRED)
                    complete(
                        PlaybackStartResult.InsecureHttpApprovalRequired(
                            displayOrigin = action.displayOrigin,
                            variantId = action.candidate.variantId,
                        ),
                    )
                    return
                }

                is PlaybackRecoveryAction.Succeeded -> {
                    activeAttemptNumber = action.attempt + 1
                    complete(PlaybackStartResult.Started)
                    return
                }

                is PlaybackRecoveryAction.Failed -> {
                    activeAttemptNumber = if (
                        action.failure == PlaybackRecoveryFailure.NoCandidates
                    ) 0 else action.attempt + 1
                    val terminalCategory = when (action.failure) {
                        PlaybackRecoveryFailure.NoCandidates,
                        PlaybackRecoveryFailure.AccessUnavailable,
                        -> lastFailure?.category ?: PlaybackFailureCategory.CREDENTIAL_ACCESS
                        PlaybackRecoveryFailure.CandidatesExhausted ->
                            lastFailure?.category ?: PlaybackFailureCategory.UNKNOWN
                        PlaybackRecoveryFailure.DeadlineExceeded ->
                            PlaybackFailureCategory.TIMEOUT
                    }
                    recordRecoveryFailure(terminalCategory)
                    clearInstalled()
                    val failure = when (action.failure) {
                        PlaybackRecoveryFailure.NoCandidates ->
                            PlaybackStartFailure.ChannelUnavailable
                        PlaybackRecoveryFailure.AccessUnavailable ->
                            PlaybackStartFailure.AccessUnavailable
                        PlaybackRecoveryFailure.CandidatesExhausted,
                        PlaybackRecoveryFailure.DeadlineExceeded,
                        -> PlaybackStartFailure.RecoveryExhausted
                    }
                    complete(
                        PlaybackStartResult.Rejected(
                            reason = failure,
                            observationAvailable = hasPlaybackAttemptEvidence(
                                failure = action.failure,
                                attemptNumber = activeAttemptNumber,
                            ),
                        ),
                    )
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
        val token = PlaybackAttemptToken(
            setupId = setupId,
            generation = action.generation,
            candidate = action.candidate,
            attempt = action.attempt,
        )
        try {
            clearInstalled()
            val seekGeneration = nextSeekGeneration()
            activeSeekGeneration = seekGeneration
            activeAttemptNumber = action.attempt + 1
            callbackGate.activate(token)
            activePlayerListener = createPlayerListener(token).also(player::addListener)
            MuxTvTrace.global.section(MuxTvTraceSection.PLAYER_PREPARE) {
                player.setMediaSource(mediaSourceFactory.create(sessionRequest, seekGeneration))
                firstFrameTracker.activate(setupId, request.profileId, request.channelId)
                player.prepare()
            }
            player.play()
        } catch (_: Exception) {
            if (callbackGate.isCurrent(token)) {
                recordAttemptFailure(
                    Media3Failure(
                        category = PlaybackFailureCategory.UNKNOWN,
                        media3ErrorCode = PlaybackException.ERROR_CODE_UNSPECIFIED,
                    ),
                )
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
                MuxTvTrace.global.section(MuxTvTraceSection.FIRST_FRAME) {
                    val action = recovery.onRenderedFirstFrame(token.generation, token.candidate)
                    if (action is PlaybackRecoveryAction.Succeeded) {
                        activeAttemptNumber = token.attempt + 1
                        recordObservation(PlaybackObservationKind.RECOVERY_SUCCEEDED)
                    }
                    processCallback(
                        token,
                        action,
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!token.matches(activeSetupId, activeGeneration, activeCandidate) ||
                    !callbackGate.isCurrent(token)
                ) return
                activeAttemptNumber = token.attempt + 1
                recordAttemptFailure(Media3FailureClassifier.classify(error))
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
            activeAttemptNumber = 0
            lastFailure = null
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
        activeExternal = null
        activeAttemptNumber = 0
        lastFailure = null
        callbackGate.clear()
        if (::player.isInitialized) clearInstalled()
    }

    private fun clearInstalled() {
        removeActivePlayerListener()
        callbackGate.clear()
        firstFrameTracker.clearActive()
        mediaSeekController.reset()
        activeSeekGeneration = null
        player.stop()
        player.clearMediaItems()
    }

    private fun removeActivePlayerListener() {
        activePlayerListener?.let(player::removeListener)
        activePlayerListener = null
    }

    private fun recordAttemptFailure(
        failure: Media3Failure,
        attemptLimit: Int = MAX_ATTEMPTS,
    ) {
        lastFailure = failure
        recordObservation(
            kind = PlaybackObservationKind.ATTEMPT_FAILED,
            failure = failure,
            attemptLimit = attemptLimit,
        )
    }

    private fun recordExternalFailure(failure: Media3Failure) {
        lastFailure = failure
        recordObservation(
            kind = PlaybackObservationKind.EXTERNAL_PLAYBACK_FAILED,
            failure = failure,
            attemptLimit = EXTERNAL_ATTEMPT_LIMIT,
        )
    }

    private fun recordRecoveryFailure(category: PlaybackFailureCategory) {
        val detail = lastFailure?.takeIf { it.category == category }
        recordObservation(
            kind = PlaybackObservationKind.RECOVERY_FAILED,
            failure = Media3Failure(
                category = category,
                media3ErrorCode = detail?.media3ErrorCode
                    ?: PlaybackException.ERROR_CODE_UNSPECIFIED,
                httpStatusCode = detail?.httpStatusCode,
            ),
        )
    }
    private fun recordObservation(
        kind: PlaybackObservationKind,
        failure: Media3Failure? = null,
        attemptLimit: Int = MAX_ATTEMPTS,
    ) {
        try {
            playbackObservationRecorder.record(
                PlaybackObservation(
                    kind = kind,
                    failureCategory = failure?.category,
                    attemptNumber = activeAttemptNumber,
                    attemptLimit = attemptLimit,
                    timestampEpochMillis = System.currentTimeMillis(),
                    httpStatusCode = failure?.httpStatusCode,
                    media3ErrorCode = failure?.media3ErrorCode,
                ),
            )
        } catch (_: Exception) {
            // Diagnostics must never affect playback state or recovery ownership.
        }
    }

    private fun nextSeekGeneration(): Long {
        seekGenerationCounter = if (seekGenerationCounter == Long.MAX_VALUE) {
            1L
        } else {
            seekGenerationCounter + 1L
        }
        return seekGenerationCounter
    }

    private fun currentSeekToken(): PlaybackSeekToken? {
        val mediaId = player.currentMediaItem?.mediaId ?: return null
        val generation = activeSeekGeneration ?: return null
        return runCatching { PlaybackSeekToken(mediaId, generation) }.getOrNull()
    }

    private fun handleSeekRequest(request: PlaybackSeekRequest): PlaybackSeekResult {
        if (request.token != currentSeekToken()) {
            return PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.STALE_PLAYBACK)
        }
        if (!player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) {
            return PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.COMMAND_UNAVAILABLE)
        }
        if (player.isCurrentMediaItemLive) {
            return PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.LIVE_CONTENT)
        }
        val durationMs = player.duration
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) {
            return PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.UNKNOWN_DURATION)
        }
        val currentPositionMs = player.currentPosition
        if (currentPositionMs < 0L) {
            return PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.INVALID_POSITION)
        }

        val accepted = when (request) {
            is PlaybackSeekRequest.Relative -> mediaSeekController.onDirectionRequested(
                generation = request.token.generation,
                direction = request.direction,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
            )
            is PlaybackSeekRequest.Absolute -> mediaSeekController.onTargetRequested(
                generation = request.token.generation,
                targetMs = request.targetMs,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
            )
        }
        if (!accepted) {
            return PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.CONTROLLER_REJECTED)
        }
        val pending = mediaSeekController.state.value as? SeekControllerState.Pending
            ?: return PlaybackSeekResult.Rejected(PlaybackSeekRejectReason.CONTROLLER_REJECTED)
        return PlaybackSeekResult.Accepted(
            targetMs = pending.targetMs,
            direction = pending.direction,
        )
    }

    private fun handleSessionSeekIntent(intent: MuxTvSessionSeekIntent) {
        val token = currentSeekToken() ?: return
        val request = when (intent) {
            is MuxTvSessionSeekIntent.Relative -> PlaybackSeekRequest.Relative(
                token = token,
                direction = intent.direction,
            )
            is MuxTvSessionSeekIntent.Absolute -> PlaybackSeekRequest.Absolute(
                token = token,
                targetMs = intent.targetMs,
            )
        }
        handleSeekRequest(request)
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
                    .add(MuxTvPlaybackSessionContract.seekCommand)
                    .add(ExternalPlaybackSessionContract.setExternalPlaybackRequestCommand)
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
                MuxTvPlaybackSessionContract.ACTION_REQUEST_SEEK -> {
                    val request = MuxTvPlaybackSessionContract.parseSeekArgs(args)
                        ?: return Futures.immediateFuture(MuxTvPlaybackSessionContract.badValue())
                    Futures.immediateFuture(
                        MuxTvPlaybackSessionContract.seekSessionResult(handleSeekRequest(request)),
                    )
                }
                ExternalPlaybackSessionContract.ACTION_SET_EXTERNAL_PLAYBACK_REQUEST -> {
                    val command = ExternalPlaybackSessionContract.parseSetupArgs(args)
                        ?: return Futures.immediateFuture(
                            ExternalPlaybackSessionContract.badValue(),
                        )
                    startExternalSetup(command)
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
        const val EXTERNAL_ATTEMPT_LIMIT = 1
        const val EXTERNAL_PROFILE_ID = "external"
        const val EXTERNAL_VARIANT_ID = "external"
    }

    private class ActiveExternalSetup(
        val setupId: PlaybackSetupId,
        val sessionId: String,
    )
}

private fun app.muxtv.catalog.PlaybackVariantResolution?.matches(
    candidate: PlaybackCandidateIdentity,
): Boolean = when (this) {
    is app.muxtv.catalog.PlaybackVariantResolution.Ready ->
        request.channelId == candidate.channelId && request.variantId == candidate.variantId
    is app.muxtv.catalog.PlaybackVariantResolution.InsecureTransportApprovalRequired ->
        channelId == candidate.channelId && variantId == candidate.variantId
    is app.muxtv.catalog.PlaybackVariantResolution.AccessUnavailable,
    null,
    -> false
}
