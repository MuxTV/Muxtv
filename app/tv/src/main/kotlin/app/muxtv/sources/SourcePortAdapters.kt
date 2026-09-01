package app.muxtv.sources

import app.muxtv.catalog.PlaybackAccessMutationResult
import app.muxtv.catalog.PlaybackAccessPolicyResolver
import app.muxtv.catalog.SourceActivationFailure
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceManagement
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePlaybackApprovalResetResult
import app.muxtv.catalog.SourcePreparationFailure
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.catalog.SourceRefreshOverview
import app.muxtv.catalog.SourceRefreshPolicy
import app.muxtv.catalog.SourceRefreshRunState
import app.muxtv.catalog.SourceRefreshStatus
import app.muxtv.catalog.onboarding.DurablePreparedSource
import app.muxtv.catalog.onboarding.DurablePreparationRegistrationResult
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.catalog.refresh.SourceAccessKind
import app.muxtv.catalog.refresh.SourceAccessReference
import app.muxtv.catalog.refresh.XtreamSourceLifecycle
import app.muxtv.catalog.refresh.XtreamSourcePreparationInput
import app.muxtv.catalog.refresh.XtreamSourcePreparationResult
import app.muxtv.catalog.refresh.XtreamSourcePreparer
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.credentials.CredentialRemoveResult
import app.muxtv.database.SourceRefreshOverview as DatabaseSourceRefreshOverview
import app.muxtv.database.SourceRefreshPolicy as DatabaseSourceRefreshPolicy
import app.muxtv.database.SourceRefreshRunState as DatabaseSourceRefreshRunState
import app.muxtv.database.SourceRefreshStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AppSourceManagement(
    private val refreshStore: SourceRefreshStore,
    private val refreshScheduler: SourceRefreshScheduler,
    private val playbackAccessPolicyResolver: PlaybackAccessPolicyResolver,
) : SourceManagement {
    override fun observeOverviews(): Flow<List<SourceRefreshOverview>> =
        refreshStore.observeOverviews().map { overviews -> overviews.map(DatabaseSourceRefreshOverview::toApi) }

    override fun refreshNow(sourceId: String) {
        refreshScheduler.refreshNow(sourceId)
    }

    override suspend fun updatePolicy(policy: SourceRefreshPolicy) {
        refreshScheduler.updatePolicy(policy.toDatabase())
    }

    override suspend fun removePolicy(sourceId: String) {
        refreshScheduler.removePolicy(sourceId)
    }

    override suspend fun revokePlaybackApprovals(
        sourceId: String,
    ): SourcePlaybackApprovalResetResult {
        val credentialRef = refreshStore.getTarget(sourceId)?.credentialRef
            ?: return SourcePlaybackApprovalResetResult.SourceNotFound
        return when (playbackAccessPolicyResolver.revokeAll(credentialRef)) {
            PlaybackAccessMutationResult.Applied -> SourcePlaybackApprovalResetResult.Reset
            PlaybackAccessMutationResult.Unchanged -> SourcePlaybackApprovalResetResult.Unchanged
            PlaybackAccessMutationResult.NotFound -> SourcePlaybackApprovalResetResult.SourceNotFound
            PlaybackAccessMutationResult.Corrupted,
            PlaybackAccessMutationResult.Unavailable,
            PlaybackAccessMutationResult.InvalidLocator,
            PlaybackAccessMutationResult.CapacityExceeded,
            -> SourcePlaybackApprovalResetResult.AccessUnavailable
        }
    }
}

