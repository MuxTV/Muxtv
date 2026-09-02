package app.muxtv.player

class PlaybackStartRequest(
    val profileId: String,
    val intent: PlaybackIntent,
    val preferredVariantId: String? = null,
) {
    constructor(
        profileId: String,
        channelId: String,
        preferredVariantId: String? = null,
    ) : this(
        profileId = profileId,
        intent = PlaybackIntent.Live(channelId),
        preferredVariantId = preferredVariantId,
    )

    val channelId: String
        get() = intent.channelId

    init {
        require(profileId.isValidIdentity())
        require(preferredVariantId == null || preferredVariantId.isValidIdentity())
    }

    override fun equals(other: Any?): Boolean =
        other is PlaybackStartRequest &&
            profileId == other.profileId &&
            intent == other.intent &&
            preferredVariantId == other.preferredVariantId

    override fun hashCode(): Int {
        var result = profileId.hashCode()
        result = 31 * result + intent.hashCode()
        result = 31 * result + (preferredVariantId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PlaybackStartRequest(profileId=<redacted>, channelId=<redacted>, " +
            "intent=${intent.kindName()}, " +
            "preferredVariantId=${if (preferredVariantId == null) "null" else "<redacted>"})"
}

private fun PlaybackIntent.kindName(): String = when (this) {
    is PlaybackIntent.Live -> "Live"
    is PlaybackIntent.CatchupProgram -> "CatchupProgram"
    is PlaybackIntent.CatchupPosition -> "CatchupPosition"
}

private fun String.isValidIdentity(): Boolean =
    isNotBlank() && length <= 512 && !contains('\r') && !contains('\n')
