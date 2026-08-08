package app.muxtv.player

class PlaybackStartRequest(
    val profileId: String,
    val channelId: String,
    val preferredVariantId: String? = null,
) {
    init {
        require(profileId.isValidIdentity())
        require(channelId.isValidIdentity())
        require(preferredVariantId == null || preferredVariantId.isValidIdentity())
    }

    override fun equals(other: Any?): Boolean =
        other is PlaybackStartRequest &&
            profileId == other.profileId &&
            channelId == other.channelId &&
            preferredVariantId == other.preferredVariantId

    override fun hashCode(): Int {
        var result = profileId.hashCode()
        result = 31 * result + channelId.hashCode()
        result = 31 * result + (preferredVariantId?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "PlaybackStartRequest(profileId=<redacted>, channelId=<redacted>, " +
            "preferredVariantId=${if (preferredVariantId == null) "null" else "<redacted>"})"
}

private fun String.isValidIdentity(): Boolean =
    isNotBlank() && length <= 512 && !contains('\r') && !contains('\n')
