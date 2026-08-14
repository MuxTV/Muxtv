package app.muxtv.external

import android.content.SharedPreferences
import java.util.Collections

enum class ExternalPlaybackOriginGrantResult {
    Applied,
    Unchanged,
    CapacityExceeded,
}

/**
 * Exact-origin grants for cleartext external playback.
 *
 * HTTPS never requires a grant. Grants authorize `scheme://host:port` only; path, query and
 * credentials are never part of a grant and never stored.
 */
interface ExternalPlaybackOriginGrantStore {
    fun contains(origin: ExternalPlaybackOrigin): Boolean

    fun approve(origin: ExternalPlaybackOrigin): ExternalPlaybackOriginGrantResult

    fun revokeAll()
}

/**
 * Bounded in-memory grant set with corruption-aware restore semantics.
 *
 * Any malformed stored origin fails the whole restore: grants are dropped (fail closed) and the
 * persistent adapter clears its storage.
 */
class ExternalPlaybackOriginGrants(
    private val maxOrigins: Int = DEFAULT_MAX_ORIGINS,
) {
    private val approved = LinkedHashSet<ExternalPlaybackOrigin>()

    init {
        require(maxOrigins > 0)
    }

    @Synchronized
    fun contains(origin: ExternalPlaybackOrigin): Boolean = origin in approved

    @Synchronized
    fun approve(origin: ExternalPlaybackOrigin): ExternalPlaybackOriginGrantResult = when {
        origin in approved -> ExternalPlaybackOriginGrantResult.Unchanged
        approved.size >= maxOrigins -> ExternalPlaybackOriginGrantResult.CapacityExceeded
        else -> {
            approved.add(origin)
            ExternalPlaybackOriginGrantResult.Applied
        }
    }

    @Synchronized
    fun revokeAll() {
        approved.clear()
    }

    @Synchronized
    fun snapshot(): Set<ExternalPlaybackOrigin> =
        Collections.unmodifiableSet(LinkedHashSet(approved))

    enum class RestoreResult {
        Restored,
        Corrupted,
    }

    @Synchronized
    fun restore(encodedOrigins: Collection<String>): RestoreResult {
        approved.clear()
        val parsed = LinkedHashSet<ExternalPlaybackOrigin>()
        for (encoded in encodedOrigins) {
            val origin = ExternalPlaybackOrigin.parse(encoded)
                ?: return RestoreResult.Corrupted
            parsed.add(origin)
            if (parsed.size > maxOrigins) return RestoreResult.Corrupted
        }
        approved.addAll(parsed)
        return RestoreResult.Restored
    }

    companion object {
        const val DEFAULT_MAX_ORIGINS = 32
    }
}

/**
 * SharedPreferences-backed adapter. Fails closed: unreadable or malformed persisted state clears
 * all grants instead of allowing unknown origins.
 */
class SharedPreferencesExternalPlaybackOriginGrantStore(
    private val preferences: SharedPreferences,
) : ExternalPlaybackOriginGrantStore {
    private val grants = ExternalPlaybackOriginGrants()

    init {
        val stored = preferences.getStringSet(KEY_ORIGINS, emptySet())
        val restoreResult = grants.restore(stored ?: emptySet())
        if (restoreResult == ExternalPlaybackOriginGrants.RestoreResult.Corrupted) {
            clearStorage()
        }
    }

    @Synchronized
    override fun contains(origin: ExternalPlaybackOrigin): Boolean = grants.contains(origin)

    @Synchronized
    override fun approve(origin: ExternalPlaybackOrigin): ExternalPlaybackOriginGrantResult {
        val result = grants.approve(origin)
        if (result == ExternalPlaybackOriginGrantResult.Applied) {
            persist()
        }
        return result
    }

    @Synchronized
    override fun revokeAll() {
        grants.revokeAll()
        clearStorage()
    }

    private fun persist() {
        preferences.edit()
            .putStringSet(KEY_ORIGINS, grants.snapshot().mapTo(LinkedHashSet()) { it.encoded })
            .apply()
    }

    private fun clearStorage() {
        preferences.edit().remove(KEY_ORIGINS).apply()
    }

    private companion object {
        const val KEY_ORIGINS = "external_playback_origin_grants"
    }
}
