package app.muxtv.feature.guide

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

internal sealed interface GuideUiState {
    val statusLabel: String

    data object Loading : GuideUiState {
        override val statusLabel = "Загружаем программу…"
    }

    data object Empty : GuideUiState {
        override val statusLabel = "Нет доступных каналов."
    }

    data object Failed : GuideUiState {
        override val statusLabel = "Не удалось загрузить программу."
    }

    data object Incomplete : GuideUiState {
        override val statusLabel = "Программа слишком большая для безопасного окна. Попробуйте ещё раз."
    }

    class Content(
        rows: List<GuideRowProjection>,
        val viewport: GuideViewport,
        val window: GuideWindowPresentation,
    ) : GuideUiState {
        val rows: List<GuideRowProjection> = rows.toList()

        init {
            require(this.rows.isNotEmpty())
            require(this.rows.size <= GuideViewportPolicy.CHANNEL_LIMIT)
            require(window.spanMillis == viewport.toEpochMillis - viewport.fromEpochMillis)
        }

        override val statusLabel: String = window.label

        override fun toString(): String =
            "Content(rowCount=${rows.size}, hasMoreChannels=${viewport.hasMoreChannels}, " +
                "canGoPrevious=${viewport.canGoPrevious}, " +
                "canResetToFirstPage=${viewport.canResetToFirstPage}, " +
                "spanMillis=${window.spanMillis})"
    }
}
