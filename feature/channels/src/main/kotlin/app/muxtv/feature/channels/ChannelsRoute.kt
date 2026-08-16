package app.muxtv.feature.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.designsystem.component.MuxTvChannelLogo
import app.muxtv.designsystem.component.MuxTvProgrammeProgress
import app.muxtv.designsystem.component.MuxTvScreenScaffold
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun ChannelsRoute(
    channelBrowseRepository: ChannelBrowseRepository,
    epgGuideRepository: EpgGuideRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    val factory = remember(
        channelBrowseRepository,
        epgGuideRepository,
        playbackSessionStateSource,
        profileId,
    ) {
        viewModelFactory {
            initializer {
                ChannelsViewModel(
                    channelBrowseRepository = channelBrowseRepository,
                    playbackSessionStateSource = playbackSessionStateSource,
                    epgGuideRepository = epgGuideRepository,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: ChannelsViewModel = viewModel(factory = factory)
    val filter by screenViewModel.filter.collectAsStateWithLifecycle()
    val rowsFlow = remember(screenViewModel, filter) {
        screenViewModel.rowsFor(filter)
    }
    val rows = rowsFlow.collectAsLazyPagingItems()
    val listState = rememberLazyListState()
    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedChannelScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    val focusAnchor = focusedChannelId?.let { id ->
        FocusAnchor(id, focusedChannelIndex, focusedChannelScrollOffset)
    }

    LaunchedEffect(listState, rows) {
        snapshotFlow {
            val snapshot = rows.itemSnapshotList
            visibleChannelIds(
                visibleIndexes = listState.layoutInfo.visibleItemsInfo.map { it.index },
                itemAt = snapshot::getOrNull,
            )
        }.collect(screenViewModel::setNowNextIds)
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
            railFocusRequester = railFocusRequester,
            modifier = modifier,
        )
    }
}

internal fun visibleChannelIds(
    visibleIndexes: List<Int>,
    itemAt: (Int) -> ChannelRowUiModel?,
): List<String> = visibleIndexes
    .mapNotNull { index -> itemAt(index)?.channelId }
    .distinct()

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
    rows: LazyPagingItems<ChannelRowUiModel>,
    filter: ChannelsFilter,
    listState: LazyListState,
    focusAnchor: FocusAnchor?,
    onFilterChanged: (ChannelsFilter) -> Unit,
    onFocusAnchorChanged: (FocusAnchor) -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
    railFocusRequester: FocusRequester? = null,
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
    var restorationCompleted by remember(filter) { mutableStateOf(false) }
    var observedFocusedChannelId by remember(filter) { mutableStateOf<String?>(null) }
    val refreshState = rows.loadState.refresh
    val appendState = rows.loadState.append

    LaunchedEffect(
        rows,
        refreshState,
        appendState,
        rows.itemCount,
        focusAnchor,
        restorationCompleted,
    ) {
        if (
            restorationCompleted ||
            refreshState !is LoadState.NotLoading ||
            rows.itemCount == 0
        ) {
            return@LaunchedEffect
        }

        val requestedIndex = focusAnchor?.previousIndex?.coerceIn(0, rows.itemCount - 1) ?: 0
        listState.scrollToItem(requestedIndex, focusAnchor?.scrollOffset ?: 0)

        val target = snapshotFlow {
            val anchoredIndex = focusAnchor?.let { anchor ->
                findLoadedIndex(rows, anchor.itemKey)
            }
            when {
                anchoredIndex != null -> {
                    val anchoredId = rows.peek(anchoredIndex)?.channelId
                    anchoredId?.let { anchoredIndex to it }
                }

                focusAnchor == null -> {
                    rows.peek(requestedIndex)?.channelId?.let { requestedIndex to it }
                }

                appendState is LoadState.NotLoading && appendState.endOfPaginationReached -> {
                    val loadedIds = (0 until rows.itemCount)
                        .mapNotNull { index -> rows.peek(index)?.channelId }
                    if (loadedIds.size == rows.itemCount) {
                        focusAnchor.resolveAgainst(loadedIds)?.let { resolved ->
                            resolved.index to resolved.itemKey
                        }
                    } else {
                        null
                    }
                }

                else -> null
            }
        }
            .filterNotNull()
            .first()

        val (targetIndex, targetId) = target
        if (targetIndex != requestedIndex) {
            listState.scrollToItem(targetIndex)
        }

        val requester = snapshotFlow {
            val placed = listState.layoutInfo.visibleItemsInfo.any { item ->
                item.index == targetIndex
            }
            if (placed) focusRequesters[targetId] else null
        }
            .filterNotNull()
            .first()

        withFrameNanos { }
        if (!requester.requestFocus()) return@LaunchedEffect

        snapshotFlow { observedFocusedChannelId }
            .first { focusedId -> focusedId == targetId }
        restorationCompleted = true
    }

    MuxTvScreenScaffold(
        title = filter.title(),
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = "Все каналы",
                onClick = { onFilterChanged(ChannelsFilter.ALL) },
                selected = filter == ChannelsFilter.ALL,
                modifier = Modifier.testTag(CHANNELS_ALL_FILTER_TEST_TAG)
                    .focusProperties {
                        left = railFocusRequester ?: FocusRequester.Default
                        right = favoritesFilterFocusRequester
                    }
                    .focusRequester(allFilterFocusRequester),
            )
            MuxTvActionButton(
                text = "Избранное",
                onClick = { onFilterChanged(ChannelsFilter.FAVORITES) },
                selected = filter == ChannelsFilter.FAVORITES,
                modifier = Modifier.testTag(CHANNELS_FAVORITES_FILTER_TEST_TAG)
                    .focusProperties {
                        left = allFilterFocusRequester
                        right = recentFilterFocusRequester
                    }
                    .focusRequester(favoritesFilterFocusRequester),
            )
            MuxTvActionButton(
                text = "Недавние",
                onClick = { onFilterChanged(ChannelsFilter.RECENT) },
                selected = filter == ChannelsFilter.RECENT,
                modifier = Modifier.testTag(CHANNELS_RECENT_FILTER_TEST_TAG)
                    .focusProperties {
                        left = favoritesFilterFocusRequester
                    }
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
                key = rows.itemKey(ChannelRowUiModel::channelId),
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
                    ChannelRow(
                        row = row,
                        onClick = {
                            captureFocusAnchor()
                            onOpenChannel(row.channelId)
                        },
                        modifier = Modifier.fillMaxWidth()
                            .testTag("$CHANNEL_ROW_TEST_TAG_PREFIX$index")
                            .focusProperties {
                                if (index == 0) {
                                    up = selectedFilterFocusRequester
                                    left = railFocusRequester ?: FocusRequester.Default
                                }
                            }
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    observedFocusedChannelId = row.channelId
                                    captureFocusAnchor()
                                } else if (observedFocusedChannelId == row.channelId) {
                                    observedFocusedChannelId = null
                                }
                            },
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

private fun findLoadedIndex(rows: LazyPagingItems<ChannelRowUiModel>, channelId: String): Int? {
    for (index in 0 until rows.itemCount) {
        if (rows.peek(index)?.channelId == channelId) return index
    }
    return null
}

/** Lounge channel row: fixed geometry, no focus scale. */
@Composable
private fun ChannelRow(
    row: ChannelRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.rowCorner)
    Row(
        modifier = modifier
            .height(TvTokens.Size.channelRowHeight)
            .clip(shape)
            .background(if (focused) TvTokens.Color.surfaceRaised else MaterialTheme.colorScheme.surface)
            .border(
                width = if (focused) TvTokens.Focus.outlineWidth else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.borderVariant,
                shape = shape,
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = TvTokens.Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
            Text(
                text = row.channelNumber?.takeIf(String::isNotBlank) ?: "—",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(TvTokens.Spacing.small))
        MuxTvChannelLogo(name = row.displayName)
        Spacer(Modifier.width(TvTokens.Spacing.small))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.isCurrentPlayback) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.width(TvTokens.Spacing.xSmall))
                }
                if (row.isFavorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = TvTokens.Color.accent,
                    )
                    Spacer(Modifier.width(TvTokens.Spacing.xSmall))
                }
                Text(
                    text = row.displayName,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontSize = TvTokens.Typography.cardTitle,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = row.metadataLabel.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(TvTokens.Spacing.medium))
        Column(
            modifier = Modifier.width(ROW_PROGRAMME_WIDTH),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = row.currentProgrammeLabel + row.currentEndLabel?.let { " · до $it" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.nextProgrammeLabel + row.nextStartLabel?.let { " · в $it" }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(TvTokens.Spacing.medium))
        Box(
            modifier = Modifier.width(ROW_PROGRESS_WIDTH).height(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (row.progressFraction != null) {
                MuxTvProgrammeProgress(fraction = row.progressFraction, height = 4.dp)
            }
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
    ChannelsFilter.ALL -> "Эфир"
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

private fun ChannelsFilter.countLabel(count: Int) = when (this) {
    ChannelsFilter.ALL -> "Показано каналов: $count"
    ChannelsFilter.FAVORITES -> "Показано избранных: $count"
    ChannelsFilter.RECENT -> "Показано недавних: $count"
}

private const val CHANNEL_ROW_TEST_TAG_PREFIX = "channel-row-"
private const val CHANNELS_ALL_FILTER_TEST_TAG = "channels-filter-all"
private const val CHANNELS_FAVORITES_FILTER_TEST_TAG = "channels-filter-favorites"
private const val CHANNELS_RECENT_FILTER_TEST_TAG = "channels-filter-recent"
private val ROW_PROGRAMME_WIDTH = 340.dp
private val ROW_PROGRESS_WIDTH = 120.dp