package app.muxtv.feature.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
            onOpenChannel = onOpenChannel,
            modifier = modifier,
        )
    }
}

@Composable
private fun ChannelsContent(
    channels: List<PlayableChannelSummary>,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
) {
    val listState = rememberLazyListState()
    var lastFocusedChannelId by rememberSaveable { mutableStateOf<String?>(null) }

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
            items(
                items = channels,
                key = PlayableChannelSummary::channelId,
            ) { channel ->
                MuxTvActionButton(
                    text = channel.buttonLabel(lastFocusedChannelId == channel.channelId),
                    onClick = { onOpenChannel(channel.channelId) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                lastFocusedChannelId = channel.channelId
                            }
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

private fun PlayableChannelSummary.buttonLabel(wasFocused: Boolean): String = buildString {
    if (wasFocused) append("• ")
    channelNumber?.takeIf(String::isNotBlank)?.let { append(it).append("  ") }
    append(displayName)
    groupTitle?.takeIf(String::isNotBlank)?.let { append("  ·  ").append(it) }
    if (variantCount > 1) append("  ·  $variantCount источника")
}

private const val CHANNEL_LIMIT = 200
