package app.muxtv.localcontrol.onboarding

import app.muxtv.catalog.SourceActivationResult
import app.muxtv.catalog.SourceCancellationResult
import app.muxtv.catalog.SourceOnboarding
import app.muxtv.catalog.SourcePreparationHandle
import app.muxtv.catalog.SourcePreparationRequest
import app.muxtv.catalog.SourcePreparationResult
import app.muxtv.localcontrol.PairingAuthority
import app.muxtv.localcontrol.PairingCapability
import app.muxtv.localcontrol.PairingGrant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * NOT an HTTP bearer credential, TLS certificate, or permission to trust the old /pair page.
 * Construction is deliberately module-internal. A future independently reviewed protected
 * transport must be implemented in this module before it can mint this principal.
 * Never expose the grant/credential over HTTP, logs, persistence, analytics or navigation.
 */
class ProtectedSourcePrincipal internal constructor(
    private val pairing: PairingAuthority,
    grant: PairingGrant,
) {
    internal val sessionId: String = grant.sessionId
    private val credential = grant.credential

    internal fun canWrite(): Boolean =
        pairing.authorize(sessionId, credential, PairingCapability.SOURCE_CREATE)

    override fun toString(): String = "ProtectedSourcePrincipal([REDACTED])"
}

/**
 * A deliberately secret-free response vocabulary. Neither prepared identity, provider
 * endpoint, credentials nor SourcePreparationHandle may cross the phone boundary.
 */
enum class SourceStagingResult {
    PREPARED,
    ACTIVATED,
    CANCELED,
    EMPTY,
    ACCESS_DENIED,
    INVALID_INPUT,
    ALREADY_PREPARED,
    LOCAL_NETWORK_PERMISSION_REQUIRED,
    TV_HTTP_APPROVAL_REQUIRED,
    CLEANUP_PENDING,
    FAILED,
}

/**
 * Pure Kotlin TV-side adapter for the EXISTING SourceOnboarding port.
 *
 * No public HTTP route constructs ProtectedSourcePrincipal. This stage is dormant until
 * a protected phone->TV channel can authenticate its client, bind a session and reject
 * active LAN MITM; private RFC1918 HTTP and Origin checks are insufficient.
 *
 * At most one opaque preparation handle per paired session is retained in memory. The
 * inputs are never stored here, and an unpaired/revoked session can never activate a handle.
 */
class ProtectedSourceStaging(private val onboarding: SourceOnboarding) {
    private val mutex = Mutex()
    private val prepared = mutableMapOf<String, SourcePreparationHandle>()

