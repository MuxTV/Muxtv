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
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.catalog.SourceRefreshOverview
import app.muxtv.catalog.SourceRefreshPolicy
import app.muxtv.catalog.SourceRefreshRunState
import app.muxtv.catalog.SourceRefreshStatus
import app.muxtv.catalog.onboarding.DurableRemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.catalog.sync.SourceRefreshScheduler
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
) : SourceOnboarding {
    override suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean,
    ): SourcePreparationResult = delegate.prepare(
        RemoteSourceOnboardingInput(
            locator = locator,
            insecureHttpApproved = insecureHttpApproved,
        ),
    ).toApi()

    override suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult {
        val remoteHandle = handle as? RemotePreparationHandle
            ?: return SourceActivationResult.Failed(
                reason = SourceActivationFailure.Unexpected,
                cleanupPending = false,
            )
        return delegate.activate(remoteHandle.token, sourceName).toApi()
    }

    override suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult {
        val remoteHandle = handle as? RemotePreparationHandle
            ?: return SourceCancellationResult.NotFound
        return delegate.cancel(remoteHandle.token).toApi()
    }

    override suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared? =
        delegate.restoreLatestPrepared()?.toApiPrepared()

    private class RemotePreparationHandle(
        val token: RemoteSourcePreparationToken,
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