class AppSourceOnboarding(
    private val delegate: DurableRemoteSourceOnboarding,
    private val xtreamPreparer: XtreamSourcePreparer? = null,
    private val xtreamLifecycle: XtreamSourceLifecycle? = null,
    private val localNetworkAccessRequired: (String) -> Boolean = { false },
) : SourceOnboarding {
    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult {
        if (localNetworkAccessRequired(locator)) {
            return SourcePreparationResult.LocalNetworkAccessRequired
        }
        return delegate.prepare(
            RemoteSourceOnboardingInput(
                locator = locator,
                insecureHttpApproved = insecureHttpApproved,
            ),
        ).toApi()
    }

    override suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult {
        val locator = when (request) {
            is SourcePreparationRequest.M3u -> request.locator
            is SourcePreparationRequest.Xtream -> request.endpoint
        }
        if (localNetworkAccessRequired(locator)) {
            return SourcePreparationResult.LocalNetworkAccessRequired
        }
        return when (request) {
            is SourcePreparationRequest.M3u -> delegate.prepare(
                RemoteSourceOnboardingInput(
                    locator = request.locator,
                    insecureHttpApproved = request.insecureHttpApproved,
                ),
            ).toApi()

            is SourcePreparationRequest.Xtream -> prepareXtream(request)
        }
    }

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult = when (handle) {
        is RemotePreparationHandle -> delegate.activate(handle.token, sourceName).toApi()
        is XtreamPreparationHandle -> activateXtream(handle, sourceName)
        else -> SourceActivationResult.Failed(
            reason = SourceActivationFailure.Unexpected,
            cleanupPending = false,
        )
    }

    override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult =
        when (handle) {
            is RemotePreparationHandle -> delegate.cancel(handle.token).toApi()
            is XtreamPreparationHandle -> cancelXtream(handle)
            else -> SourceCancellationResult.NotFound
        }

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? =
        delegate.restoreLatestRegistered()?.toApiPrepared()

    private suspend fun prepareXtream(
        request: SourcePreparationRequest.Xtream,
    ): SourcePreparationResult {
        val preparer = xtreamPreparer
            ?: return SourcePreparationResult.Failed(SourcePreparationFailure.UnsupportedProvider)
        return when (
            val result = preparer.prepare(
                XtreamSourcePreparationInput(
                    endpoint = request.endpoint,
                    username = request.username,
                    password = request.password,
                    insecureHttpApproved = request.insecureHttpApproved,
                ),
            )
        ) {
            is XtreamSourcePreparationResult.Prepared -> {
                when (
                    delegate.registerPrepared(
                        preparationId = result.accessReference.value,
                        scheme = result.scheme,
                        host = result.host,
                        rollbackPrepared = { preparer.rollback(result.accessReference) },
                    )
                ) {
                    DurablePreparationRegistrationResult.Registered ->
                        SourcePreparationResult.Prepared(
                            handle = XtreamPreparationHandle(result.accessReference),
                            displayEndpoint = "${result.scheme}://${result.host}",
                        )

                    DurablePreparationRegistrationResult.StorageUnavailable ->
                        SourcePreparationResult.Failed(SourcePreparationFailure.StorageUnavailable)
                }
            }

            XtreamSourcePreparationResult.InsecureTransportApprovalRequired ->
                SourcePreparationResult.InsecureTransportApprovalRequired
            is XtreamSourcePreparationResult.UrlRejected,
            XtreamSourcePreparationResult.InvalidAccess,
            -> SourcePreparationResult.Failed(SourcePreparationFailure.InvalidLocator)
            is XtreamSourcePreparationResult.CredentialTooLarge ->
                SourcePreparationResult.Failed(SourcePreparationFailure.CredentialTooLarge)
            is XtreamSourcePreparationResult.CredentialUnavailable ->
                SourcePreparationResult.Failed(SourcePreparationFailure.StorageUnavailable)
        }
    }

    private suspend fun activateXtream(
        handle: XtreamPreparationHandle,
        sourceName: String,
    ): SourceActivationResult {
        val lifecycle = xtreamLifecycle
            ?: return SourceActivationResult.Failed(
                reason = SourceActivationFailure.Unexpected,
                cleanupPending = false,
            )
        val result = lifecycle.activate(handle.accessReference, sourceName)
        val cleanupComplete = when (result) {
            is RemoteSourceActivationResult.Activated -> true
            is RemoteSourceActivationResult.Failed ->
                result.credentialCleanupFailure == null && result.sourceCleanupFailure == null
        }
        if (cleanupComplete) {
            delegate.completeRegisteredSideEffect(handle.accessReference.value)
        }
        return result.toApi()
    }

    private suspend fun cancelXtream(handle: XtreamPreparationHandle): SourceCancellationResult {
        val preparer = xtreamPreparer ?: return SourceCancellationResult.CleanupPending
        return when (preparer.rollback(handle.accessReference)) {
            CredentialRemoveResult.Removed -> {
                delegate.completeRegisteredSideEffect(handle.accessReference.value)
                SourceCancellationResult.Removed
            }

            CredentialRemoveResult.NotFound -> {
                delegate.completeRegisteredSideEffect(handle.accessReference.value)
                SourceCancellationResult.NotFound
            }

            is CredentialRemoveResult.Unavailable -> SourceCancellationResult.CleanupPending
        }
    }

    private class RemotePreparationHandle(
        val token: RemoteSourcePreparationToken,
    ) : SourcePreparationHandle()

    private class XtreamPreparationHandle(
        val accessReference: SourceAccessReference,
    ) : SourcePreparationHandle()

    private fun RemoteSourcePreparationResult.toApi(): SourcePreparationResult = when (this) {
        is RemoteSourcePreparationResult.Prepared -> toApiPrepared()
        RemoteSourcePreparationResult.InsecureTransportApprovalRequired ->
            SourcePreparationResult.InsecureTransportApprovalRequired
        is RemoteSourcePreparationResult.UrlRejected,
        RemoteSourcePreparationResult.InvalidAccess,
        -> SourcePreparationResult.Failed(SourcePreparationFailure.InvalidLocator)
        is RemoteSourcePreparationResult.CredentialTooLarge ->
            SourcePreparationResult.Failed(SourcePreparationFailure.CredentialTooLarge)
        is RemoteSourcePreparationResult.CredentialUnavailable ->
            SourcePreparationResult.Failed(SourcePreparationFailure.StorageUnavailable)
    }

    private fun RemoteSourcePreparationResult.Prepared.toApiPrepared(): SourcePreparationResult.Prepared =
        SourcePreparationResult.Prepared(
            handle = RemotePreparationHandle(token),
            displayEndpoint = "$scheme://$host",
        )

    private fun DurablePreparedSource.toApiPrepared(): SourcePreparationResult.Prepared =
        SourcePreparationResult.Prepared(
            handle = when (accessReference.kind) {
                SourceAccessKind.M3U -> RemotePreparationHandle(
                    RemoteSourcePreparationToken.parse(accessReference.credentialId.value),
                )

                SourceAccessKind.XTREAM -> XtreamPreparationHandle(accessReference)
            },
            displayEndpoint = "$scheme://$host",
        )

    private fun RemoteSourceActivationResult.toApi(): SourceActivationResult = when (this) {
        is RemoteSourceActivationResult.Activated -> SourceActivationResult.Activated
        is RemoteSourceActivationResult.Failed -> SourceActivationResult.Failed(
            reason = failure.toApi(),
            cleanupPending = credentialCleanupFailure != null || sourceCleanupFailure != null,
        )
    }

    private fun RemoteSourceActivationFailure.toApi(): SourceActivationFailure = when (this) {
        RemoteSourceActivationFailure.InvalidSourceName -> SourceActivationFailure.InvalidSourceName
        RemoteSourceActivationFailure.AccessCredentialNotFound,
        is RemoteSourceActivationFailure.AccessCredentialUnavailable,
        RemoteSourceActivationFailure.AccessCredentialCorrupted,
        -> SourceActivationFailure.AccessUnavailable
        is RemoteSourceActivationFailure.UrlRejected,
        RemoteSourceActivationFailure.InsecureTransportApprovalRequired,
        -> SourceActivationFailure.InvalidLocator
        is RemoteSourceActivationFailure.Http -> SourceActivationFailure.Http
        is RemoteSourceActivationFailure.Network,
        is RemoteSourceActivationFailure.RedirectRejected,
        is RemoteSourceActivationFailure.ResponseTooLarge,
        -> SourceActivationFailure.Network
        RemoteSourceActivationFailure.EmptyRevisionRejected -> SourceActivationFailure.EmptyPlaylist
        is RemoteSourceActivationFailure.ImportFailed -> SourceActivationFailure.Import
        RemoteSourceActivationFailure.Unexpected -> SourceActivationFailure.Unexpected
    }

    private fun RemoteSourceCancellationResult.toApi(): SourceCancellationResult = when (this) {
        RemoteSourceCancellationResult.Removed -> SourceCancellationResult.Removed
        RemoteSourceCancellationResult.NotFound -> SourceCancellationResult.NotFound
        RemoteSourceCancellationResult.MetadataRetained,
        RemoteSourceCancellationResult.SourceCleanupFailed,
        is RemoteSourceCancellationResult.Unavailable,
        -> SourceCancellationResult.CleanupPending
    }
}

