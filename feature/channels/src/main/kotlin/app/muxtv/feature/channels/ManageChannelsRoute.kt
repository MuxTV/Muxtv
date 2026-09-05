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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.ChannelBrowseRepository
import app.muxtv.catalog.ChannelManagementItem
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.designsystem.component.MuxTvChannelLogo
import app.muxtv.designsystem.component.MuxTvScreenScaffold
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ManageChannelsRoute(
    channelBrowseRepository: ChannelBrowseRepository,
    channelPreferencesRepository: ChannelPreferencesRepository,
    profileId: String,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    val factory = remember(channelBrowseRepository, channelPreferencesRepository, profileId) {
        viewModelFactory {
            initializer {
                ManageChannelsViewModel(
                    channelBrowseRepository = channelBrowseRepository,
                    channelPreferencesRepository = channelPreferencesRepository,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: ManageChannelsViewModel = viewModel(factory = factory)
    val filter by screenViewModel.filter.collectAsStateWithLifecycle()
    val rowsFlow = remember(screenViewModel, filter) { screenViewModel.rowsFor(filter) }
    val rows = rowsFlow.collectAsLazyPagingItems()
    val scope = rememberCoroutineScope()
    var selectedChannel by remember { mutableStateOf<ChannelManagementItem?>(null) }
    var editor by remember { mutableStateOf<ManageChannelEditor?>(null) }
    var mutationMessage by remember { mutableStateOf<String?>(null) }
    var selectionFocusAnchor by remember { mutableStateOf<FocusAnchor?>(null) }
    var focusReturnRequest by remember { mutableStateOf<ManageChannelsFocusReturnRequest?>(null) }

    fun dismissActions(waitForAnchorRemoval: Boolean) {
        selectionFocusAnchor?.let { anchor ->
            focusReturnRequest = ManageChannelsFocusReturnRequest(
                anchor = anchor,
                waitForAnchorRemoval = waitForAnchorRemoval,
            )
        }
        selectionFocusAnchor = null
        selectedChannel = null
        editor = null
    }

    fun consumeMutationResult(
        result: ChannelPreferenceMutationResult,
        invalidMessage: String,
        waitForAnchorRemoval: Boolean = false,
    ) {
        mutationMessage = when (result) {
            ChannelPreferenceMutationResult.Applied,
            ChannelPreferenceMutationResult.Unchanged,
            -> null

            ChannelPreferenceMutationResult.NotFound -> "Канал больше недоступен в активном каталоге."
            ChannelPreferenceMutationResult.InvalidInput -> invalidMessage
        }
        if (result.shouldDismissManageChannelActions()) {
            dismissActions(waitForAnchorRemoval = waitForAnchorRemoval)
        }
    }

    ManageChannelsContent(
        rows = rows,
        filter = filter,
        selectedChannel = selectedChannel,
        editor = editor,
        mutationMessage = mutationMessage,
        focusReturnRequest = focusReturnRequest,
        onFilterChanged = { next ->
            selectionFocusAnchor = null
            focusReturnRequest = null
            selectedChannel = null
            editor = null
            mutationMessage = null
            screenViewModel.setFilter(next)
        },
        onSelectChannel = { channel, anchor ->
            selectedChannel = channel
            selectionFocusAnchor = anchor
            focusReturnRequest = null
            editor = null
            mutationMessage = null
        },
        onCloseActions = {
            dismissActions(waitForAnchorRemoval = false)
            mutationMessage = null
        },
        onFocusReturnConsumed = {
            focusReturnRequest = null
        },
        onToggleHidden = { channel ->
            scope.launch {
                consumeMutationResult(
                    result = screenViewModel.setHidden(channel.channelId, !channel.isHidden),
                    invalidMessage = "Не удалось изменить видимость канала.",
                    waitForAnchorRemoval = filter != ManageChannelsFilter.ALL,
                )
            }
        },
        onRename = { channel ->
            editor = ManageChannelEditor.Name(
                value = channel.effectiveDisplayName,
            )
            mutationMessage = null
        },
        onEditNumber = { channel ->
            editor = ManageChannelEditor.Number(
                value = channel.customChannelNumber?.toString().orEmpty(),
            )
            mutationMessage = null
        },
        onEditorValueChanged = { value ->
            editor = when (val current = editor) {
                is ManageChannelEditor.Name -> current.copy(value = value)
                is ManageChannelEditor.Number -> current.copy(value = value)
                null -> null
            }
            mutationMessage = null
        },
        onSaveEditor = save@{
            val channel = selectedChannel ?: return@save
            when (val current = editor) {
                is ManageChannelEditor.Name -> scope.launch {
                    consumeMutationResult(
                        result = screenViewModel.setCustomName(channel.channelId, current.value),
                        invalidMessage = "Введите имя от 1 до 128 символов без управляющих знаков.",
                    )
                }

                is ManageChannelEditor.Number -> {
                    val raw = current.value.trim()
                    val parsed = raw.takeIf(String::isNotEmpty)?.toIntOrNull()
                    if (raw.isNotEmpty() && parsed == null) {
                        mutationMessage = "Введите номер от 1 до 9999."
                        return@save
                    }
                    scope.launch {
                        consumeMutationResult(
                            result = screenViewModel.setChannelNumber(channel.channelId, parsed),
                            invalidMessage = "Введите номер от 1 до 9999.",
                        )
                    }
                }

                null -> Unit
            }
        },
        onCancelEditor = {
            editor = null
            mutationMessage = null
        },
        onReset = { channel ->
            scope.launch {
                consumeMutationResult(
                    result = screenViewModel.resetCustomization(channel.channelId),
                    invalidMessage = "Не удалось сбросить настройки канала.",
                    waitForAnchorRemoval = filter == ManageChannelsFilter.HIDDEN && channel.isHidden,
                )
            }
        },
        railFocusRequester = railFocusRequester,
        modifier = modifier,
    )
}

@Composable
private fun ManageChannelsContent(
    rows: LazyPagingItems<ChannelManagementItem>,
    filter: ManageChannelsFilter,
    selectedChannel: ChannelManagementItem?,
    editor: ManageChannelEditor?,
    mutationMessage: String?,
    focusReturnRequest: ManageChannelsFocusReturnRequest?,
    onFilterChanged: (ManageChannelsFilter) -> Unit,
    onSelectChannel: (ChannelManagementItem, FocusAnchor) -> Unit,
    onCloseActions: () -> Unit,
    onFocusReturnConsumed: () -> Unit,
    onToggleHidden: (ChannelManagementItem) -> Unit,
    onRename: (ChannelManagementItem) -> Unit,
    onEditNumber: (ChannelManagementItem) -> Unit,
    onEditorValueChanged: (String) -> Unit,
    onSaveEditor: () -> Unit,
    onCancelEditor: () -> Unit,
    onReset: (ChannelManagementItem) -> Unit,
    railFocusRequester: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val rowFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val allFocusRequester = remember { FocusRequester() }
    val visibleFocusRequester = remember { FocusRequester() }
    val hiddenFocusRequester = remember { FocusRequester() }
    val firstActionFocusRequester = remember { FocusRequester() }
    val editorFocusRequester = remember { FocusRequester() }
    val selectedFilterFocusRequester = when (filter) {
        ManageChannelsFilter.ALL -> allFocusRequester
        ManageChannelsFilter.VISIBLE -> visibleFocusRequester
        ManageChannelsFilter.HIDDEN -> hiddenFocusRequester
    }
    val refreshState = rows.loadState.refresh
    val appendState = rows.loadState.append

    LaunchedEffect(selectedChannel?.channelId, editor) {
        when {
            editor != null -> editorFocusRequester.requestFocus()
            selectedChannel != null -> firstActionFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(
        focusReturnRequest,
        rows,
        refreshState,
        appendState,
        rows.itemCount,
    ) {
        val request = focusReturnRequest ?: return@LaunchedEffect
        if (refreshState !is LoadState.NotLoading) return@LaunchedEffect

        if (rows.itemCount == 0) {
            withFrameNanos { }
            if (selectedFilterFocusRequester.requestFocus()) {
                onFocusReturnConsumed()
            }
            return@LaunchedEffect
        }

        val requestedIndex = request.anchor.previousIndex.coerceIn(0, rows.itemCount - 1)
        listState.scrollToItem(requestedIndex, request.anchor.scrollOffset)

        val target = snapshotFlow {
            val anchoredIndex = findLoadedManageChannelIndex(rows, request.anchor.itemKey)
            when {
                request.waitForAnchorRemoval && anchoredIndex != null -> null
                !request.waitForAnchorRemoval && anchoredIndex != null -> {
                    request.anchor.itemKey.let { anchoredIndex to it }
                }

                appendState is LoadState.NotLoading && appendState.endOfPaginationReached -> {
                    val loadedIds = (0 until rows.itemCount)
                        .mapNotNull { index -> rows.peek(index)?.channelId }
                    if (loadedIds.size == rows.itemCount) {
                        request.anchor.resolveAgainst(loadedIds)?.let { resolved ->
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
            listState.scrollToItem(targetIndex, request.anchor.scrollOffset)
        }

        val requester = snapshotFlow {
            val placed = listState.layoutInfo.visibleItemsInfo.any { item -> item.index == targetIndex }
            if (placed) rowFocusRequesters[targetId] else null
        }
            .filterNotNull()
            .first()

        withFrameNanos { }
        if (requester.requestFocus()) {
            onFocusReturnConsumed()
        }
    }

    MuxTvScreenScaffold(
        title = "Управление каналами",
        modifier = modifier,
        titleTestTag = MANAGE_CHANNELS_TITLE_TEST_TAG,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = "Все",
                onClick = { onFilterChanged(ManageChannelsFilter.ALL) },
                selected = filter == ManageChannelsFilter.ALL,
                modifier = Modifier
                    .testTag(MANAGE_CHANNELS_FILTER_ALL_TEST_TAG)
                    .focusProperties {
                        left = railFocusRequester ?: FocusRequester.Default
                        right = visibleFocusRequester
                    }
                    .focusRequester(allFocusRequester),
            )
            MuxTvActionButton(
                text = "Видимые",
                onClick = { onFilterChanged(ManageChannelsFilter.VISIBLE) },
                selected = filter == ManageChannelsFilter.VISIBLE,
                modifier = Modifier
                    .testTag(MANAGE_CHANNELS_FILTER_VISIBLE_TEST_TAG)
                    .focusProperties {
                        left = allFocusRequester
                        right = hiddenFocusRequester
                    }
                    .focusRequester(visibleFocusRequester),
            )
            MuxTvActionButton(
                text = "Скрытые",
                onClick = { onFilterChanged(ManageChannelsFilter.HIDDEN) },
                selected = filter == ManageChannelsFilter.HIDDEN,
                modifier = Modifier
                    .testTag(MANAGE_CHANNELS_FILTER_HIDDEN_TEST_TAG)
                    .focusProperties { left = visibleFocusRequester }
                    .focusRequester(hiddenFocusRequester),
            )
        }

        Spacer(Modifier.height(TvTokens.Spacing.small))
        Text(
            text = filter.summary(rows.itemCount),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (selectedChannel != null) {
            Spacer(Modifier.height(TvTokens.Spacing.small))
            ManageChannelActions(
                channel = selectedChannel,
                editor = editor,
                mutationMessage = mutationMessage,
                firstActionFocusRequester = firstActionFocusRequester,
                editorFocusRequester = editorFocusRequester,
                onToggleHidden = { onToggleHidden(selectedChannel) },
                onRename = { onRename(selectedChannel) },
                onEditNumber = { onEditNumber(selectedChannel) },
                onEditorValueChanged = onEditorValueChanged,
                onSaveEditor = onSaveEditor,
                onCancelEditor = onCancelEditor,
                onReset = { onReset(selectedChannel) },
                onClose = onCloseActions,
            )
        } else if (mutationMessage != null) {
            Spacer(Modifier.height(TvTokens.Spacing.small))
            Text(
                text = mutationMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        Spacer(Modifier.height(TvTokens.Spacing.small))
        when {
            rows.loadState.refresh is LoadState.Loading && rows.itemCount == 0 -> {
                Text("Загрузка каналов…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            rows.loadState.refresh is LoadState.Error && rows.itemCount == 0 -> {
                Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    Text("Не удалось прочитать список каналов.", color = MaterialTheme.colorScheme.error)
                    MuxTvActionButton(text = "Повторить", onClick = rows::retry)
                }
            }

            rows.loadState.refresh is LoadState.NotLoading && rows.itemCount == 0 -> {
                Text(
                    text = filter.emptyMessage(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag(MANAGE_CHANNELS_EMPTY_TEST_TAG),
                )
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
            ) {
                items(
                    count = rows.itemCount,
                    key = rows.itemKey(ChannelManagementItem::channelId),
                    contentType = rows.itemContentType { "manage-channel-row" },
                ) { index ->
                    val channel = rows[index]
                    if (channel == null) {
                        Text("Загрузка…")
                    } else {
                        val focusRequester = remember(channel.channelId) { FocusRequester() }
                        DisposableEffect(channel.channelId, focusRequester) {
                            rowFocusRequesters[channel.channelId] = focusRequester
                            onDispose {
                                if (rowFocusRequesters[channel.channelId] === focusRequester) {
                                    rowFocusRequesters.remove(channel.channelId)
                                }
                            }
                        }
                        ManageChannelRow(
                            channel = channel,
                            selected = selectedChannel?.channelId == channel.channelId,
                            onClick = {
                                onSelectChannel(
                                    channel,
                                    FocusAnchor(
                                        itemKey = channel.channelId,
                                        previousIndex = index,
                                        scrollOffset = listState.firstVisibleItemScrollOffset,
                                    ),
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("$MANAGE_CHANNEL_ROW_TEST_TAG_PREFIX${channel.channelId}")
                                .focusProperties {
                                    if (index == 0) {
                                        up = selectedFilterFocusRequester
                                        left = railFocusRequester ?: FocusRequester.Default
                                    }
                                }
                                .focusRequester(focusRequester),
                        )
                    }
                }
                if (rows.loadState.append is LoadState.Error) {
                    item(key = "manage-append-error") {
                        MuxTvActionButton(text = "Повторить загрузку", onClick = rows::retry)
                    }
                }
            }
        }
    }
}

private fun findLoadedManageChannelIndex(
    rows: LazyPagingItems<ChannelManagementItem>,
    channelId: String,
): Int? {
    for (index in 0 until rows.itemCount) {
        if (rows.peek(index)?.channelId == channelId) return index
    }
    return null
}

@Composable
private fun ManageChannelActions(
    channel: ChannelManagementItem,
    editor: ManageChannelEditor?,
    mutationMessage: String?,
    firstActionFocusRequester: FocusRequester,
    editorFocusRequester: FocusRequester,
    onToggleHidden: () -> Unit,
    onRename: () -> Unit,
    onEditNumber: () -> Unit,
    onEditorValueChanged: (String) -> Unit,
    onSaveEditor: () -> Unit,
    onCancelEditor: () -> Unit,
    onReset: () -> Unit,
    onClose: () -> Unit,
) {
    val shape = RoundedCornerShape(TvTokens.Shape.rowCorner)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TvTokens.Color.surfaceRaised)
            .border(1.dp, MaterialTheme.colorScheme.borderVariant, shape)
            .padding(TvTokens.Spacing.medium)
            .testTag(MANAGE_CHANNELS_ACTIONS_TEST_TAG),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        Text(
            text = channel.effectiveDisplayName,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        when (editor) {
            null -> Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                MuxTvActionButton(
                    text = if (channel.isHidden) "Показать" else "Скрыть",
                    onClick = onToggleHidden,
                    modifier = Modifier.focusRequester(firstActionFocusRequester),
                )
                MuxTvActionButton(text = "Переименовать", onClick = onRename)
                MuxTvActionButton(text = "Номер", onClick = onEditNumber)
                MuxTvActionButton(text = "Сбросить", onClick = onReset)
                MuxTvActionButton(text = "Закрыть", onClick = onClose)
            }

            is ManageChannelEditor.Name -> EditorRow(
                label = "Название",
                value = editor.value,
                keyboardType = KeyboardType.Text,
                focusRequester = editorFocusRequester,
                onValueChanged = onEditorValueChanged,
                onSave = onSaveEditor,
                onCancel = onCancelEditor,
            )

            is ManageChannelEditor.Number -> EditorRow(
                label = "Номер · пустое поле вернёт номер источника",
                value = editor.value,
                keyboardType = KeyboardType.Number,
                focusRequester = editorFocusRequester,
                onValueChanged = onEditorValueChanged,
                onSave = onSaveEditor,
                onCancel = onCancelEditor,
            )
        }

        if (mutationMessage != null) {
            Text(
                text = mutationMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(MANAGE_CHANNELS_ERROR_TEST_TAG),
            )
        }
    }
}

@Composable
private fun EditorRow(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    focusRequester: FocusRequester,
    onValueChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.xSmall)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChanged,
                modifier = Modifier
                    .width(420.dp)
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(TvTokens.Shape.rowCorner))
                    .border(1.dp, MaterialTheme.colorScheme.borderVariant, RoundedCornerShape(TvTokens.Shape.rowCorner))
                    .padding(horizontal = TvTokens.Spacing.small)
                    .focusRequester(focusRequester)
                    .testTag(MANAGE_CHANNELS_EDITOR_TEST_TAG),
                textStyle = MaterialTheme.typography.titleMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            )
            MuxTvActionButton(text = "Сохранить", onClick = onSave)
            MuxTvActionButton(text = "Отмена", onClick = onCancel)
        }
    }
}

@Composable
private fun ManageChannelRow(
    channel: ChannelManagementItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember(channel.channelId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.rowCorner)
    val customizedName = channel.effectiveDisplayName != channel.canonicalDisplayName
    val customizedNumber = channel.customChannelNumber != null
    val originalParts = buildList {
        if (customizedName) add("исходное имя: ${channel.canonicalDisplayName}")
        if (customizedNumber) {
            channel.defaultChannelNumber?.takeIf(String::isNotBlank)?.let { add("номер источника: $it") }
        }
    }
    val stateParts = buildList {
        add(if (channel.isHidden) "Скрыт" else "Видим")
        if (channel.isFavorite) add("Избранное")
        if (customizedName || customizedNumber) add("Изменён")
        if (channel.variantCount > 1) add("Источников: ${channel.variantCount}")
    }

    Row(
        modifier = modifier
            .height(MANAGE_CHANNEL_ROW_HEIGHT)
            .clip(shape)
            .background(
                when {
                    focused -> TvTokens.Color.surfaceRaised
                    selected -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.surface
                },
            )
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
        Box(modifier = Modifier.width(64.dp), contentAlignment = Alignment.Center) {
            Text(
                text = channel.effectiveChannelNumber?.takeIf(String::isNotBlank) ?: "—",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(TvTokens.Spacing.small))
        MuxTvChannelLogo(name = channel.effectiveDisplayName)
        Spacer(Modifier.width(TvTokens.Spacing.small))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = channel.effectiveDisplayName,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = originalParts.joinToString(" · ").ifBlank { "Без пользовательских изменений" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(TvTokens.Spacing.medium))
        Text(
            text = stateParts.joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = if (channel.isHidden) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

private data class ManageChannelsFocusReturnRequest(
    val anchor: FocusAnchor,
    val waitForAnchorRemoval: Boolean,
)

private sealed interface ManageChannelEditor {
    val value: String

    data class Name(override val value: String) : ManageChannelEditor
    data class Number(override val value: String) : ManageChannelEditor
}

internal fun ChannelPreferenceMutationResult.shouldDismissManageChannelActions(): Boolean =
    this != ChannelPreferenceMutationResult.InvalidInput

private fun ManageChannelsFilter.summary(count: Int): String = when (this) {
    ManageChannelsFilter.ALL -> "Каналов в управлении: $count"
    ManageChannelsFilter.VISIBLE -> "Видимых каналов: $count"
    ManageChannelsFilter.HIDDEN -> "Скрытых каналов: $count"
}

private fun ManageChannelsFilter.emptyMessage(): String = when (this) {
    ManageChannelsFilter.ALL -> "Активных каналов пока нет."
    ManageChannelsFilter.VISIBLE -> "Нет видимых каналов. Скрытые можно восстановить во вкладке «Скрытые»."
    ManageChannelsFilter.HIDDEN -> "Скрытых каналов нет."
}

private const val MANAGE_CHANNELS_TITLE_TEST_TAG = "manage-channels-title"
private const val MANAGE_CHANNELS_FILTER_ALL_TEST_TAG = "manage-channels-filter-all"
private const val MANAGE_CHANNELS_FILTER_VISIBLE_TEST_TAG = "manage-channels-filter-visible"
private const val MANAGE_CHANNELS_FILTER_HIDDEN_TEST_TAG = "manage-channels-filter-hidden"
private const val MANAGE_CHANNELS_EMPTY_TEST_TAG = "manage-channels-empty"
private const val MANAGE_CHANNELS_ACTIONS_TEST_TAG = "manage-channels-actions"
private const val MANAGE_CHANNELS_EDITOR_TEST_TAG = "manage-channels-editor"
private const val MANAGE_CHANNELS_ERROR_TEST_TAG = "manage-channels-error"
private const val MANAGE_CHANNEL_ROW_TEST_TAG_PREFIX = "manage-channel-row-"
private val MANAGE_CHANNEL_ROW_HEIGHT = 88.dp
