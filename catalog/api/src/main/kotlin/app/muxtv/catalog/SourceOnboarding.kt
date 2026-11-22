package app.muxtv.catalog

/**
 * Opaque capability for one prepared onboarding transaction.
 *
 * Implementations may retain credential-backed identity internally, but the feature layer can
 * neither inspect nor serialize it. The textual representation is deliberately redacted.
 */
abstract class SourcePreparationHandle protected constructor() {
    final override fun toString(): String = "SourcePreparationHandle(<redacted>)"
}

/** Provider-neutral source input accepted by the stable onboarding port. */
sealed interface SourcePreparationRequest {
    class M3u(
        val locator: String,
        val insecureHttpApproved: Boolean = false,
    ) : SourcePreparationRequest {
        override fun toString(): String =
            "SourcePreparationRequest.M3u(locator=<redacted>, " +
                "insecureHttpApproved=$insecureHttpApproved)"
    }

    class Xtream(
        val endpoint: String,
        val username: String,
        val password: String,
        val insecureHttpApproved: Boolean = false,
    ) : SourcePreparationRequest {
        override fun toString(): String =
            "SourcePreparationRequest.Xtream(endpoint=<redacted>, username=<redacted>, " +
                "password=<redacted>, insecureHttpApproved=$insecureHttpApproved)"
    }
}

enum class SourcePreparationFailure {
    InvalidLocator,
    CredentialTooLarge,
    StorageUnavailable,
    UnsupportedProvider,
}

sealed interface SourcePreparationResult {
    data class Prepared(
        val handle: SourcePreparationHandle,
        val displayEndpoint: String,
    ) : SourcePreparationResult {
        init {
            require(displayEndpoint.startsWith("http://") || displayEndpoint.startsWith("https://"))
            require('@' !in displayEndpoint)
            require('?' !in displayEndpoint)
            require('#' !in displayEndpoint)
        }
    }

    /** Preparation must not perform network I/O until app-owned LAN permission is granted. */
    data object LocalNetworkAccessRequired : SourcePreparationResult

    data object InsecureTransportApprovalRequired : SourcePreparationResult

    data class Failed(
        val reason: SourcePreparationFailure,
    ) : SourcePreparationResult
}

enum class SourceActivationFailure {
    InvalidSourceName,
    AccessUnavailable,
    InvalidLocator,
    Network,
    Http,
    EmptyPlaylist,
    Import,
    Unexpected,
}

sealed interface SourceActivationResult {
    data object Activated : SourceActivationResult

    /** Prepared access remains valid; activation may be replayed after app-owned LAN grant. */
    data object LocalNetworkAccessRequired : SourceActivationResult

    data class Failed(
        val reason: SourceActivationFailure,
        val cleanupPending: Boolean,
    ) : SourceActivationResult
}

enum class SourceCancellationResult {
    Removed,
    NotFound,
    CleanupPending,
}

/** Stable durable-onboarding port consumed by the feature layer. */
interface SourceOnboarding {
    suspend fun prepare(
        locator: String,
        insecureHttpApproved: Boolean = false,
    ): SourcePreparationResult

    /**
     * Provider-neutral entry point. The default keeps existing M3U-only implementations source
     * compatible while making unsupported provider kinds explicit and typed.
     */
    suspend fun prepare(request: SourcePreparationRequest): SourcePreparationResult =
        when (request) {
            is SourcePreparationRequest.M3u -> prepare(
                locator = request.locator,
                insecureHttpApproved = request.insecureHttpApproved,
            )

            is SourcePreparationRequest.Xtream -> SourcePreparationResult.Failed(
                SourcePreparationFailure.UnsupportedProvider,
            )
        }

    suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult

    suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult

    suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared?
}
