package app.muxtv.player.media3

import android.os.Bundle
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

data class PlaybackSetupCommand(
    val id: PlaybackSetupId,
    val request: PlaybackSessionRequest,
) {
    override fun toString(): String =
        "PlaybackSetupCommand(id=<redacted>, request=$request)"
}

@AndroidXOptIn(UnstableApi::class)
object MuxTvPlaybackSessionContract {
    const val ACTION_SET_PLAYBACK_REQUEST =
        "app.muxtv.player.media3.action.SET_PLAYBACK_REQUEST"
    const val ACTION_CANCEL_PLAYBACK_SETUP =
        "app.muxtv.player.media3.action.CANCEL_PLAYBACK_SETUP"

    private const val KEY_SETUP_ID = "setup_id"
    private const val KEY_REQUEST = "request"

    val setPlaybackRequestCommand: SessionCommand
        get() = SessionCommand(ACTION_SET_PLAYBACK_REQUEST, Bundle.EMPTY)

    val cancelPlaybackSetupCommand: SessionCommand
        get() = SessionCommand(ACTION_CANCEL_PLAYBACK_SETUP, Bundle.EMPTY)

    fun setupArgs(
        id: PlaybackSetupId,
        request: PlaybackSessionRequest,
    ): Bundle = Bundle().apply {
        putString(KEY_SETUP_ID, id.encoded())
        putBundle(KEY_REQUEST, request.toBundle())
    }

    fun cancelArgs(id: PlaybackSetupId): Bundle = Bundle().apply {
        putString(KEY_SETUP_ID, id.encoded())
    }

    fun parseSetupArgs(args: Bundle): PlaybackSetupCommand? {
        val id = PlaybackSetupId.parse(args.getString(KEY_SETUP_ID)) ?: return null
        val requestBundle = args.getBundle(KEY_REQUEST) ?: return null
        val request = PlaybackSessionRequest.fromBundle(requestBundle) ?: return null
        return PlaybackSetupCommand(id = id, request = request)
    }

    fun parseCancelArgs(args: Bundle): PlaybackSetupId? =
        PlaybackSetupId.parse(args.getString(KEY_SETUP_ID))

    fun success(): SessionResult = SessionResult(SessionResult.RESULT_SUCCESS)

    fun cancelled(): SessionResult = SessionResult(SessionError.ERROR_INVALID_STATE)

    fun badValue(): SessionResult = SessionResult(SessionError.ERROR_BAD_VALUE)

    fun permissionDenied(): SessionResult =
        SessionResult(SessionError.ERROR_PERMISSION_DENIED)

    fun notSupported(): SessionResult =
        SessionResult(SessionError.ERROR_NOT_SUPPORTED)
}
