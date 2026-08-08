package app.muxtv.player.media3

import android.os.Bundle
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.muxtv.player.PlaybackStartFailure
import app.muxtv.player.PlaybackStartRequest
import app.muxtv.player.PlaybackStartResult

data class PlaybackSetupCommand(
    val id: PlaybackSetupId,
    val request: PlaybackStartRequest,
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
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_CHANNEL_ID = "channel_id"
    private const val KEY_PREFERRED_VARIANT_ID = "preferred_variant_id"
    private const val KEY_RESULT_KIND = "result_kind"
    private const val KEY_DISPLAY_ORIGIN = "display_origin"
    private const val KEY_VARIANT_ID = "variant_id"
    private const val KEY_FAILURE = "failure"

    val setPlaybackRequestCommand: SessionCommand
        get() = SessionCommand(ACTION_SET_PLAYBACK_REQUEST, Bundle.EMPTY)

    val cancelPlaybackSetupCommand: SessionCommand
        get() = SessionCommand(ACTION_CANCEL_PLAYBACK_SETUP, Bundle.EMPTY)

    fun setupArgs(
        id: PlaybackSetupId,
        request: PlaybackStartRequest,
    ): Bundle = Bundle().apply {
        putString(KEY_SETUP_ID, id.encoded())
        putBundle(
            KEY_REQUEST,
            Bundle().apply {
                putString(KEY_PROFILE_ID, request.profileId)
                putString(KEY_CHANNEL_ID, request.channelId)
                request.preferredVariantId?.let {
                    putString(KEY_PREFERRED_VARIANT_ID, it)
                }
            },
        )
    }

    fun cancelArgs(id: PlaybackSetupId): Bundle = Bundle().apply {
        putString(KEY_SETUP_ID, id.encoded())
    }

    fun parseSetupArgs(args: Bundle): PlaybackSetupCommand? {
        if (args.keySet() != setOf(KEY_SETUP_ID, KEY_REQUEST)) return null
        val id = PlaybackSetupId.parse(args.getString(KEY_SETUP_ID)) ?: return null
        val requestBundle = args.getBundle(KEY_REQUEST) ?: return null
        val allowedRequestKeys = setOf(
            KEY_PROFILE_ID,
            KEY_CHANNEL_ID,
            KEY_PREFERRED_VARIANT_ID,
        )
        if (!allowedRequestKeys.containsAll(requestBundle.keySet())) return null
        if (!requestBundle.keySet().containsAll(setOf(KEY_PROFILE_ID, KEY_CHANNEL_ID))) return null
        val request = runCatching {
            PlaybackStartRequest(
                profileId = requestBundle.getString(KEY_PROFILE_ID) ?: return null,
                channelId = requestBundle.getString(KEY_CHANNEL_ID) ?: return null,
                preferredVariantId = requestBundle.getString(KEY_PREFERRED_VARIANT_ID),
            )
        }.getOrNull() ?: return null
        return PlaybackSetupCommand(id = id, request = request)
    }

    fun parseCancelArgs(args: Bundle): PlaybackSetupId? {
        if (args.keySet() != setOf(KEY_SETUP_ID)) return null
        return PlaybackSetupId.parse(args.getString(KEY_SETUP_ID))
    }

    fun result(result: PlaybackStartResult): SessionResult = SessionResult(
        SessionResult.RESULT_SUCCESS,
        Bundle().apply {
            when (result) {
                PlaybackStartResult.Started -> putString(KEY_RESULT_KIND, "started")
                is PlaybackStartResult.InsecureHttpApprovalRequired -> {
                    putString(KEY_RESULT_KIND, "approval_required")
                    putString(KEY_DISPLAY_ORIGIN, result.displayOrigin)
                    putString(KEY_VARIANT_ID, result.variantId)
                }
                is PlaybackStartResult.Rejected -> {
                    putString(KEY_RESULT_KIND, "rejected")
                    putString(KEY_FAILURE, result.reason.name)
                }
            }
        },
    )

    fun parseResult(result: SessionResult): PlaybackStartResult? {
        if (result.resultCode != SessionResult.RESULT_SUCCESS) return null
        val extras = result.extras
        return when (extras.getString(KEY_RESULT_KIND)) {
            "started" -> PlaybackStartResult.Started.takeIf {
                extras.keySet() == setOf(KEY_RESULT_KIND)
            }
            "approval_required" -> {
                if (extras.keySet() != setOf(
                        KEY_RESULT_KIND,
                        KEY_DISPLAY_ORIGIN,
                        KEY_VARIANT_ID,
                    )
                ) return null
                runCatching {
                    PlaybackStartResult.InsecureHttpApprovalRequired(
                        displayOrigin = extras.getString(KEY_DISPLAY_ORIGIN) ?: return null,
                        variantId = extras.getString(KEY_VARIANT_ID) ?: return null,
                    )
                }.getOrNull()
            }
            "rejected" -> {
                if (extras.keySet() != setOf(KEY_RESULT_KIND, KEY_FAILURE)) return null
                val failure = runCatching {
                    PlaybackStartFailure.valueOf(extras.getString(KEY_FAILURE) ?: return null)
                }.getOrNull() ?: return null
                PlaybackStartResult.Rejected(failure)
            }
            else -> null
        }
    }

    /*
     * Media3 1.10.1 documents INFO_CANCELLED as a valid SessionResult code, but its
     * API 26 Binder round-trip reconstructs positive non-success codes as SessionError
     * and rejects the bundle. A cancelled setup is therefore represented by the stable
     * negative ERROR_INVALID_STATE code; coroutine cancellation still propagates as
     * CancellationException before this protocol boundary.
     */
    fun cancelled(): SessionResult = SessionResult(SessionError.ERROR_INVALID_STATE)

    fun badValue(): SessionResult = SessionResult(SessionError.ERROR_BAD_VALUE)

    fun permissionDenied(): SessionResult =
        SessionResult(SessionError.ERROR_PERMISSION_DENIED)

    fun notSupported(): SessionResult =
        SessionResult(SessionError.ERROR_NOT_SUPPORTED)
}
