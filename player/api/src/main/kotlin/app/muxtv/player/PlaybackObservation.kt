package app.muxtv.player

enum class PlaybackFailureCategory {
    DNS,
    TLS,
    HTTP_RESPONSE,
    REDIRECT_POLICY,
    TIMEOUT,
    NETWORK_UNREACHABLE,
    MANIFEST_FORMAT,
    CODEC_DECODER,
    PLAYER_RENDER,
    CREDENTIAL_ACCESS,
    UNKNOWN,
}

enum class PlaybackObservationKind {
    ATTEMPT_STARTED,
    ATTEMPT_FAILED,
    RECOVERY_SUCCEEDED,
    RECOVERY_FAILED,
    APPROVAL_REQUIRED,
    EXTERNAL_INTENT_ACCEPTED,
    EXTERNAL_INTENT_REJECTED,
    EXTERNAL_SETUP_STARTED,
    EXTERNAL_FIRST_FRAME,
    EXTERNAL_PLAYBACK_FAILED,
}

data class PlaybackObservation(
    val kind: PlaybackObservationKind,
    val failureCategory: PlaybackFailureCategory? = null,
    val attemptNumber: Int,
    val attemptLimit: Int,
    val timestampEpochMillis: Long,
    val httpStatusCode: Int? = null,
    val media3ErrorCode: Int? = null,
) {
    init {
        require(attemptLimit > 0)
        require(attemptNumber in 0..attemptLimit)
        require(timestampEpochMillis >= 0L)
        require(httpStatusCode == null || httpStatusCode in 100..599)
        val failureKind = kind == PlaybackObservationKind.ATTEMPT_FAILED ||
            kind == PlaybackObservationKind.RECOVERY_FAILED ||
            kind == PlaybackObservationKind.EXTERNAL_PLAYBACK_FAILED
        require(failureKind == (failureCategory != null))
        require(httpStatusCode == null || failureCategory == PlaybackFailureCategory.HTTP_RESPONSE)
    }
}

fun interface PlaybackObservationRecorder {
    fun record(observation: PlaybackObservation)
}

fun interface PlaybackObservationReader {
    fun snapshot(): List<PlaybackObservation>
}
