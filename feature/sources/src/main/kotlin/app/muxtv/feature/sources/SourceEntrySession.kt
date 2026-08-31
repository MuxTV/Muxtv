package app.muxtv.feature.sources

import app.muxtv.catalog.SourceActivationFailure
import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationFailure
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

typealias SourceEntryOnboarding = SourceOnboarding

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

class SourceEntrySession(
    private val onboarding: SourceOnboarding,
) {
    private val operationMutex = Mutex()
    private val mutableState = MutableStateFlow<SourceEntryUiState>(SourceEntryUiState.Editing)

    val state: StateFlow<SourceEntryUiState> = mutableState.asStateFlow()

    private var preparedHandle: SourcePreparationHandle? = null
    private var preparedEndpoint: String? = null
    private var pendingHttpRequest: SourcePreparationRequest? = null

    suspend fun restore() = runExclusive {
        if (preparedHandle != null || mutableState.value !is SourceEntryUiState.Editing) return@runExclusive
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
        if (preparedHandle != null) return@runExclusive
        prepareLocked(
            SourcePreparationRequest.M3u(
                locator = locator,
                insecureHttpApproved = false,
            ),
        )
    }

    suspend fun prepareXtream(
        endpoint: String,
        username: String,
        password: String,
    ) = runExclusive {
        if (preparedHandle != null) return@runExclusive
        prepareLocked(
            SourcePreparationRequest.Xtream(
                endpoint = endpoint,
                username = username,
                password = password,
                insecureHttpApproved = false,
            ),
        )
    }

    suspend fun approveInsecureHttp() = runExclusive {
        val request = pendingHttpRequest ?: return@runExclusive
        prepareLocked(request.withInsecureHttpApproval())
    }

    suspend fun activate(sourceName: String) = runExclusive {
        val handle = preparedHandle
        if (handle == null) {
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
            onboarding.activate(handle, sourceName)
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
            SourceActivationResult.Activated -> {
                preparedHandle = null
                preparedEndpoint = null
                mutableState.value = SourceEntryUiState.Completed
            }

            is SourceActivationResult.Failed -> {
                if (!result.cleanupPending) {
                    preparedHandle = null
                    preparedEndpoint = null
                }
                mutableState.value = SourceEntryUiState.Failed(
                    reason = result.reason.toEntryFailure(),
                    cleanupPending = result.cleanupPending,
                )
            }
        }
    }

    suspend fun cancel(): Boolean {
        if (!operationMutex.tryLock()) return false
        return try {
            pendingHttpRequest = null
            val handle = preparedHandle
            if (handle == null) {
                mutableState.value = SourceEntryUiState.Editing
                true
            } else {
                when (onboarding.cancel(handle)) {
                    SourceCancellationResult.Removed,
                    SourceCancellationResult.NotFound,
                    -> {
                        preparedHandle = null
                        preparedEndpoint = null
                        mutableState.value = SourceEntryUiState.Editing
                        true
                    }

                    SourceCancellationResult.CleanupPending -> {
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
        if (preparedHandle == null && !operationMutex.isLocked) {
            pendingHttpRequest = null
            preparedEndpoint = null
            mutableState.value = SourceEntryUiState.Editing
        }
    }

    fun clearTransientLocator() {
        pendingHttpRequest = null
    }

    private suspend fun prepareLocked(request: SourcePreparationRequest) {
        if (!request.hasRequiredInput()) {
            pendingHttpRequest = null
            mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.InvalidLocator)
            return
        }

        pendingHttpRequest = request
        mutableState.value = SourceEntryUiState.Preparing
        val result = try {
            when (request) {
                is SourcePreparationRequest.M3u -> onboarding.prepare(
                    locator = request.locator,
                    insecureHttpApproved = request.insecureHttpApproved,
                )

                is SourcePreparationRequest.Xtream -> onboarding.prepare(request)
            }
        } catch (cancelled: CancellationException) {
            pendingHttpRequest = null
            preparedEndpoint = null
            mutableState.value = SourceEntryUiState.Editing
            throw cancelled
        } catch (_: Exception) {
            pendingHttpRequest = null
            mutableState.value = SourceEntryUiState.Failed(SourceEntryFailure.Unexpected)
            return
        }

        when (result) {
            is SourcePreparationResult.Prepared -> acceptPrepared(result)
            SourcePreparationResult.InsecureTransportApprovalRequired ->
                mutableState.value = SourceEntryUiState.HttpApprovalRequired

            is SourcePreparationResult.Failed -> {
                pendingHttpRequest = null
                mutableState.value = SourceEntryUiState.Failed(result.reason.toEntryFailure())
            }
        }
    }

    private fun acceptPrepared(result: SourcePreparationResult.Prepared) {
        preparedHandle = result.handle
        preparedEndpoint = result.displayEndpoint
        pendingHttpRequest = null
        mutableState.value = SourceEntryUiState.Confirming(
            endpoint = result.displayEndpoint,
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

private fun SourcePreparationRequest.hasRequiredInput(): Boolean = when (this) {
    is SourcePreparationRequest.M3u -> locator.isNotBlank()
    is SourcePreparationRequest.Xtream ->
        endpoint.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

private fun SourcePreparationRequest.withInsecureHttpApproval(): SourcePreparationRequest = when (this) {
    is SourcePreparationRequest.M3u -> SourcePreparationRequest.M3u(
        locator = locator,
        insecureHttpApproved = true,
    )

    is SourcePreparationRequest.Xtream -> SourcePreparationRequest.Xtream(
        endpoint = endpoint,
        username = username,
        password = password,
        insecureHttpApproved = true,
    )
}

private fun SourcePreparationFailure.toEntryFailure(): SourceEntryFailure = when (this) {
    SourcePreparationFailure.InvalidLocator -> SourceEntryFailure.InvalidLocator
    SourcePreparationFailure.CredentialTooLarge -> SourceEntryFailure.CredentialTooLarge
    SourcePreparationFailure.StorageUnavailable -> SourceEntryFailure.StorageUnavailable
    SourcePreparationFailure.UnsupportedProvider -> SourceEntryFailure.Unexpected
}

private fun SourceActivationFailure.toEntryFailure(): SourceEntryFailure = when (this) {
    SourceActivationFailure.InvalidSourceName -> SourceEntryFailure.InvalidSourceName
    SourceActivationFailure.AccessUnavailable -> SourceEntryFailure.AccessUnavailable
    SourceActivationFailure.InvalidLocator -> SourceEntryFailure.InvalidLocator
    SourceActivationFailure.Network -> SourceEntryFailure.Network
    SourceActivationFailure.Http -> SourceEntryFailure.Http
    SourceActivationFailure.EmptyPlaylist -> SourceEntryFailure.EmptyPlaylist
    SourceActivationFailure.Import -> SourceEntryFailure.Import
    SourceActivationFailure.Unexpected -> SourceEntryFailure.Unexpected
}
