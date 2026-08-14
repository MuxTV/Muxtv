package app.muxtv.player

sealed interface ExternalPlaybackClaimResult {
    data class Claimed(
        val descriptor: ExternalPlaybackDescriptor,
        val sessionId: String,
    ) : ExternalPlaybackClaimResult

    data object Unknown : ExternalPlaybackClaimResult

    data object Expired : ExternalPlaybackClaimResult
}

/**
 * Process-local registry holding transient external playback descriptors under opaque lease ids.
 *
 * Leases are consumed on claim: the same lease id can never authorize two setups. Bounded
 * capacity and TTL keep untrusted intent data from accumulating in memory.
 */
interface ExternalPlaybackLeaseRegistry {
    fun register(
        descriptor: ExternalPlaybackDescriptor,
        sessionId: String,
        nowEpochMillis: Long,
    ): ExternalPlaybackLeaseId

    fun claim(
        leaseId: ExternalPlaybackLeaseId,
        nowEpochMillis: Long,
    ): ExternalPlaybackClaimResult

    fun remove(leaseId: ExternalPlaybackLeaseId)

    fun removeSession(sessionId: String)
}
