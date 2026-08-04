package app.muxtv.feature.search

import app.muxtv.catalog.ChannelSearchResult

internal sealed interface SearchUiState {
    data object Idle : SearchUiState
    data object Loading : SearchUiState
    data object Empty : SearchUiState
    data object Failed : SearchUiState

    data class Content(
        val rows: List<SearchRowProjection>,
        val isTruncated: Boolean,
    ) : SearchUiState {
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
    )
