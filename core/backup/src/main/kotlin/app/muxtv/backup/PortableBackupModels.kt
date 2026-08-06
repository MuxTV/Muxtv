package app.muxtv.backup

object PortableBackupLimits {
    const val MAX_DOCUMENT_BYTES: Int = 2 * 1024 * 1024
    const val MAX_PROFILES: Int = 16
    const val MAX_SOURCES: Int = 128
    const val MAX_CHANNEL_OVERLAYS: Int = 5_000
    const val MAX_RECENT_CHANNELS: Int = 800
    const val MAX_RECENT_PER_PROFILE: Int = 50
    const val MAX_ID_CHARACTERS: Int = 128
    const val MAX_DISPLAY_NAME_CHARACTERS: Int = 160
}

enum class PortableSourceRecoveryState {
    REAUTH_REQUIRED,
}

enum class PortableBackupRejectReason {
    OVERSIZED,
    MALFORMED,
    UNKNOWN_FIELD,
    UNSUPPORTED_VERSION,
    INTEGRITY_MISMATCH,
    LIMIT_EXCEEDED,
    INVALID_DATA,
    DUPLICATE_IDENTITY,
}

sealed interface PortableBackupDecodeResult {
    data class Success(
        val document: PortableBackupDocument,
    ) : PortableBackupDecodeResult

    data class Rejected(
        val reason: PortableBackupRejectReason,
    ) : PortableBackupDecodeResult
}

data class PortableBackupProfile(
    val id: String,
    val name: String,
    val isPrimary: Boolean,
    val archivedAtEpochMillis: Long? = null,
) {
    init {
        requirePortableId(id)
        requirePortableName(name)
        if (archivedAtEpochMillis != null && archivedAtEpochMillis < 0L) {
            invalidData()
        }
    }

    override fun toString(): String =
        "PortableBackupProfile(isPrimary=$isPrimary, archived=${archivedAtEpochMillis != null})"
}

data class PortableBackupSource(
    val id: String,
    val name: String,
    val recoveryState: PortableSourceRecoveryState = PortableSourceRecoveryState.REAUTH_REQUIRED,
) {
    init {
        requirePortableId(id)
        requirePortableName(name)
        if (recoveryState != PortableSourceRecoveryState.REAUTH_REQUIRED) {
            invalidData()
        }
    }

    override fun toString(): String =
        "PortableBackupSource(recoveryState=$recoveryState)"
}

data class PortableChannelOverlay(
    val profileId: String,
    val canonicalChannelId: String,
    val isFavorite: Boolean = false,
    val customName: String? = null,
    val channelNumber: Int? = null,
    val isHidden: Boolean = false,
) {
    init {
        requirePortableId(profileId)
        requirePortableId(canonicalChannelId)
        if (customName != null) {
            requirePortableName(customName)
        }
        if (channelNumber != null && channelNumber < 0) {
            invalidData()
        }
    }

    override fun toString(): String =
        "PortableChannelOverlay(isFavorite=$isFavorite, hasCustomName=${customName != null}, " +
            "hasChannelNumber=${channelNumber != null}, isHidden=$isHidden)"
}

data class PortableRecentChannel(
    val profileId: String,
    val canonicalChannelId: String,
    val lastSuccessfulPlaybackAtEpochMillis: Long,
) {
    init {
        requirePortableId(profileId)
        requirePortableId(canonicalChannelId)
        if (lastSuccessfulPlaybackAtEpochMillis < 0L) {
            invalidData()
        }
    }

    override fun toString(): String =
        "PortableRecentChannel(lastSuccessfulPlaybackAtEpochMillis=$lastSuccessfulPlaybackAtEpochMillis)"
}

