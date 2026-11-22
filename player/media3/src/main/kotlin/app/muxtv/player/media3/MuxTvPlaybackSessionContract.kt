package app.muxtv.player.media3

import android.os.Bundle
import androidx.annotation.OptIn as AndroidXOptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import app.muxtv.player.PlaybackIntent
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
    const val ACTION_REQUEST_SEEK =
        "app.muxtv.player.media3.action.REQUEST_SEEK"

    private const val KEY_SETUP_ID = "setup_id"
    private const val KEY_REQUEST = "request"
    private const val KEY_PROFILE_ID = "profile_id"
    private const val KEY_CHANNEL_ID = "channel_id"
    private const val KEY_PREFERRED_VARIANT_ID = "preferred_variant_id"
    private const val KEY_INTENT_KIND = "intent_kind"
    private const val KEY_PROGRAMME_ID = "programme_id"
    private const val KEY_PROGRAMME_START_EPOCH_MILLIS = "programme_start_epoch_millis"
    private const val KEY_PROGRAMME_END_EPOCH_MILLIS = "programme_end_epoch_millis"
    private const val KEY_POSITION_EPOCH_MILLIS = "position_epoch_millis"
    private const val KEY_RESULT_KIND = "result_kind"
    private const val KEY_DISPLAY_ORIGIN = "display_origin"
    private const val KEY_VARIANT_ID = "variant_id"
    private const val KEY_FAILURE = "failure"
    private const val KEY_OBSERVATION_AVAILABLE = "observation_available"

    private const val KEY_SEEK_KIND = "seek_kind"
    private const val KEY_SEEK_MEDIA_ID = "seek_media_id"
    private const val KEY_SEEK_GENERATION = "seek_generation"
    private const val KEY_SEEK_DIRECTION = "seek_direction"
    private const val KEY_SEEK_TARGET_MS = "seek_target_ms"
    private const val KEY_SEEK_REJECT_REASON = "seek_reject_reason"

    private const val INTENT_KIND_CATCHUP_PROGRAM = "catchup_program"
    private const val INTENT_KIND_CATCHUP_POSITION = "catchup_position"
    private const val RESULT_KIND_STARTED = "started"
    private const val RESULT_KIND_APPROVAL_REQUIRED = "approval_required"
    private const val RESULT_KIND_LOCAL_NETWORK_PERMISSION_REQUIRED =
        "local_network_permission_required"
    private const val RESULT_KIND_REJECTED = "rejected"
    private const val SEEK_KIND_RELATIVE = "relative"
    private const val SEEK_KIND_ABSOLUTE = "absolute"
    private const val RESULT_KIND_SEEK_ACCEPTED = "seek_accepted"
    private const val RESULT_KIND_SEEK_REJECTED = "seek_rejected"

    val setPlaybackRequestCommand: SessionCommand
        get() = SessionCommand(ACTION_SET_PLAYBACK_REQUEST, Bundle.EMPTY)

    val cancelPlaybackSetupCommand: SessionCommand
        get() = SessionCommand(ACTION_CANCEL_PLAYBACK_SETUP, Bundle.EMPTY)

    val seekCommand: SessionCommand
        get() = SessionCommand(ACTION_REQUEST_SEEK, Bundle.EMPTY)

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
                when (val intent = request.intent) {
                    is PlaybackIntent.Live -> Unit
                    is PlaybackIntent.CatchupProgram -> {
                        putString(KEY_INTENT_KIND, INTENT_KIND_CATCHUP_PROGRAM)
                        putString(KEY_PROGRAMME_ID, intent.programmeId)
                        putLong(KEY_PROGRAMME_START_EPOCH_MILLIS, intent.startEpochMillis)
                        putLong(KEY_PROGRAMME_END_EPOCH_MILLIS, intent.endEpochMillis)
                    }
                    is PlaybackIntent.CatchupPosition -> {
                        putString(KEY_INTENT_KIND, INTENT_KIND_CATCHUP_POSITION)
                        putLong(KEY_POSITION_EPOCH_MILLIS, intent.positionEpochMillis)
                    }
                }
            },
        )
    }

    fun cancelArgs(id: PlaybackSetupId): Bundle = Bundle().apply {
        putString(KEY_SETUP_ID, id.encoded())
    }

    fun seekArgs(request: PlaybackSeekRequest): Bundle = Bundle().apply {
        putString(KEY_SEEK_MEDIA_ID, request.token.mediaId)
        putLong(KEY_SEEK_GENERATION, request.token.generation)
        when (request) {
            is PlaybackSeekRequest.Relative -> {
                putString(KEY_SEEK_KIND, SEEK_KIND_RELATIVE)
                putInt(KEY_SEEK_DIRECTION, request.direction)
            }
            is PlaybackSeekRequest.Absolute -> {
                putString(KEY_SEEK_KIND, SEEK_KIND_ABSOLUTE)
                putLong(KEY_SEEK_TARGET_MS, request.targetMs)
            }
        }
    }

    fun parseSetupArgs(args: Bundle): PlaybackSetupCommand? {
        if (args.keySet() != setOf(KEY_SETUP_ID, KEY_REQUEST)) return null
        val id = PlaybackSetupId.parse(args.getString(KEY_SETUP_ID)) ?: return null
        val requestBundle = args.getBundle(KEY_REQUEST) ?: return null
        val profileId = requestBundle.getString(KEY_PROFILE_ID) ?: return null
        val channelId = requestBundle.getString(KEY_CHANNEL_ID) ?: return null
        val preferredVariantId = requestBundle.getString(KEY_PREFERRED_VARIANT_ID)
        val intent = parsePlaybackIntent(requestBundle, channelId) ?: return null
        val request = runCatching {
            PlaybackStartRequest(
                profileId = profileId,
                intent = intent,
                preferredVariantId = preferredVariantId,
            )
        }.getOrNull() ?: return null
        return PlaybackSetupCommand(id = id, request = request)
    }

    private fun parsePlaybackIntent(
        requestBundle: Bundle,
        channelId: String,
    ): PlaybackIntent? {
        return when (requestBundle.getString(KEY_INTENT_KIND)) {
            null -> {
                if (!hasExactRequestKeys(requestBundle, LIVE_REQUIRED_KEYS, LIVE_OPTIONAL_KEYS)) {
                    return null
                }
                runCatching { PlaybackIntent.Live(channelId) }.getOrNull()
            }
            INTENT_KIND_CATCHUP_PROGRAM -> {
                if (!hasExactRequestKeys(
                        requestBundle,
                        CATCHUP_PROGRAM_REQUIRED_KEYS,
                        CATCHUP_OPTIONAL_KEYS,
                    )
                ) return null
                runCatching {
                    PlaybackIntent.CatchupProgram(
                        channelId = channelId,
                        programmeId = requestBundle.getString(KEY_PROGRAMME_ID) ?: return null,
                        startEpochMillis = requestBundle.getLong(KEY_PROGRAMME_START_EPOCH_MILLIS),
                        endEpochMillis = requestBundle.getLong(KEY_PROGRAMME_END_EPOCH_MILLIS),
                    )
                }.getOrNull()
            }
            INTENT_KIND_CATCHUP_POSITION -> {
                if (!hasExactRequestKeys(
                        requestBundle,
                        CATCHUP_POSITION_REQUIRED_KEYS,
                        CATCHUP_OPTIONAL_KEYS,
                    )
                ) return null
                runCatching {
                    PlaybackIntent.CatchupPosition(
                        channelId = channelId,
                        positionEpochMillis = requestBundle.getLong(KEY_POSITION_EPOCH_MILLIS),
                    )
                }.getOrNull()
            }
            else -> null
        }
    }

    private fun hasExactRequestKeys(
        bundle: Bundle,
        required: Set<String>,
        optional: Set<String>,
    ): Boolean {
        val keys = bundle.keySet()
        return keys.containsAll(required) && (required + optional).containsAll(keys)
    }

    fun parseCancelArgs(args: Bundle): PlaybackSetupId? {
        if (args.keySet() != setOf(KEY_SETUP_ID)) return null
        return PlaybackSetupId.parse(args.getString(KEY_SETUP_ID))
    }

    fun parseSeekArgs(args: Bundle): PlaybackSeekRequest? {
        val kind = args.getString(KEY_SEEK_KIND) ?: return null
        val token = runCatching {
            PlaybackSeekToken(
                mediaId = args.getString(KEY_SEEK_MEDIA_ID) ?: return null,
                generation = args.getLong(KEY_SEEK_GENERATION, Long.MIN_VALUE),
            )
        }.getOrNull() ?: return null

        return when (kind) {
            SEEK_KIND_RELATIVE -> {
                if (args.keySet() != setOf(
                        KEY_SEEK_KIND,
                        KEY_SEEK_MEDIA_ID,
                        KEY_SEEK_GENERATION,
                        KEY_SEEK_DIRECTION,
                    )
                ) return null
                runCatching {
                    PlaybackSeekRequest.Relative(
                        token = token,
                        direction = args.getInt(KEY_SEEK_DIRECTION, Int.MIN_VALUE),
                    )
                }.getOrNull()
            }
            SEEK_KIND_ABSOLUTE -> {
                if (args.keySet() != setOf(
                        KEY_SEEK_KIND,
                        KEY_SEEK_MEDIA_ID,
                        KEY_SEEK_GENERATION,
                        KEY_SEEK_TARGET_MS,
                    )
                ) return null
                runCatching {
                    PlaybackSeekRequest.Absolute(
                        token = token,
                        targetMs = args.getLong(KEY_SEEK_TARGET_MS, Long.MIN_VALUE),
                    )
                }.getOrNull()
            }
            else -> null
        }
    }

    fun result(result: PlaybackStartResult): SessionResult = SessionResult(
        SessionResult.RESULT_SUCCESS,
        Bundle().apply {
            when (result) {
                PlaybackStartResult.Started -> putString(KEY_RESULT_KIND, RESULT_KIND_STARTED)
                is PlaybackStartResult.InsecureHttpApprovalRequired -> {
                    putString(KEY_RESULT_KIND, RESULT_KIND_APPROVAL_REQUIRED)
                    putString(KEY_DISPLAY_ORIGIN, result.displayOrigin)
                    putString(KEY_VARIANT_ID, result.variantId)
                }
                is PlaybackStartResult.LocalNetworkPermissionRequired -> {
                    putString(KEY_RESULT_KIND, RESULT_KIND_LOCAL_NETWORK_PERMISSION_REQUIRED)
                    putString(KEY_VARIANT_ID, result.variantId)
                }
                is PlaybackStartResult.Rejected -> {
                    putString(KEY_RESULT_KIND, RESULT_KIND_REJECTED)
                    putString(KEY_FAILURE, result.reason.name)
                    putBoolean(KEY_OBSERVATION_AVAILABLE, result.observationAvailable)
                }
            }
        },
    )

    fun seekSessionResult(result: PlaybackSeekResult): SessionResult = SessionResult(
        SessionResult.RESULT_SUCCESS,
        Bundle().apply {
            when (result) {
                is PlaybackSeekResult.Accepted -> {
                    putString(KEY_RESULT_KIND, RESULT_KIND_SEEK_ACCEPTED)
                    putLong(KEY_SEEK_TARGET_MS, result.targetMs)
                    putInt(KEY_SEEK_DIRECTION, result.direction)
                }
                is PlaybackSeekResult.Rejected -> {
                    putString(KEY_RESULT_KIND, RESULT_KIND_SEEK_REJECTED)
                    putString(KEY_SEEK_REJECT_REASON, result.reason.name)
                }
            }
        },
    )

    fun parseResult(result: SessionResult): PlaybackStartResult? {
        if (result.resultCode != SessionResult.RESULT_SUCCESS) return null
        val extras = result.extras
        return when (extras.getString(KEY_RESULT_KIND)) {
            RESULT_KIND_STARTED -> PlaybackStartResult.Started.takeIf {
                extras.keySet() == setOf(KEY_RESULT_KIND)
            }
            RESULT_KIND_APPROVAL_REQUIRED -> {
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
            RESULT_KIND_LOCAL_NETWORK_PERMISSION_REQUIRED -> {
                if (extras.keySet() != setOf(KEY_RESULT_KIND, KEY_VARIANT_ID)) return null
                runCatching {
                    PlaybackStartResult.LocalNetworkPermissionRequired(
                        variantId = extras.getString(KEY_VARIANT_ID) ?: return null,
                    )
                }.getOrNull()
            }
            RESULT_KIND_REJECTED -> {
                if (extras.keySet() != setOf(
                        KEY_RESULT_KIND,
                        KEY_FAILURE,
                        KEY_OBSERVATION_AVAILABLE,
                    )
                ) return null
                val failure = runCatching {
                    PlaybackStartFailure.valueOf(extras.getString(KEY_FAILURE) ?: return null)
                }.getOrNull() ?: return null
                PlaybackStartResult.Rejected(
                    reason = failure,
                    observationAvailable = extras.getBoolean(KEY_OBSERVATION_AVAILABLE),
                )
            }
            else -> null
        }
    }

    fun parseSeekResult(result: SessionResult): PlaybackSeekResult? {
        if (result.resultCode != SessionResult.RESULT_SUCCESS) return null
        val extras = result.extras
        return when (extras.getString(KEY_RESULT_KIND)) {
            RESULT_KIND_SEEK_ACCEPTED -> {
                if (extras.keySet() != setOf(
                        KEY_RESULT_KIND,
                        KEY_SEEK_TARGET_MS,
                        KEY_SEEK_DIRECTION,
                    )
                ) return null
                runCatching {
                    PlaybackSeekResult.Accepted(
                        targetMs = extras.getLong(KEY_SEEK_TARGET_MS, Long.MIN_VALUE),
                        direction = extras.getInt(KEY_SEEK_DIRECTION, Int.MIN_VALUE),
                    )
                }.getOrNull()
            }
            RESULT_KIND_SEEK_REJECTED -> {
                if (extras.keySet() != setOf(KEY_RESULT_KIND, KEY_SEEK_REJECT_REASON)) return null
                val reason = runCatching {
                    PlaybackSeekRejectReason.valueOf(
                        extras.getString(KEY_SEEK_REJECT_REASON) ?: return null,
                    )
                }.getOrNull() ?: return null
                PlaybackSeekResult.Rejected(reason)
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

    private val LIVE_REQUIRED_KEYS = setOf(KEY_PROFILE_ID, KEY_CHANNEL_ID)
    private val LIVE_OPTIONAL_KEYS = setOf(KEY_PREFERRED_VARIANT_ID)
    private val CATCHUP_OPTIONAL_KEYS = setOf(KEY_PREFERRED_VARIANT_ID)
    private val CATCHUP_PROGRAM_REQUIRED_KEYS = setOf(
        KEY_PROFILE_ID,
        KEY_CHANNEL_ID,
        KEY_INTENT_KIND,
        KEY_PROGRAMME_ID,
        KEY_PROGRAMME_START_EPOCH_MILLIS,
        KEY_PROGRAMME_END_EPOCH_MILLIS,
    )
    private val CATCHUP_POSITION_REQUIRED_KEYS = setOf(
        KEY_PROFILE_ID,
        KEY_CHANNEL_ID,
        KEY_INTENT_KIND,
        KEY_POSITION_EPOCH_MILLIS,
    )
}
