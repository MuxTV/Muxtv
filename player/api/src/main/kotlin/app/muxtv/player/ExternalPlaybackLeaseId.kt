package app.muxtv.player

import java.util.UUID

/**
 * Opaque random handle for an external playback lease.
 *
 * The id never encodes the underlying descriptor, locator or any media identifier. It is the
 * only external-playback value allowed to cross the MediaSession custom-command boundary.
 */
@JvmInline
value class ExternalPlaybackLeaseId private constructor(
    private val value: String,
) {
    fun encoded(): String = value

    override fun toString(): String = "ExternalPlaybackLeaseId(<redacted>)"

    companion object {
        private const val MAX_LENGTH = 64
        private val VALID_VALUE = Regex("[A-Za-z0-9-]{1,$MAX_LENGTH}")

        fun create(): ExternalPlaybackLeaseId = ExternalPlaybackLeaseId(UUID.randomUUID().toString())

        fun parse(raw: String?): ExternalPlaybackLeaseId? = raw
            ?.takeIf(VALID_VALUE::matches)
            ?.let(::ExternalPlaybackLeaseId)
    }
}
