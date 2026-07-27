package app.muxtv.player.media3

import java.util.UUID

@JvmInline
value class PlaybackSetupId private constructor(
    private val value: String,
) {
    fun encoded(): String = value

    override fun toString(): String = "PlaybackSetupId(<redacted>)"

    companion object {
        private const val MAX_LENGTH = 64
        private val VALID_VALUE = Regex("[A-Za-z0-9-]{1,$MAX_LENGTH}")

        fun create(): PlaybackSetupId = PlaybackSetupId(UUID.randomUUID().toString())

        fun parse(raw: String?): PlaybackSetupId? = raw
            ?.takeIf(VALID_VALUE::matches)
            ?.let(::PlaybackSetupId)
    }
}
