package app.muxtv.catalog

import app.muxtv.common.SourceId

enum class ProviderUsability {
    NOT_USABLE,
    USABLE,
}

data class ProviderActiveCatalog(
    val revisionNumber: Long,
    val channelCount: Int,
    val activatedAtEpochMillis: Long,
) {
    init {
        require(revisionNumber > 0)
        require(channelCount > 0)
        require(activatedAtEpochMillis >= 0)
    }
}

data class ProviderSyncProgress(
    val completedPages: Int,
    val discoveredItems: Int,
) {
    init {
        require(completedPages >= 0)
        require(discoveredItems >= 0)
    }
}

sealed interface ProviderSyncFailure {
    data object AuthenticationRequired : ProviderSyncFailure

    data class RateLimited(
        val retryAfterEpochMillis: Long?,
    ) : ProviderSyncFailure {
        init {
            require(retryAfterEpochMillis == null || retryAfterEpochMillis >= 0)
        }
    }

    data object Timeout : ProviderSyncFailure
    data object Network : ProviderSyncFailure
    data object InvalidContent : ProviderSyncFailure
    data object Storage : ProviderSyncFailure
    data object Internal : ProviderSyncFailure
}

sealed interface ProviderCatalogSyncAttempt {
    data object Idle : ProviderCatalogSyncAttempt

    data class Running(
        val progress: ProviderSyncProgress,
    ) : ProviderCatalogSyncAttempt

    data class Succeeded(
        val revisionNumber: Long,
    ) : ProviderCatalogSyncAttempt {
        init {
            require(revisionNumber > 0)
        }
    }

    data class Failed(
        val failure: ProviderSyncFailure,
    ) : ProviderCatalogSyncAttempt

    data object Cancelled : ProviderCatalogSyncAttempt
    data object Superseded : ProviderCatalogSyncAttempt
}

sealed interface ProviderSecondaryAttempt {
    data object Idle : ProviderSecondaryAttempt
    data object Running : ProviderSecondaryAttempt

    data class Succeeded(
        val revisionNumber: Long,
    ) : ProviderSecondaryAttempt {
        init {
            require(revisionNumber > 0)
        }
    }

    data class Failed(
        val failure: ProviderSyncFailure,
    ) : ProviderSecondaryAttempt

    data object Cancelled : ProviderSecondaryAttempt
    data object Superseded : ProviderSecondaryAttempt
}

data class ProviderSecondaryState(
    val activeRevisionNumber: Long? = null,
    val latestAttempt: ProviderSecondaryAttempt = ProviderSecondaryAttempt.Idle,
) {
    init {
        require(activeRevisionNumber == null || activeRevisionNumber > 0)
        if (latestAttempt is ProviderSecondaryAttempt.Succeeded) {
            require(activeRevisionNumber == latestAttempt.revisionNumber)
        }
    }

    val hasActiveData: Boolean
        get() = activeRevisionNumber != null
}

data class ProviderReadinessSnapshot(
    val sourceId: SourceId,
    val activeCatalog: ProviderActiveCatalog?,
    val latestCatalogAttempt: ProviderCatalogSyncAttempt = ProviderCatalogSyncAttempt.Idle,
    val epg: ProviderSecondaryState = ProviderSecondaryState(),
) {
    init {
        if (latestCatalogAttempt is ProviderCatalogSyncAttempt.Succeeded) {
            require(activeCatalog?.revisionNumber == latestCatalogAttempt.revisionNumber)
        }
    }

    val usability: ProviderUsability
        get() = if (activeCatalog == null) {
            ProviderUsability.NOT_USABLE
        } else {
            ProviderUsability.USABLE
        }

    override fun toString(): String =
        "ProviderReadinessSnapshot(" +
            "usability=$usability, " +
            "activeCatalogRevision=${activeCatalog?.revisionNumber}, " +
            "activeChannelCount=${activeCatalog?.channelCount}, " +
            "catalogAttempt=${latestCatalogAttempt.diagnosticName()}, " +
            "epgActive=${epg.hasActiveData}, " +
            "epgAttempt=${epg.latestAttempt.diagnosticName()})"
}

private fun ProviderCatalogSyncAttempt.diagnosticName(): String = when (this) {
    ProviderCatalogSyncAttempt.Idle -> "IDLE"
    is ProviderCatalogSyncAttempt.Running -> "RUNNING"
    is ProviderCatalogSyncAttempt.Succeeded -> "SUCCEEDED"
    is ProviderCatalogSyncAttempt.Failed -> "FAILED"
    ProviderCatalogSyncAttempt.Cancelled -> "CANCELLED"
    ProviderCatalogSyncAttempt.Superseded -> "SUPERSEDED"
}

private fun ProviderSecondaryAttempt.diagnosticName(): String = when (this) {
    ProviderSecondaryAttempt.Idle -> "IDLE"
    ProviderSecondaryAttempt.Running -> "RUNNING"
    is ProviderSecondaryAttempt.Succeeded -> "SUCCEEDED"
    is ProviderSecondaryAttempt.Failed -> "FAILED"
    ProviderSecondaryAttempt.Cancelled -> "CANCELLED"
    ProviderSecondaryAttempt.Superseded -> "SUPERSEDED"
}
