package app.muxtv.player

enum class PlaybackErrorCode(val externalName: String) {
    NETWORK_TIMEOUT("network_timeout"),
    NETWORK_UNREACHABLE("network_unreachable"),
    HTTP_REJECTED("http_rejected"),
    UNSUPPORTED_FORMAT("unsupported_format"),
    DECODER_FAILED("decoder_failed"),
    UNKNOWN("unknown"),
}

data class PlaybackError(
    val code: PlaybackErrorCode,
    val message: String,
    val retryable: Boolean,
)
