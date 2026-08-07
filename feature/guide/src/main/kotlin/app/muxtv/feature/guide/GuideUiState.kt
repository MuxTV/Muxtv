package app.muxtv.feature.guide

import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary

internal data class GuideViewport(
    val fromEpochMillis: Long,
    val toEpochMillis: Long,
    val hasMoreChannels: Boolean,
    val canGoPrevious: Boolean,
    val canResetToFirstPage: Boolean,
) {
    init {
        require(fromEpochMillis >= 0L)
        require(toEpochMillis > fromEpochMillis)
        require(!canGoPrevious || canResetToFirstPage)
    }
}

internal class GuideRow(
    val channel: PlayableChannelSummary,
    val state: GuideProjectionState,
    programmes: List<GuideProgrammeCell>,
) {
    val programmes: List<GuideProgrammeCell> = programmes.toList()

    init {
        require(state == GuideProjectionState.READY || this.programmes.isEmpty())
    }

    override fun toString(): String =
        "GuideRow(state=$state, programmeCount=${programmes.size}, " +
            "favorite=${channel.isFavorite}, channelNumberPresent=${channel.channelNumber != null})"
}

internal sealed interface GuideUiState {
    data object Loading : GuideUiState
    data object Empty : GuideUiState
    data object Failed : GuideUiState
    data object Incomplete : GuideUiState

    class Content(
        rows: List<GuideRow>,
        val viewport: GuideViewport,
    ) : GuideUiState {
        val rows: List<GuideRow> = rows.toList()

        init {
            require(this.rows.isNotEmpty())
            require(this.rows.size <= GuideViewportPolicy.CHANNEL_LIMIT)
        }

        override fun toString(): String =
            "Content(rowCount=${rows.size}, hasMoreChannels=${viewport.hasMoreChannels}, " +
                "canGoPrevious=${viewport.canGoPrevious}, " +
                "canResetToFirstPage=${viewport.canResetToFirstPage}, " +
                "spanMillis=${viewport.toEpochMillis - viewport.fromEpochMillis})"
    }
}
