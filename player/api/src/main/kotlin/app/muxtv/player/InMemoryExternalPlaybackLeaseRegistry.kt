package app.muxtv.player

import java.util.LinkedHashMap

/**
 * Bounded, TTL-guarded, consume-on-claim lease registry.
 *
 * Entries expire after [leaseTtlMillis] of wall-clock time; claiming removes the entry, so a
 * stale setup or a replayed command can never claim the same lease twice. Capacity eviction
 * drops the oldest entry. Descriptor contents are never logged by this class.
 */
class InMemoryExternalPlaybackLeaseRegistry(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val leaseTtlMillis: Long = DEFAULT_LEASE_TTL_MILLIS,
) : ExternalPlaybackLeaseRegistry {
    init {
        require(capacity > 0)
        require(leaseTtlMillis > 0L)
    }

    private val entries = LinkedHashMap<ExternalPlaybackLeaseId, Entry>()

    @Synchronized
    override fun register(
        descriptor: ExternalPlaybackDescriptor,
        sessionId: String,
        nowEpochMillis: Long,
    ): ExternalPlaybackLeaseId {
        require(sessionId.isNotBlank() && sessionId.length <= MAX_SESSION_ID_LENGTH)
        require(!sessionId.contains('\r') && !sessionId.contains('\n'))
        require(nowEpochMillis >= 0L)
        evictExpired(nowEpochMillis)
        removeSession(sessionId)
        while (entries.size >= capacity) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        val leaseId = ExternalPlaybackLeaseId.create()
        entries[leaseId] = Entry(
            descriptor = descriptor,
            sessionId = sessionId,
            expiresAtEpochMillis = nowEpochMillis + leaseTtlMillis,
        )
        return leaseId
    }

    @Synchronized
    override fun claim(
        leaseId: ExternalPlaybackLeaseId,
        nowEpochMillis: Long,
    ): ExternalPlaybackClaimResult {
        require(nowEpochMillis >= 0L)
        val entry = entries[leaseId] ?: return ExternalPlaybackClaimResult.Unknown
        entries.remove(leaseId)
        if (entry.expiresAtEpochMillis <= nowEpochMillis) {
            return ExternalPlaybackClaimResult.Expired
        }
        return ExternalPlaybackClaimResult.Claimed(
            descriptor = entry.descriptor,
            sessionId = entry.sessionId,
        )
    }

    @Synchronized
    override fun remove(leaseId: ExternalPlaybackLeaseId) {
        entries.remove(leaseId)
    }

    @Synchronized
    override fun removeSession(sessionId: String) {
        entries.values.removeIf { it.sessionId == sessionId }
    }

    @Synchronized
    internal fun size(): Int = entries.size

    private fun evictExpired(nowEpochMillis: Long) {
        entries.values.removeIf { it.expiresAtEpochMillis <= nowEpochMillis }
    }

    private class Entry(
        val descriptor: ExternalPlaybackDescriptor,
        val sessionId: String,
        val expiresAtEpochMillis: Long,
    )

    companion object {
        const val DEFAULT_CAPACITY = 8
        const val DEFAULT_LEASE_TTL_MILLIS = 10 * 60_000L
        private const val MAX_SESSION_ID_LENGTH = 512
    }
}
