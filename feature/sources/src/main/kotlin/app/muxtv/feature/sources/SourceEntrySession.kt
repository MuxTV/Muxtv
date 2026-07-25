package app.muxtv.feature.sources

import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceCancellationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

sealed interface SourceEntryUiState {
    data object Editing : SourceEntryUiState
    data object Restoring : SourceEntryUiState
    data object Preparing : SourceEntryUiState
    data object HttpApprovalRequired : SourceEntryUiState
    data class Confirming(val endpoint: String) : SourceEntryUiState
    data object Activating : SourceEntryUiState
    data object Completed : SourceEntryUiState
    data class Failed(
        val reason: SourceEntryFailure,
        val cleanupPending: Boolean = false,
    ) : SourceEntryUiState
}

enum class SourceEntryFailure {
    InvalidLocator,
    CredentialTooLarge,
    StorageUnavailable,
    InvalidSourceName,
    AccessUnavailable,
    Network,
    Http,
    EmptyPlaylist,
    Import,
    CleanupPending,
    SessionExpired,
    Unexpected,
}

interface SourceEntryOnboarding {
    suspend fun prepare(input: RemoteSourceOnboardingInput): RemoteSourcePreparationResult

    suspend fun activate(
        token: RemoteSourcePreparationToken,
        sourceName: String,
    ): RemoteSourceActivationResult

    suspend fun cancel(token: RemoteSourcePreparationToken): RemoteSourceCancellationResult

    suspend fun restoreLatestPrepared(): RemoteSourcePreparationResult.Prepared?
}