private fun DatabaseSourceRefreshOverview.toApi(): SourceRefreshOverview = SourceRefreshOverview(
    sourceId = sourceId,
    sourceName = sourceName,
    hasStoredAccess = hasCredentialReference,
    activeRevision = activeRevision,
    policy = policy?.toApi(),
    status = status?.let { SourceRefreshStatus(state = it.state.toApi()) },
)

private fun DatabaseSourceRefreshPolicy.toApi(): SourceRefreshPolicy = SourceRefreshPolicy(
    sourceId = sourceId,
    enabled = enabled,
    intervalMinutes = intervalMinutes,
    unmeteredOnly = unmeteredOnly,
    requiresCharging = requiresCharging,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun SourceRefreshPolicy.toDatabase(): DatabaseSourceRefreshPolicy = DatabaseSourceRefreshPolicy(
    sourceId = sourceId,
    enabled = enabled,
    intervalMinutes = intervalMinutes,
    unmeteredOnly = unmeteredOnly,
    requiresCharging = requiresCharging,
    updatedAtEpochMillis = updatedAtEpochMillis,
)

private fun DatabaseSourceRefreshRunState.toApi(): SourceRefreshRunState = when (this) {
    DatabaseSourceRefreshRunState.IDLE -> SourceRefreshRunState.IDLE
    DatabaseSourceRefreshRunState.RUNNING -> SourceRefreshRunState.RUNNING
    DatabaseSourceRefreshRunState.SUCCEEDED -> SourceRefreshRunState.SUCCEEDED
    DatabaseSourceRefreshRunState.FAILED -> SourceRefreshRunState.FAILED
    DatabaseSourceRefreshRunState.NEEDS_AUTH -> SourceRefreshRunState.NEEDS_AUTH
    DatabaseSourceRefreshRunState.CANCELLED -> SourceRefreshRunState.CANCELLED
}
