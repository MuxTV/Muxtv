package app.muxtv.feature.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.ChannelBrowseItem
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun ChannelsRoute(
    channelBrowseRepository: ChannelBrowseRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(channelBrowseRepository, playbackSessionStateSource, profileId) {
        viewModelFactory {
            initializer {
                ChannelsViewModel(
                    channelBrowseRepository = channelBrowseRepository,
                    playbackSessionStateSource = playbackSessionStateSource,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: ChannelsViewModel = viewModel(factory = factory)
    val filter by screenViewModel.filter.collectAsStateWithLifecycle()
    val rows = screenViewModel.rows.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var focusedChannelId by rememberSaveable(filter) { mutableStateOf<String?>(null) }
    var focusedChannelIndex by rememberSaveable(filter) { mutableIntStateOf(0) }
    var focusedChannelScrollOffset by rememberSaveable(filter) { mutableIntStateOf(0) }
    val focusAnchor = focusedChannelId?.let { id ->
        FocusAnchor(id, focusedChannelIndex, focusedChannelScrollOffset)
    }

    when {
        rows.loadState.refresh is LoadState.Loading && rows.itemCount == 0 -> MessageRoute(
            title = filter.title(),
            message = filter.loadingMessage(),
            modifier = modifier,
        )

        rows.loadState.refresh is LoadState.Error && rows.itemCount == 0 -> MessageRoute(
            title = filter.title(),
            message = filter.failureMessage(),
            actionLabel = "Повторить",
            onAction = rows::retry,
            modifier = modifier,
        )

        rows.loadState.refresh is LoadState.NotLoading && rows.itemCount == 0 -> EmptyRoute(
            filter = filter,
            onShowAll = { screenViewModel.setFilter(ChannelsFilter.ALL) },
            modifier = modifier,
        )

        else -> ChannelsContent(
            rows = rows,
            filter = filter,
            listState = listState,
            focusAnchor = focusAnchor,
            onFilterChanged = screenViewModel::setFilter,
            onFocusAnchorChanged = { anchor ->
                focusedChannelId = anchor.itemKey
                focusedChannelIndex = anchor.previousIndex
                focusedChannelScrollOffset = anchor.scrollOffset
            },
            onOpenChannel = onOpenChannel,
            modifier = modifier,
        )
    }
}

@Composable
private fun EmptyRoute(
    filter: ChannelsFilter,
    onShowAll: () -> Unit,
    modifier: Modifier,
) = when (filter) {
    ChannelsFilter.ALL -> MessageRoute(
        title = filter.title(),
        message = "Активных каналов пока нет. Добавьте или обновите источник.",
        modifier = modifier,
    )

    ChannelsFilter.FAVORITES -> MessageRoute(
        title = filter.title(),
        message = "В избранном пока нет каналов.",
        actionLabel = "Показать все каналы",
        onAction = onShowAll,
        modifier = modifier,
    )

    ChannelsFilter.RECENT -> MessageRoute(
        title = filter.title(),
        message = "Недавно просмотренных каналов пока нет.",
        actionLabel = "Показать все каналы",
        onAction = onShowAll,
        modifier = modifier,
    )
}

@Composable
private fun ChannelsContent(
    rows: LazyPagingItems<ChannelBrowseItem>,
    filter: ChannelsFilter,
    listState: LazyListState,
    focusAnchor: FocusAnchor?,
    onFilterChanged: (ChannelsFilter) -> Unit,
    onFocusAnchorChanged: (FocusAnchor) -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
) {
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val allFilterFocusRequester = remember { FocusRequester() }
    val favoritesFilterFocusRequester = remember { FocusRequester() }
    val recentFilterFocusRequester = remember { FocusRequester() }
    val selectedFilterFocusRequester = when (filter) {
        ChannelsFilter.ALL -> allFilterFocusRequester
        ChannelsFilter.FAVORITES -> favoritesFilterFocusRequester
        ChannelsFilter.RECENT -> recentFilterFocusRequester
    }
    var restorationCompleted by rememberSaveable(filter) { mutableStateOf(false) }

    LaunchedEffect(filter, rows.itemCount, focusAnchor, restorationCompleted) {
        if (restorationCompleted || rows.itemCount == 0) return@LaunchedEffect
        val requestedIndex = focusAnchor?.previousIndex?.coerceIn(0, rows.itemCount - 1) ?: 0
        listState.scrollToItem(requestedIndex, focusAnchor?.scrollOffset ?: 0)
        val loadedAtRequestedIndex = snapshotFlow { rows.peek(requestedIndex) }
            .filterNotNull()
            .first()
        val targetIndex = if (
            focusAnchor == null || loadedAtRequestedIndex.channelId == focusAnchor.itemKey
        ) {
            requestedIndex
        } else {
            findLoadedIndex(rows, focusAnchor.itemKey) ?: requestedIndex
        }
        if (targetIndex != requestedIndex) listState.scrollToItem(targetIndex)
        val targetId = snapshotFlow { rows.peek(targetIndex)?.channelId }
            .filterNotNull()
            .first()
        snapshotFlow { focusRequesters[targetId] }
            .filterNotNull()
            .first()
            .requestFocus()
        restorationCompleted = true
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(filter.title(), style = MaterialTheme.typography.displaySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = filter.filterLabel(ChannelsFilter.ALL, "Все каналы"),
                onClick = { onFilterChanged(ChannelsFilter.ALL) },
                modifier = Modifier.testTag(CHANNELS_ALL_FILTER_TEST_TAG)
                    .focusProperties { right = favoritesFilterFocusRequester }
                    .focusRequester(allFilterFocusRequester),
            )
            MuxTvActionButton(
                text = filter.filterLabel(ChannelsFilter.FAVORITES, "Избранное"),
                onClick = { onFilterChanged(ChannelsFilter.FAVORITES) },
                modifier = Modifier.testTag(CHANNELS_FAVORITES_FILTER_TEST_TAG)
                    .focusProperties {
                        left = allFilterFocusRequester
                        right = recentFilterFocusRequester
                    }
                    .focusRequester(favoritesFilterFocusRequester),
            )
            MuxTvActionButton(
                text = filter.filterLabel(ChannelsFilter.RECENT, "Недавние"),
                onClick = { onFilterChanged(ChannelsFilter.RECENT) },
                modifier = Modifier.testTag(CHANNELS_RECENT_FILTER_TEST_TAG)
                    .focusProperties { left = favoritesFilterFocusRequester }
                    .focusRequester(recentFilterFocusRequester),
            )
        }
        Text(
            text = filter.countLabel(rows.itemCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
        ) {
            items(
                count = rows.itemCount,
                key = rows.itemKey(ChannelBrowseItem::channelId),
                contentType = rows.itemContentType { "channel-row" },
            ) { index ->
                val row = rows[index]
                if (row == null) {
                    Text("Загрузка…", modifier = Modifier.testTag("channel-loading-$index"))
                } else {
                    val focusRequester = remember(row.channelId) { FocusRequester() }
                    DisposableEffect(row.channelId, focusRequester) {
                        focusRequesters[row.channelId] = focusRequester
                        onDispose {
                            if (focusRequesters[row.channelId] === focusRequester) {
                                focusRequesters.remove(row.channelId)
                            }
                        }
                    }
                    fun captureFocusAnchor() = onFocusAnchorChanged(
                        FocusAnchor(
                            itemKey = row.channelId,
                            previousIndex = index,
                            scrollOffset = listState.firstVisibleItemScrollOffset,
                        ),
                    )
                    ChannelListItem(
                        row = row,
                        onClick = {
                            captureFocusAnchor()
                            onOpenChannel(row.channelId)
                        },
                        modifier = Modifier.fillMaxWidth()
                            .testTag("$CHANNEL_ROW_TEST_TAG_PREFIX$index")
                            .focusProperties { if (index == 0) up = selectedFilterFocusRequester }
                            .focusRequester(focusRequester)
                            .onFocusChanged { state -> if (state.isFocused) captureFocusAnchor() },
                    )
                }
            }
            if (rows.loadState.append is LoadState.Error) {
                item(key = "append-error", contentType = "append-error") {
                    MuxTvActionButton(text = "Повторить загрузку", onClick = rows::retry)
                }
            }
        }
    }
}

private fun findLoadedIndex(rows: LazyPagingItems<ChannelBrowseItem>, channelId: String): Int? {
    for (index in 0 until rows.itemCount) {
        if (rows.peek(index)?.channelId == channelId) return index
    }
    return null
}

@Composable
private fun ChannelListItem(row: ChannelBrowseItem, onClick: () -> Unit, modifier: Modifier) {
    Button(onClick = onClick, modifier = modifier, contentPadding = PaddingValues(24.dp, 14.dp)) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = row.primaryLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.metadataLabel().ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(row.currentProgrammeLabel(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = row.nextProgrammeLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MessageRoute(
    title: String,
    message: String,
    modifier: Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val actionFocusRequester = remember(actionLabel) { FocusRequester() }
    LaunchedEffect(actionLabel, onAction) {
        if (actionLabel != null && onAction != null) {
            withFrameNanos { }
            actionFocusRequester.requestFocus()
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.displaySmall)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (actionLabel != null && onAction != null) {
            MuxTvActionButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.focusRequester(actionFocusRequester),
            )
        }
    }
}

private fun ChannelsFilter.title() = when (this) {
    ChannelsFilter.ALL -> "Каналы"
    ChannelsFilter.FAVORITES -> "Избранное"
    ChannelsFilter.RECENT -> "Недавние"
}

private fun ChannelsFilter.loadingMessage() = when (this) {
    ChannelsFilter.RECENT -> "Загрузка недавних каналов…"
    else -> "Загрузка активного каталога…"
}

private fun ChannelsFilter.failureMessage() = when (this) {
    ChannelsFilter.RECENT -> "Не удалось прочитать недавние каналы."
    else -> "Не удалось прочитать активный каталог."
}

private fun ChannelsFilter.filterLabel(target: ChannelsFilter, label: String) =
    if (this == target) "• $label" else label

private fun ChannelsFilter.countLabel(count: Int) = when (this) {
    ChannelsFilter.ALL -> "Показано каналов: $count"
    ChannelsFilter.FAVORITES -> "Показано избранных: $count"
    ChannelsFilter.RECENT -> "Показано недавних: $count"
}

private fun ChannelBrowseItem.primaryLabel(): String = buildString {
    if (isCurrentPlayback) append("▶  ")
    if (isFavorite) append("★  ")
    channelNumber?.takeIf(String::isNotBlank)?.let { append(it).append("  ") }
    append(displayName)
}

private fun ChannelBrowseItem.metadataLabel(): String = buildString {
    groupTitle?.takeIf(String::isNotBlank)?.let(::append)
    if (variantCount > 1) {
        if (isNotEmpty()) append("  ·  ")
        append(variantCount).append(" источника")
    }
}

private fun ChannelBrowseItem.currentProgrammeLabel(): String = when (guideState) {
    GuideProjectionState.READY -> currentProgrammeTitle?.let { "Сейчас: $it" } ?: " "
    GuideProjectionState.SOURCE_CONFLICT -> "Программа недоступна"
    GuideProjectionState.NO_GUIDE -> " "
}

private fun ChannelBrowseItem.nextProgrammeLabel(): String =
    if (guideState == GuideProjectionState.READY) {
        nextProgrammeTitle?.let { "Далее: $it" } ?: " "
    } else {
        " "
    }

private const val CHANNEL_ROW_TEST_TAG_PREFIX = "channel-row-"
private const val CHANNELS_ALL_FILTER_TEST_TAG = "channels-filter-all"
private const val CHANNELS_FAVORITES_FILTER_TEST_TAG = "channels-filter-favorites"
private const val CHANNELS_RECENT_FILTER_TEST_TAG = "channels-filter-recent"
