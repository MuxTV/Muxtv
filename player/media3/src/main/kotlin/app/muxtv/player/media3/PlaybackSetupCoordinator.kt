package app.muxtv.player.media3

internal enum class PlaybackSetupInstallResult {
    Installed,
    Cancelled,
}

internal enum class PlaybackSetupCancelResult {
    PendingCancelled,
    ActiveCleared,
    AlreadyCancelled,
}

internal class PlaybackSetupCoordinator<T : Any>(
    private val cancelledCapacity: Int = DEFAULT_CANCELLED_CAPACITY,
    private val install: (PlaybackSetupId, T) -> Unit,
    private val clearInstalled: () -> Unit,
) {
    private val cancelledIds = linkedSetOf<PlaybackSetupId>()
    private var activeId: PlaybackSetupId? = null

    init {
        require(cancelledCapacity > 0) { "cancelledCapacity must be positive." }
    }

    fun install(
        id: PlaybackSetupId,
        value: T,
    ): PlaybackSetupInstallResult {
        if (id in cancelledIds) return PlaybackSetupInstallResult.Cancelled

        install(id, value)
        activeId = id
        return PlaybackSetupInstallResult.Installed
    }

    fun cancel(id: PlaybackSetupId): PlaybackSetupCancelResult {
        val added = cancelledIds.add(id)
        trimCancelledIds()

        if (activeId == id) {
            activeId = null
            clearInstalled()
            return PlaybackSetupCancelResult.ActiveCleared
        }

        return if (added) {
            PlaybackSetupCancelResult.PendingCancelled
        } else {
            PlaybackSetupCancelResult.AlreadyCancelled
        }
    }

    private fun trimCancelledIds() {
        while (cancelledIds.size > cancelledCapacity) {
            val oldest = cancelledIds.firstOrNull() ?: return
            cancelledIds.remove(oldest)
        }
    }

    private companion object {
        const val DEFAULT_CANCELLED_CAPACITY = 64
    }
}
