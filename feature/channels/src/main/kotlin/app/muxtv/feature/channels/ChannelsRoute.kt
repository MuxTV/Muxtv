package app.muxtv.feature.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
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
import app.muxtv.designsystem.TvTokens
import app.muxtv.player.PlaybackSessionStateSource
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun ChannelsRoute(
    playbackCatalog: PlaybackCatalog,
    epgGuideRepository: EpgGuideRepository,
    playbackSessionStateSource: PlaybackSessionStateSource,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(
        playbackCatalog,
        epgGuideRepository,
        playbackSessionStateSource,
        profileId,
    ) {
        viewModelFactory {
            initializer {
                ChannelsViewModel(
                    playbackCatalog = playbackCatalog,
                    epgGuideRepository = epgGuideRepository,
                    playbackSessionStateSource = playbackSessionStateSource,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: ChannelsViewModel = viewModel(factory = factory)
    val state by screenViewModel.uiState.collectAsStateWithLifecycle()

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

    when (val current = state) {
        ChannelsUiState.Loading -> MessageRoute(
            title = "Каналы",
            message = "Загрузка активного каталога…",
            modifier = modifier,
        )

        ChannelsUiState.Empty -> MessageRoute(
            title = "Каналы",
            message = "Активных каналов пока нет. Добавьте или обновите источник.",
            modifier = modifier,
        )

        ChannelsUiState.Failed -> MessageRoute(
            title = "Каналы",
            message = "Не удалось прочитать активный каталог.",
            modifier = modifier,
        )

        is ChannelsUiState.Content -> ChannelsContent(
            rows = current.rows,
            listState = listState,
            focusAnchor = focusAnchor,
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
    listState: LazyListState,
    focusAnchor: FocusAnchor?,
    onFocusAnchorChanged: (FocusAnchor) -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
) {
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val restorationAnchor = remember { focusAnchor }
    var restorationCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(rows, restorationAnchor, restorationCompleted) {
        if (restorationCompleted || rows.isEmpty()) return@LaunchedEffect

        val itemKeys = rows.map(ChannelRowProjection::channelId)
        val target = restorationAnchor?.resolveAgainst(itemKeys) ?: FocusTarget(
            itemKey = itemKeys.first(),
            index = 0,
            scrollOffset = 0,
        )
        val targetIsVisible = listState.layoutInfo.visibleItemsInfo.any { item ->
            item.index == target.index
        }
        if (!targetIsVisible) {
            listState.scrollToItem(target.index)
        }

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
        Text("Каналы", style = MaterialTheme.typography.displaySmall)
        Text(
            text = "Активных каналов: ${rows.size}",
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
) {
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
    }
}

private fun ChannelRowProjection.primaryLabel(): String = buildString {
    if (isCurrentPlayback) {
        append(if (isPlaying) "▶  " else "●  ")
    }
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
