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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.EpgGuideRepository
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.catalog.RecentChannelsRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun ChannelsRoute(
    playbackCatalog: PlaybackCatalog,
    recentChannelsRepository: RecentChannelsRepository,
    epgGuideRepository: EpgGuideRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(
        playbackCatalog,
        recentChannelsRepository,
        epgGuideRepository,
        playbackSessionStateSource,
        profileId,
    ) {
        viewModelFactory {
            initializer {
                ChannelsViewModel(
                    playbackCatalog = playbackCatalog,
                    recentChannelsRepository = recentChannelsRepository,
                    epgGuideRepository = epgGuideRepository,
                    playbackSessionStateSource = playbackSessionStateSource,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: ChannelsViewModel = viewModel(factory = factory)
    val state by screenViewModel.uiState.collectAsStateWithLifecycle()
    val filter by screenViewModel.filter.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedChannelScrollOffset by rememberSaveable { mutableIntStateOf(0) }

    val focusAnchor = focusedChannelId?.let { channelId ->
        FocusAnchor(
            itemKey = channelId,
            previousIndex = focusedChannelIndex,
            scrollOffset = focusedChannelScrollOffset,
        )
    }
    val routeTitle = filter.title()

    when (val current = state) {
        ChannelsUiState.Loading -> MessageRoute(
            title = routeTitle,
            message = filter.loadingMessage(),
            modifier = modifier,
        )

        ChannelsUiState.Empty -> when (filter) {
            ChannelsFilter.ALL -> MessageRoute(
                title = routeTitle,
                message = "Активных каналов пока нет. Добавьте или обновите источник.",
                modifier = modifier,
            )

            ChannelsFilter.FAVORITES -> MessageRoute(
                title = routeTitle,
                message = "В избранном пока нет каналов.",
                actionLabel = "Показать все каналы",
                onAction = { screenViewModel.setFilter(ChannelsFilter.ALL) },
                modifier = modifier,
            )

            ChannelsFilter.RECENT -> MessageRoute(
                title = routeTitle,
                message = "Недавно просмотренных каналов пока нет.",
                actionLabel = "Показать все каналы",
                onAction = { screenViewModel.setFilter(ChannelsFilter.ALL) },
                modifier = modifier,
            )
        }

        ChannelsUiState.Failed -> MessageRoute(
            title = routeTitle,
            message = filter.failureMessage(),
            modifier = modifier,
        )

        is ChannelsUiState.Content -> ChannelsContent(
            rows = current.rows,
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
private fun ChannelsContent(
    rows: List<ChannelRowProjection>,
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
    val channelIds = rows.map(ChannelRowProjection::channelId)
    val restorationAnchor = remember(filter, channelIds) { focusAnchor }
    var restorationCompleted by remember(filter, channelIds) { mutableStateOf(false) }

    LaunchedEffect(channelIds, restorationAnchor, restorationCompleted, filter) {
        if (restorationCompleted || channelIds.isEmpty()) return@LaunchedEffect

        val target = restorationAnchor?.resolveAgainst(channelIds) ?: FocusTarget(
            itemKey = channelIds.first(),
            index = 0,
            scrollOffset = 0,
        )
        fun targetIsPlacedInCurrentLayout(): Boolean =
            listState.layoutInfo.visibleItemsInfo.any { item ->
                item.index == target.index && item.key == target.itemKey
            }

        if (!targetIsPlacedInCurrentLayout()) {
            listState.scrollToItem(target.index)
        }
        snapshotFlow { targetIsPlacedInCurrentLayout() }
            .first { isPlaced -> isPlaced }

        val requester = snapshotFlow { focusRequesters[target.itemKey] }
            .filterNotNull()
            .first()
        requester.requestFocus()
        restorationCompleted = true
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(
            text = filter.title(),
            style = MaterialTheme.typography.displaySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = filter.filterLabel(ChannelsFilter.ALL, "Все каналы"),
                onClick = { onFilterChanged(ChannelsFilter.ALL) },
                modifier = Modifier
                    .testTag(CHANNELS_ALL_FILTER_TEST_TAG)
                    .focusProperties { right = favoritesFilterFocusRequester }
                    .focusRequester(allFilterFocusRequester),
            )
            MuxTvActionButton(
                text = filter.filterLabel(ChannelsFilter.FAVORITES, "Избранное"),
                onClick = { onFilterChanged(ChannelsFilter.FAVORITES) },
                modifier = Modifier
                    .testTag(CHANNELS_FAVORITES_FILTER_TEST_TAG)
                    .focusProperties {
                        left = allFilterFocusRequester
                        right = recentFilterFocusRequester
                    }
                    .focusRequester(favoritesFilterFocusRequester),
            )
            MuxTvActionButton(
                text = filter.filterLabel(ChannelsFilter.RECENT, "Недавние"),
                onClick = { onFilterChanged(ChannelsFilter.RECENT) },
                modifier = Modifier
                    .testTag(CHANNELS_RECENT_FILTER_TEST_TAG)
                    .focusProperties { left = favoritesFilterFocusRequester }
                    .focusRequester(recentFilterFocusRequester),
            )
        }
        Text(
            text = filter.countLabel(rows.size),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
        ) {
            itemsIndexed(
                items = rows,
                key = { _, row -> row.channelId },
            ) { index, row ->
                val focusRequester = remember(row.channelId) { FocusRequester() }
                DisposableEffect(row.channelId, focusRequester) {
                    focusRequesters[row.channelId] = focusRequester
                    onDispose {
                        if (focusRequesters[row.channelId] === focusRequester) {
                            focusRequesters.remove(row.channelId)
                        }
                    }
                }

                fun captureFocusAnchor() {
                    onFocusAnchorChanged(
                        FocusAnchor(
                            itemKey = row.channelId,
                            previousIndex = index,
                            scrollOffset = listState.firstVisibleItemScrollOffset,
                        ),
                    )
                }

                ChannelListItem(
                    row = row,
                    onClick = {
                        captureFocusAnchor()
                        onOpenChannel(row.channelId)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("$CHANNEL_ROW_TEST_TAG_PREFIX$index")
                        .focusProperties {
                            if (index == 0) up = selectedFilterFocusRequester
                        }
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) captureFocusAnchor()
                        },
                )
            }
        }
    }
}

@Composable
private fun ChannelListItem(
    row: ChannelRowProjection,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = row.primaryLabel(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.channel.metadataLabel().ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.currentProgrammeLabel(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
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
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (actionLabel != null && onAction != null) {
            MuxTvActionButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.focusRequester(actionFocusRequester),
            )
        }
    }
}

private fun ChannelsFilter.title(): String = when (this) {
    ChannelsFilter.ALL -> "Каналы"
    ChannelsFilter.FAVORITES -> "Избранное"
    ChannelsFilter.RECENT -> "Недавние"
}

private fun ChannelsFilter.loadingMessage(): String = when (this) {
    ChannelsFilter.RECENT -> "Загрузка недавних каналов…"
    ChannelsFilter.ALL,
    ChannelsFilter.FAVORITES,
    -> "Загрузка активного каталога…"
}

private fun ChannelsFilter.failureMessage(): String = when (this) {
    ChannelsFilter.RECENT -> "Не удалось прочитать недавние каналы."
    ChannelsFilter.ALL,
    ChannelsFilter.FAVORITES,
    -> "Не удалось прочитать активный каталог."
}

private fun ChannelsFilter.filterLabel(target: ChannelsFilter, label: String): String =
    if (this == target) "• $label" else label

private fun ChannelsFilter.countLabel(count: Int): String = when (this) {
    ChannelsFilter.ALL -> "Активных каналов: $count"
    ChannelsFilter.FAVORITES -> "Избранных каналов: $count"
    ChannelsFilter.RECENT -> "Недавно просмотренных каналов: $count"
}

private fun ChannelRowProjection.primaryLabel(): String = buildString {
    if (isCurrentPlayback) {
        append(if (isPlaying) "▶  " else "●  ")
    }
    if (channel.isFavorite) append("★  ")
    channel.channelNumber?.takeIf(String::isNotBlank)?.let { append(it).append("  ") }
    append(channel.displayName)
}

private fun PlayableChannelSummary.metadataLabel(): String = buildString {
    groupTitle?.takeIf(String::isNotBlank)?.let(::append)
    if (variantCount > 1) {
        if (isNotEmpty()) append("  ·  ")
        append(variantCount).append(" источника")
    }
}

private fun ChannelRowProjection.currentProgrammeLabel(): String = when (guideState) {
    GuideProjectionState.READY -> currentTitle
        ?.takeIf(String::isNotBlank)
        ?.let { title -> "Сейчас: $title" }
        ?: " "
    GuideProjectionState.SOURCE_CONFLICT -> "Программа недоступна"
    GuideProjectionState.NO_GUIDE -> " "
}

private fun ChannelRowProjection.nextProgrammeLabel(): String = when (guideState) {
    GuideProjectionState.READY -> nextTitle
        ?.takeIf(String::isNotBlank)
        ?.let { title -> "Далее: $title" }
        ?: " "
    GuideProjectionState.SOURCE_CONFLICT,
    GuideProjectionState.NO_GUIDE,
    -> " "
}

private const val CHANNEL_ROW_TEST_TAG_PREFIX = "channel-row-"
private const val CHANNELS_ALL_FILTER_TEST_TAG = "channels-filter-all"
private const val CHANNELS_FAVORITES_FILTER_TEST_TAG = "channels-filter-favorites"
private const val CHANNELS_RECENT_FILTER_TEST_TAG = "channels-filter-recent"
