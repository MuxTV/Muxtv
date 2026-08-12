package app.muxtv.feature.search

import app.muxtv.catalog.ChannelSearchResult

internal sealed interface SearchUiState {
    val statusLabel: String

    data object Idle : SearchUiState {
        override val statusLabel = "Введите запрос. Поиск выполняется по активным каналам и текущим программам."
    }

    data object Loading : SearchUiState {
        override val statusLabel = "Поиск…"
    }

    data object Empty : SearchUiState {
        override val statusLabel = "Ничего не найдено."
    }

    data object Failed : SearchUiState {
        override val statusLabel = "Не удалось выполнить поиск."
    }

    data class Content(
        val rows: List<SearchRowProjection>,
        val isTruncated: Boolean,
    ) : SearchUiState {
        val channelIds = rows.map(SearchRowProjection::channelId)
        override val statusLabel = if (isTruncated) {
            "Показано результатов: ${rows.size}. Уточните запрос, чтобы сузить выдачу."
        } else {
            "Найдено результатов: ${rows.size}"
        }

        override fun toString(): String =
            "Content(resultCount=${rows.size}, isTruncated=$isTruncated)"
    }
}

internal data class SearchRowProjection(
    val channelId: String,
    val channelNumber: String?,
    val displayName: String,
    val groupTitle: String?,
    val isFavorite: Boolean,
    val variantCount: Int,
    val currentProgrammeTitle: String?,
    val primaryLabel: String,
    val metadataLabel: String,
    val currentProgrammeLabel: String,
    val resultTestTag: String,
) {
    init {
        require(channelId.isNotBlank())
        require(displayName.isNotBlank())
        require(variantCount >= 1)
    }

    override fun toString(): String =
        "SearchRowProjection(channelId=<redacted>, favorite=$isFavorite, " +
            "variantCount=$variantCount, currentProgrammePresent=${currentProgrammeTitle != null})"
}

internal fun ChannelSearchResult.toSearchRowProjection(): SearchRowProjection =
    SearchRowProjection(
        channelId = channel.channelId,
        channelNumber = channel.channelNumber,
        displayName = channel.displayName,
        groupTitle = channel.groupTitle,
        isFavorite = channel.isFavorite,
        variantCount = channel.variantCount,
        currentProgrammeTitle = currentProgrammeTitle,
        primaryLabel = buildString {
            if (channel.isFavorite) append("★  ")
            channel.channelNumber?.takeIf(String::isNotBlank)?.let { number ->
                append(number).append("  ")
            }
            append(channel.displayName)
        },
        metadataLabel = buildString {
            channel.groupTitle?.takeIf(String::isNotBlank)?.let(::append)
            if (channel.variantCount > 1) {
                if (isNotEmpty()) append("  ·  ")
                append(channel.variantCount).append(" источника")
            }
        },
        currentProgrammeLabel = currentProgrammeTitle
            ?.takeIf(String::isNotBlank)
            ?.let { title -> "Сейчас: $title" }
            ?: " ",
        resultTestTag = "search-result-${channel.channelId}",
    )