data class PortableBackupPayload(
    val profiles: List<PortableBackupProfile>,
    val sources: List<PortableBackupSource>,
    val channelOverlays: List<PortableChannelOverlay>,
    val recentChannels: List<PortableRecentChannel>,
) {
    init {
        requireCountWithin(profiles.size, PortableBackupLimits.MAX_PROFILES)
        requireCountWithin(sources.size, PortableBackupLimits.MAX_SOURCES)
        requireCountWithin(channelOverlays.size, PortableBackupLimits.MAX_CHANNEL_OVERLAYS)
        requireCountWithin(recentChannels.size, PortableBackupLimits.MAX_RECENT_CHANNELS)

        requireUnique(profiles.map(PortableBackupProfile::id))
        requireUnique(sources.map(PortableBackupSource::id))
        requireUnique(channelOverlays.map { it.profileId to it.canonicalChannelId })
        requireUnique(recentChannels.map { it.profileId to it.canonicalChannelId })

        val profileIds = profiles.mapTo(mutableSetOf(), PortableBackupProfile::id)
        if (channelOverlays.any { it.profileId !in profileIds }) {
            invalidData()
        }
        if (recentChannels.any { it.profileId !in profileIds }) {
            invalidData()
        }
        if (profiles.count { it.isPrimary && it.archivedAtEpochMillis == null } > 1) {
            invalidData()
        }
        if (recentChannels.groupingBy(PortableRecentChannel::profileId).eachCount()
                .any { (_, count) -> count > PortableBackupLimits.MAX_RECENT_PER_PROFILE }
        ) {
            limitExceeded()
        }
    }

    override fun toString(): String =
        "PortableBackupPayload(profileCount=${profiles.size}, sourceCount=${sources.size}, " +
            "overlayCount=${channelOverlays.size}, recentCount=${recentChannels.size})"
}

data class PortableBackupSnapshot(
    val createdAtEpochMillis: Long,
    val dataSchemaVersion: Int,
    val payload: PortableBackupPayload,
) {
    init {
        if (createdAtEpochMillis < 0L || dataSchemaVersion <= 0) {
            invalidData()
        }
    }

    override fun toString(): String =
        "PortableBackupSnapshot(createdAtEpochMillis=$createdAtEpochMillis, " +
            "dataSchemaVersion=$dataSchemaVersion, payload=$payload)"
}

data class PortableBackupIntegrity(
    val algorithm: String,
    val documentSha256: String,
) {
    init {
        if (algorithm != SHA_256_ALGORITHM || !documentSha256.matches(SHA_256_PATTERN)) {
            throw PortableBackupValidationException(PortableBackupRejectReason.INTEGRITY_MISMATCH)
        }
    }

    override fun toString(): String =
        "PortableBackupIntegrity(algorithm=$algorithm, digestPresent=true)"

    companion object {
        const val SHA_256_ALGORITHM: String = "SHA-256"
    }
}

data class PortableBackupDocument(
    val formatVersion: Int,
    val snapshot: PortableBackupSnapshot,
    val integrity: PortableBackupIntegrity,
) {
    init {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw PortableBackupValidationException(PortableBackupRejectReason.UNSUPPORTED_VERSION)
        }
    }

    override fun toString(): String =
        "PortableBackupDocument(formatVersion=$formatVersion, snapshot=$snapshot, integrity=$integrity)"

    companion object {
        const val CURRENT_FORMAT_VERSION: Int = 1
    }
}

internal class PortableBackupValidationException(
    val reason: PortableBackupRejectReason,
) : IllegalArgumentException(reason.name)

private fun requirePortableId(value: String) {
    if (value.length > PortableBackupLimits.MAX_ID_CHARACTERS) {
        limitExceeded()
    }
    if (value.isBlank() || value != value.trim()) {
        invalidData()
    }
}

private fun requirePortableName(value: String) {
    if (value.length > PortableBackupLimits.MAX_DISPLAY_NAME_CHARACTERS) {
        limitExceeded()
    }
    if (value.isBlank() || value != value.trim()) {
        invalidData()
    }
}

private fun requireCountWithin(count: Int, maximum: Int) {
    if (count > maximum) {
        limitExceeded()
    }
}

private fun <T> requireUnique(values: List<T>) {
    if (values.toSet().size != values.size) {
        throw PortableBackupValidationException(PortableBackupRejectReason.DUPLICATE_IDENTITY)
    }
}

private fun limitExceeded(): Nothing =
    throw PortableBackupValidationException(PortableBackupRejectReason.LIMIT_EXCEEDED)

private fun invalidData(): Nothing =
    throw PortableBackupValidationException(PortableBackupRejectReason.INVALID_DATA)

private val SHA_256_PATTERN = Regex("[0-9a-f]{64}")
