package app.muxtv.player.media3

import androidx.media3.session.MediaController

/**
 * Sends one semantic seek request to the service authority.
 *
 * A `null` result means the Media3/session transport did not return a valid typed success payload;
 * policy rejection remains represented by [PlaybackSeekResult.Rejected].
 */
suspend fun MediaController.awaitPlaybackSeek(
    request: PlaybackSeekRequest,
    timeoutMillis: Long,
): PlaybackSeekResult? {
    val sessionResult = sendCustomCommand(
        MuxTvPlaybackSessionContract.seekCommand,
        MuxTvPlaybackSessionContract.seekArgs(request),
    ).awaitCancellable(
        timeoutMillis = timeoutMillis,
        cancelFutureOnCancellation = false,
    )
    return MuxTvPlaybackSessionContract.parseSeekResult(sessionResult)
}
