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

enum class SourcePreparationFailure {
    InvalidLocator,
    CredentialTooLarge,
    StorageUnavailable,
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

    suspend fun activate(
        handle: SourcePreparationHandle,
        sourceName: String,
    ): SourceActivationResult

    suspend fun cancel(handle: SourcePreparationHandle): SourceCancellationResult

    suspend fun restoreLatestPrepared(): SourcePreparationResult.Prepared?
}