    suspend fun prepare(
        principal: ProtectedSourcePrincipal,
        request: SourcePreparationRequest,
    ): SourceStagingResult = mutex.withLock {
        if (!principal.canWrite()) return@withLock SourceStagingResult.ACCESS_DENIED
        if (!request.isBounded() || request.explicitlyApprovesInsecureHttp()) {
            return@withLock SourceStagingResult.INVALID_INPUT
        }
        if (principal.sessionId in prepared) return@withLock SourceStagingResult.ALREADY_PREPARED

        val result = try {
            onboarding.prepare(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock SourceStagingResult.FAILED
        }

        if (!principal.canWrite()) {
            if (result is SourcePreparationResult.Prepared) {
                cancelHandle(result.handle) // No newly prepared state after revocation.
            }
            return@withLock SourceStagingResult.ACCESS_DENIED
        }

        when (result) {
            is SourcePreparationResult.Prepared -> {
                prepared[principal.sessionId] = result.handle
                SourceStagingResult.PREPARED
            }
            SourcePreparationResult.LocalNetworkAccessRequired ->
                SourceStagingResult.LOCAL_NETWORK_PERMISSION_REQUIRED

            SourcePreparationResult.InsecureTransportApprovalRequired ->
                SourceStagingResult.TV_HTTP_APPROVAL_REQUIRED

            is SourcePreparationResult.Failed -> SourceStagingResult.FAILED
        }
    }

    suspend fun activate(
        principal: ProtectedSourcePrincipal,
        sourceName: String,
    ): SourceStagingResult = mutex.withLock {
        if (!principal.canWrite()) return@withLock SourceStagingResult.ACCESS_DENIED
        if (!sourceName.isValidDisplayName()) return@withLock SourceStagingResult.INVALID_INPUT
        val handle = prepared[principal.sessionId] ?: return@withLock SourceStagingResult.EMPTY
        val result = try {
            onboarding.activate(handle, sourceName)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return@withLock SourceStagingResult.FAILED
        }
        if (!principal.canWrite()) {
            prepared.remove(principal.sessionId)
            // Activation may already have committed; do not claim rollback or success.
            if (result !is SourceActivationResult.Activated) cancelHandle(handle)
            return@withLock SourceStagingResult.ACCESS_DENIED
        }
        when (result) {
            SourceActivationResult.Activated -> {
                prepared.remove(principal.sessionId)
                SourceStagingResult.ACTIVATED
            }
            SourceActivationResult.LocalNetworkAccessRequired ->
                SourceStagingResult.LOCAL_NETWORK_PERMISSION_REQUIRED

            is SourceActivationResult.Failed -> {
                if (!result.cleanupPending) prepared.remove(principal.sessionId)
                if (result.cleanupPending) SourceStagingResult.CLEANUP_PENDING
                else SourceStagingResult.FAILED
            }
        }
    }

    suspend fun cancel(principal: ProtectedSourcePrincipal): SourceStagingResult = mutex.withLock {
        if (!principal.canWrite()) return@withLock SourceStagingResult.ACCESS_DENIED
        val handle = prepared[principal.sessionId] ?: return@withLock SourceStagingResult.EMPTY
        when (cancelHandle(handle)) {
            SourceCancellationResult.Removed, SourceCancellationResult.NotFound -> {
                prepared.remove(principal.sessionId)
                SourceStagingResult.CANCELED
            }
            SourceCancellationResult.CleanupPending -> SourceStagingResult.CLEANUP_PENDING
        }
    }

    /**
     * TV lifecycle path only: caller revokes PairingAuthority before this method.
     * No grant is required to remove sensitive in-memory preparatory state.
     * Any durable cleanup failure is reported; recovery belongs to SourceOnboarding.
     */
    suspend fun discardRevoked(sessionId: String): SourceStagingResult = mutex.withLock {
        val handle = prepared.remove(sessionId) ?: return@withLock SourceStagingResult.EMPTY
        when (cancelHandle(handle)) {
            SourceCancellationResult.Removed, SourceCancellationResult.NotFound ->
                SourceStagingResult.CANCELED
            SourceCancellationResult.CleanupPending -> SourceStagingResult.CLEANUP_PENDING
        }
    }

    suspend fun discardAllAfterStop(): SourceStagingResult = mutex.withLock {
        var pending = false
        val entries = prepared.values.toList()
        prepared.clear()
        entries.forEach { handle ->
            if (cancelHandle(handle) == SourceCancellationResult.CleanupPending) pending = true
        }
        if (pending) SourceStagingResult.CLEANUP_PENDING else SourceStagingResult.CANCELED
    }

    private suspend fun cancelHandle(handle: SourcePreparationHandle): SourceCancellationResult =
        try {
            onboarding.cancel(handle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SourceCancellationResult.CleanupPending
        }
}

private fun SourcePreparationRequest.explicitlyApprovesInsecureHttp(): Boolean = when (this) {
    is SourcePreparationRequest.M3u -> insecureHttpApproved
    is SourcePreparationRequest.Xtream -> insecureHttpApproved
}

private fun SourcePreparationRequest.isBounded(): Boolean = when (this) {
    is SourcePreparationRequest.M3u ->
        locator.isBoundedUtf8(4_096)

    is SourcePreparationRequest.Xtream ->
        endpoint.isBoundedUtf8(4_096) &&
            username.isBoundedUtf8(256) &&
            password.isBoundedUtf8(2_048)
}

private fun String.isBoundedUtf8(max: Int): Boolean =
    isNotBlank() && length <= max && toByteArray(Charsets.UTF_8).size <= max

private fun String.isValidDisplayName(): Boolean =
    isBoundedUtf8(120) && trim() == this && none { it.isISOControl() }
