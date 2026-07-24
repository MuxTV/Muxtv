package app.muxtv.player.media3

import android.os.Bundle
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult

object MuxTvPlaybackSessionContract {
    const val ACTION_SET_PLAYBACK_REQUEST =
        "app.muxtv.player.media3.action.SET_PLAYBACK_REQUEST"

    val setPlaybackRequestCommand: SessionCommand
        get() = SessionCommand(ACTION_SET_PLAYBACK_REQUEST, Bundle.EMPTY)

    fun success(): SessionResult = SessionResult(SessionResult.RESULT_SUCCESS)

    fun badValue(): SessionResult = SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)

    fun permissionDenied(): SessionResult =
        SessionResult(SessionResult.RESULT_ERROR_PERMISSION_DENIED)

    fun notSupported(): SessionResult =
        SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
}
