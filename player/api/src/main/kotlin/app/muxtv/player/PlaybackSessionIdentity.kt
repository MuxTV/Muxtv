package app.muxtv.player

/**
 * Secret-safe public identity of the playback session currently owned by the service.
 *
 * Catalog identity carries profile/channel resolution keys. External identity carries only an
 * opaque random session id: no host, path, query, torrent hash or file index ever appears.
 */
sealed interface PlaybackSessionIdentity {
    data class Catalog(
        val profileId: String,
        val channelId: String,
    ) : PlaybackSessionIdentity {
        init {
            require(profileId.isValidIdentity())
            require(channelId.isValidIdentity())
        }

        override fun toString(): String =
            "PlaybackSessionIdentity.Catalog(profileId=<redacted>, channelId=<redacted>)"
    }

    data class External(
        val sessionId: String,
    ) : PlaybackSessionIdentity {
        init {
            require(sessionId.isValidIdentity())
        }

        override fun toString(): String =
            "PlaybackSessionIdentity.External(sessionId=<redacted>)"
    }
}

private fun String.isValidIdentity(): Boolean =
    isNotBlank() && length <= 512 && !contains('\r') && !contains('\n')
