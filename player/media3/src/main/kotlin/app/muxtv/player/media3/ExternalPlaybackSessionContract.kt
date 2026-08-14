package app.muxtv.player.media3

import android.os.Bundle
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.muxtv.player.ExternalPlaybackLeaseId
import app.muxtv.player.ExternalPlaybackStartFailure
import app.muxtv.player.ExternalPlaybackStartResult

data class ExternalPlaybackSetupCommand(
    val id: PlaybackSetupId,
    val leaseId: ExternalPlaybackLeaseId,
) {
    override fun toString(): String =
        "ExternalPlaybackSetupCommand(id=<redacted>, leaseId=<redacted>)"
}

/**
 * MediaSession contract for external playback setups.
 *
 * The command bundle carries only an opaque lease id. The URI, MIME type, title and all other
 * media data stay process-local inside the lease registry and are claimed directly by the
 * service after the standard same-package controller check.
 */
@AndroidXOptIn(UnstableApi::class)
object ExternalPlaybackSessionContract {
    const val ACTION_SET_EXTERNAL_PLAYBACK_REQUEST =
        "app.muxtv.player.media3.action.SET_EXTERNAL_PLAYBACK_REQUEST"

    private const val KEY_SETUP_ID = "setup_id"
    private const val KEY_LEASE_ID = "lease_id"
    private const val KEY_RESULT_KIND = "result_kind"
    private const val KEY_FAILURE = "failure"
    private const val KEY_OBSERVATION_AVAILABLE = "observation_available"

    val setExternalPlaybackRequestCommand: SessionCommand
        get() = SessionCommand(ACTION_SET_EXTERNAL_PLAYBACK_REQUEST, Bundle.EMPTY)

    fun setupArgs(
        id: PlaybackSetupId,
        leaseId: ExternalPlaybackLeaseId,
    ): Bundle = Bundle().apply {
        putString(KEY_SETUP_ID, id.encoded())
        putString(KEY_LEASE_ID, leaseId.encoded())
    }

    fun parseSetupArgs(args: Bundle): ExternalPlaybackSetupCommand? {
        if (args.keySet() != setOf(KEY_SETUP_ID, KEY_LEASE_ID)) return null
        val id = PlaybackSetupId.parse(args.getString(KEY_SETUP_ID)) ?: return null
        val leaseId = ExternalPlaybackLeaseId.parse(args.getString(KEY_LEASE_ID)) ?: return null
        return ExternalPlaybackSetupCommand(id = id, leaseId = leaseId)
    }

    fun result(result: ExternalPlaybackStartResult): SessionResult = SessionResult(
        SessionResult.RESULT_SUCCESS,
        Bundle().apply {
            when (result) {
                ExternalPlaybackStartResult.Started ->
                    putString(KEY_RESULT_KIND, "started")

                is ExternalPlaybackStartResult.Rejected -> {
                    putString(KEY_RESULT_KIND, "rejected")
                    putString(KEY_FAILURE, result.reason.name)
                    putBoolean(KEY_OBSERVATION_AVAILABLE, result.observationAvailable)
                }
            }
        },
    )

    fun parseResult(result: SessionResult): ExternalPlaybackStartResult? {
        if (result.resultCode != SessionResult.RESULT_SUCCESS) return null
        val extras = result.extras
        return when (extras.getString(KEY_RESULT_KIND)) {
            "started" -> ExternalPlaybackStartResult.Started.takeIf {
                extras.keySet() == setOf(KEY_RESULT_KIND)
            }
            "rejected" -> {
                if (extras.keySet() != setOf(
                        KEY_RESULT_KIND,
                        KEY_FAILURE,
                        KEY_OBSERVATION_AVAILABLE,
                    )
                ) return null
                val failure = runCatching {
                    ExternalPlaybackStartFailure.valueOf(
                        extras.getString(KEY_FAILURE) ?: return null,
                    )
                }.getOrNull() ?: return null
                ExternalPlaybackStartResult.Rejected(
                    reason = failure,
                    observationAvailable = extras.getBoolean(KEY_OBSERVATION_AVAILABLE),
                )
            }
            else -> null
        }
    }

    fun cancelled(): SessionResult = SessionResult(SessionError.ERROR_INVALID_STATE)

    fun badValue(): SessionResult = SessionResult(SessionError.ERROR_BAD_VALUE)

    fun permissionDenied(): SessionResult =
        SessionResult(SessionError.ERROR_PERMISSION_DENIED)
}
