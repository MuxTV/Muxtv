package app.muxtv.feature.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.ChannelQuery
import app.muxtv.catalog.PlayableChannelSummary
import app.muxtv.catalog.PlaybackCatalog
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

sealed interface ChannelsUiState {
    data object Loading : ChannelsUiState
    data object Empty : ChannelsUiState
    data object Failed : ChannelsUiState
    data class Content(val channels: List<PlayableChannelSummary>) : ChannelsUiState
}

@Composable
fun ChannelsRoute(
    playbackCatalog: PlaybackCatalog,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedChannelScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    val state by produceState<ChannelsUiState>(
        initialValue = ChannelsUiState.Loading,
        playbackCatalog,
        profileId,
    ) {
        playbackCatalog.observeChannels(
            ChannelQuery(
                profileId = profileId,
                limit = CHANNEL_LIMIT,
            ),
        )
            .catch { value = ChannelsUiState.Failed }
            .collect { channels ->
                value = if (channels.isEmpty()) {
                    ChannelsUiState.Empty
                } else {
                    ChannelsUiState.Content(channels)
                }
            }
    }

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
            channels = current.channels,
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
    channels: List<PlayableChannelSummary>,
    listState: LazyListState,
    focusAnchor: FocusAnchor?,
    onFocusAnchorChanged: (FocusAnchor) -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
) {
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val restorationAnchor = remember { focusAnchor }
    var restorationCompleted by remember { mutableStateOf(false) }

    LaunchedEffect(channels, restorationAnchor, restorationCompleted) {
        if (restorationCompleted || channels.isEmpty()) return@LaunchedEffect

        val itemKeys = channels.map(PlayableChannelSummary::channelId)
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
            text = "Активных каналов: ${channels.size}",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
        ) {
            itemsIndexed(
                items = channels,
                key = { _, channel -> channel.channelId },
            ) { index, channel ->
                val focusRequester = remember(channel.channelId) { FocusRequester() }
                DisposableEffect(channel.channelId, focusRequester) {
                    focusRequesters[channel.channelId] = focusRequester
                    onDispose {
                        if (focusRequesters[channel.channelId] === focusRequester) {
                            focusRequesters.remove(channel.channelId)
                        }
                    }
                }

                fun captureFocusAnchor() {
                    onFocusAnchorChanged(
                        FocusAnchor(
                            itemKey = channel.channelId,
                            previousIndex = index,
                            scrollOffset = listState.firstVisibleItemScrollOffset,
                        ),
                    )
                }

                MuxTvActionButton(
                    text = channel.buttonLabel(),
                    onClick = {
                        captureFocusAnchor()
                        onOpenChannel(channel.channelId)
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

private fun PlayableChannelSummary.buttonLabel(): String = buildString {
    channelNumber?.takeIf(String::isNotBlank)?.let { append(it).append("  ") }
    append(displayName)
    groupTitle?.takeIf(String::isNotBlank)?.let { append("  ·  ").append(it) }
    if (variantCount > 1) append("  ·  $variantCount источника")
}

private const val CHANNEL_ROW_TEST_TAG_PREFIX = "channel-row-"
private const val CHANNEL_LIMIT = 200
