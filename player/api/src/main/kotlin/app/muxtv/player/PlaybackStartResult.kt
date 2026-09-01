package app.muxtv.player

sealed interface PlaybackStartResult {
    data object Started : PlaybackStartResult

    data class InsecureHttpApprovalRequired(
        val displayOrigin: String,
        val variantId: String,
    ) : PlaybackStartResult {
        init {
            require(displayOrigin.isNotBlank())
            require(variantId.isNotBlank())
        }

        override fun toString(): String =
            "InsecureHttpApprovalRequired(displayOrigin=<redacted>, variantId=<redacted>)"
    }

    data class LocalNetworkPermissionRequired(
        val variantId: String,
    ) : PlaybackStartResult {
        init {
            require(variantId.isNotBlank())
        }

        override fun toString(): String =
            "LocalNetworkPermissionRequired(variantId=<redacted>)"
    }

    data class Rejected(
        val reason: PlaybackStartFailure,
        val observationAvailable: Boolean = false,
    ) : PlaybackStartResult
}

enum class PlaybackStartFailure {
    ChannelUnavailable,
    AccessUnavailable,
    RecoveryExhausted,
    CommandFailed,
}
