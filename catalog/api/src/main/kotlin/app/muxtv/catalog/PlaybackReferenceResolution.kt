package app.muxtv.catalog

import app.muxtv.player.PlaybackIntent
import app.muxtv.player.ResolvedPlaybackTimeline

/** Minimum persisted archive metadata carried to the provider/catalog resolution boundary. */
data class PlaybackCatchupMetadata(
    val mode: String?,
    val sourceTemplate: String?,
    val retentionDays: Int?,
    val correction: String?,
) {
    override fun toString(): String =
        "PlaybackCatchupMetadata(modePresent=${mode != null}, sourceTemplate=<redacted>, " +
            "retentionDays=$retentionDays, correctionPresent=${correction != null})"
}

enum class PlaybackCatchupUnavailableReason {
    OUTSIDE_RETENTION,
    UNSUPPORTED,
    INVALID_METADATA,
}

/**
 * Provider-neutral request for resolving a persisted playback identity into an ephemeral transport locator.
 * Controlled reference values are deliberately excluded from diagnostics.
 */
data class PlaybackReferenceRequest(
    val credentialRef: String,
    val playbackReference: String,
    val intent: PlaybackIntent? = null,
    val catchupMetadata: PlaybackCatchupMetadata? = null,
) {
    override fun toString(): String =
        "PlaybackReferenceRequest(credentialRefPresent=${credentialRef.isNotEmpty()}, " +
            "playbackReference=<redacted>, intentPresent=${intent != null}, " +
            "catchupMetadataPresent=${catchupMetadata != null})"
}

/**
 * Result of resolving a persisted playback reference before the existing playback access policy is applied.
 */
sealed interface PlaybackReferenceResolution {
    /** The reference is already a direct locator and should continue through the existing path unchanged. */
    data object Unhandled : PlaybackReferenceResolution

    /** The persisted provider reference is malformed or uses an unsupported provider namespace. */
    data object InvalidReference : PlaybackReferenceResolution

    /** The source no longer has the credential record required to resolve its provider reference. */
    data object CredentialNotFound : PlaybackReferenceResolution

    /** The credential record exists but cannot be decoded safely. */
    data object CredentialCorrupted : PlaybackReferenceResolution

    /** Secure credential storage is temporarily unavailable. */
    data object CredentialUnavailable : PlaybackReferenceResolution

    /** Provider-specific catch-up semantics could not produce a usable archive transport. */
    data class CatchupUnavailable(
        val reason: PlaybackCatchupUnavailableReason,
    ) : PlaybackReferenceResolution

    /** Cleartext provider access exists but has not been explicitly approved. */
    class ApprovalRequired(
        val displayOrigin: String,
    ) : PlaybackReferenceResolution {
        init {
            require(displayOrigin.isNotBlank())
        }

        override fun toString(): String =
            "PlaybackReferenceResolution.ApprovalRequired(displayOrigin=<redacted>)"
    }

    /**
     * Ephemeral provider-owned transport output. The locator can contain credentials and therefore
     * must never be logged, persisted, used as identity, or included in [toString].
     */
    class Ready(
        val locator: String,
        val insecureHttpPreapproved: Boolean,
    ) : PlaybackReferenceResolution {
        override fun toString(): String =
            "PlaybackReferenceResolution.Ready(locator=<redacted>, insecureHttpPreapproved=$insecureHttpPreapproved)"
    }

    /**
     * Archive transport materialized from an existing direct source. Unlike [Ready], this locator
     * remains subject to the credential-bound direct-source access policy before playback.
     */
    data class MaterializedDirect(
        val locator: String,
        val timeline: ResolvedPlaybackTimeline,
    ) : PlaybackReferenceResolution {
        override fun toString(): String =
            "PlaybackReferenceResolution.MaterializedDirect(locator=<redacted>, timeline=$timeline)"
    }
}

fun interface PlaybackReferenceResolver {
    suspend fun resolve(request: PlaybackReferenceRequest): PlaybackReferenceResolution
}

/** Safe default for existing direct M3U playback and database-only construction. */
object UnhandledPlaybackReferenceResolver : PlaybackReferenceResolver {
    override suspend fun resolve(request: PlaybackReferenceRequest): PlaybackReferenceResolution =
        PlaybackReferenceResolution.Unhandled
}