class SourceEntrySession(
    private val onboarding: SourceEntryOnboarding,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<SourceEntryUiState>(SourceEntryUiState.Editing)

    val state: StateFlow<SourceEntryUiState> = mutableState.asStateFlow()

    private var preparedToken: RemoteSourcePreparationToken? = null
    private var preparedEndpoint: String? = null
    private var pendingHttpLocator: String? = null

    suspend fun restore() = runExclusive {
        if (preparedToken != null || mutableState.value !is SourceEntryUiState.Editing) return@runExclusive
        mutableState.value = SourceEntryUiState.Restoring
        val restored = try {
            onboarding.restoreLatestPrepared()
        } catch (cancelled: CancellationException) {
            mutableState.value = SourceEntryUiState.Editing
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (restored == null) {
            mutableState.value = SourceEntryUiState.Editing
        } else {
            acceptPrepared(restored)
        }
    }

    suspend fun prepare(locator: String) = runExclusive {
        if (preparedToken != null) return@runExclusive
        prepareLocked(locator = locator, insecureHttpApproved = false)
    }

    suspend fun approveInsecureHttp() = runExclusive {
        val locator = pendingHttpLocator ?: return@runExclusive
        prepareLocked(locator = locator, insecureHttpApproved = true)
    }

    suspend fun activate(sourceName: String) = runExclusive {
        val token = preparedToken
        if (token == null) {
            mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.SessionExpired)
            return@runExclusive
        }

        val endpoint = preparedEndpoint
        if (endpoint == null) {
            mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.SessionExpired)
            return@runExclusive
        }

        mutableState.value = SourceEntryUiState.Activating
        val result = try {
            onboarding.activate(token, sourceName)
        } catch (cancelled: CancellationException) {
            mutableState.value = SourceEntryUiState.Confirming(endpoint = endpoint)
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = SourceEntryUiState.Failed(
                reason = SourceEntryFailure.CleanupPending,
                cleanupPending = true,
            )
            return@runExclusive
        }

        when (result) {
            is RemoteSourceActivationResult.Activated -> {
                preparedToken = null
                preparedEndpoint = null
                mutableState.value = SourceEntryUiState.Completed
            }

            is RemoteSourceActivationResult.Failed -> {
                val cleanupPending =
                    result.credentialCleanupFailure != null || result.sourceCleanupFailure != null
                if (!cleanupPending) {
                    preparedToken = null
                    preparedEndpoint = null
                }
                mutableState.value = SourceEntryUiState.Failed(
                    reason = result.failure.toEntryFailure(),
                    cleanupPending = cleanupPending,
                )
            }
        }
    }

    suspend fun cancel(): Boolean {
        if (!operationMutex.tryLock()) return false
        return try {
            pendingHttpLocator = null
            val token = preparedToken
            if (token == null) {
                mutableState.value = SourceEntryUiState.Editing
                true
            } else {
                when (onboarding.cancel(token)) {
                    RemoteSourceCancellationResult.Removed,
                    RemoteSourceCancellationResult.NotFound,
                    -> {
                        preparedToken = null
                        preparedEndpoint = null
                        mutableState.value = SourceEntryUiState.Editing
                        true
                    }

                    RemoteSourceCancellationResult.MetadataRetained,
                    RemoteSourceCancellationResult.SourceCleanupFailed,
                    is RemoteSourceCancellationResult.Unavailable,
                    -> {
                        mutableState.value = SourceEntryUiState.Failed(
                            reason = SourceEntryFailure.CleanupPending,
                            cleanupPending = true,
                        )
                        false
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.value = SourceEntryUiState.Failed(
                reason = SourceEntryFailure.CleanupPending,
                cleanupPending = true,
            )
            false
        } finally {
            operationMutex.unlock()
        }
    }

    fun editAgain() {
        if (preparedToken == null && !operationMutex.isLocked) {
            pendingHttpLocator = null
            preparedEndpoint = null
            mutableState.value = SourceEntryUiState.Editing
        }
    }

    fun clearTransientLocator() {
        pendingHttpLocator = null
    }

    private suspend fun prepareLocked(
        locator: String,
        insecureHttpApproved: Boolean,
    ) {
        if (locator.isBlank()) {
            pendingHttpLocator = null
            mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.InvalidLocator)
            return
        }

        pendingHttpLocator = locator
        mutableState.value = SourceEntryUiState.Preparing
        val result = try {
            onboarding.prepare(
                RemoteSourceOnboardingInput(
                    locator = locator,
                    insecureHttpApproved = insecureHttpApproved,
                ),
            )
        } catch (cancelled: CancellationException) {
            pendingHttpLocator = null
            preparedEndpoint = null
            mutableState.value = SourceEntryUiState.Editing
            throw cancelled
        } catch (_: Exception) {
            pendingHttpLocator = null
            mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.Unexpected)
            return
        }

        when (result) {
            is RemoteSourcePreparationResult.Prepared -> acceptPrepared(result)
            RemoteSourcePreparationResult.InsecureTransportApprovalRequired ->
                mutableState.value = SourceEntryUiState.HttpApprovalRequired

            is RemoteSourcePreparationResult.UrlRejected,
            RemoteSourcePreparationResult.InvalidAccess,
            -> {
                pendingHttpLocator = null
                mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.InvalidLocator)
            }

            is RemoteSourcePreparationResult.CredentialTooLarge -> {
                pendingHttpLocator = null
                mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.CredentialTooLarge)
            }

            is RemoteSourcePreparationResult.CredentialUnavailable -> {
                pendingHttpLocator = null
                mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.StorageUnavailable)
            }
        }
    }

    private fun acceptPrepared(result: RemoteSourcePreparationResult.Prepared) {
        preparedToken = result.token
        preparedEndpoint = "${result.scheme}://${result.host}"
        pendingHttpLocator = null
        mutableState.value = SourceEntryUiState.Confirming(
            endpoint = requireNotNull(preparedEndpoint),
        )
    }

    private suspend inline fun runExclusive(crossinline block: suspend () -> Unit) {
        if (!operationMutex.tryLock()) return
        try {
            block()
        } finally {
            operationMutex.unlock()
        }
    }
}

private fun RemoteSourceActivationFailure.toEntryFailure(): SourceEntryFailure = when (this) {
    RemoteSourceActivationFailure.InvalidSourceName -> SourceEntryFailure.InvalidSourceName
    RemoteSourceActivationFailure.AccessCredentialNotFound,
    is RemoteSourceActivationFailure.AccessCredentialUnavailable,
    RemoteSourceActivationFailure.AccessCredentialCorrupted,
    -> SourceEntryFailure.AccessUnavailable

    is RemoteSourceActivationFailure.UrlRejected,
    RemoteSourceActivationFailure.InsecureTransportApprovalRequired,
    -> SourceEntryFailure.InvalidLocator

    is RemoteSourceActivationFailure.Http -> SourceEntryFailure.Http
    is RemoteSourceActivationFailure.Network,
    is RemoteSourceActivationFailure.RedirectRejected,
    is RemoteSourceActivationFailure.ResponseTooLarge,
    -> SourceEntryFailure.Network

    RemoteSourceActivationFailure.EmptyRevisionRejected -> SourceEntryFailure.EmptyPlaylist
    is RemoteSourceActivationFailure.ImportFailed -> SourceEntryFailure.Import
    RemoteSourceActivationFailure.Unexpected -> SourceEntryFailure.Unexpected
}
