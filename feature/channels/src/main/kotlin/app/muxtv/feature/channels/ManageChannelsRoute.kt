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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
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
import app.muxtv.catalog.ChannelPreferenceMutationResult
import app.muxtv.catalog.ChannelPreferencesRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.designsystem.component.MuxTvScreenScaffold
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

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
    val mutationResult by screenViewModel.lastMutationResult.collectAsStateWithLifecycle()
    val rowsFlow = remember(screenViewModel, filter) { screenViewModel.rowsFor(filter) }
    val rows = rowsFlow.collectAsLazyPagingItems()
    val listState = rememberLazyListState()

    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedScrollOffset by rememberSaveable { mutableIntStateOf(0) }
    var recoverySignal by rememberSaveable { mutableIntStateOf(0) }
    var selectedRow by remember { mutableStateOf<ManageChannelRowUiModel?>(null) }
    var editor by remember { mutableStateOf<ManageEditorState?>(null) }
    var editorPending by remember { mutableStateOf(false) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val focusAnchor = focusedChannelId?.let { id ->
        FocusAnchor(id, focusedChannelIndex, focusedScrollOffset)
    }

    LaunchedEffect(mutationResult, editorPending) {
        val result = mutationResult ?: return@LaunchedEffect
        if (editorPending) {
            when (result) {
                ChannelPreferenceMutationResult.Applied,
                ChannelPreferenceMutationResult.Unchanged,
                -> {
                    editor = null
                    editorPending = false
                    editorError = null
                }

                ChannelPreferenceMutationResult.InvalidInput -> {
                    editorPending = false
                    editorError = "Проверьте введённое значение."
                }

                ChannelPreferenceMutationResult.NotFound -> {
                    editor = null
                    editorPending = false
                    editorError = null
                    statusMessage = "Канал больше недоступен."
                }
            }
            screenViewModel.clearMutationResult()
        } else {
            if (result == ChannelPreferenceMutationResult.NotFound) {
                statusMessage = "Канал больше недоступен."
            }
            screenViewModel.clearMutationResult()
        }
    }

    ManageChannelsContent(
        rows = rows,
        filter = filter,
        listState = listState,
        focusAnchor = focusAnchor,
        recoverySignal = recoverySignal,
        statusMessage = statusMessage,
        onFilterChanged = screenViewModel::setFilter,
        onFocusAnchorChanged = { anchor ->
            focusedChannelId = anchor.itemKey
            focusedChannelIndex = anchor.previousIndex
            focusedScrollOffset = anchor.scrollOffset
        },
        onOpenActions = { row ->
            statusMessage = null
            screenViewModel.clearMutationResult()
            selectedRow = row
        },
        railFocusRequester = railFocusRequester,
        modifier = modifier,
    )

    selectedRow?.let { row ->
        ManageActionsDialog(
            row = row,
            onToggleHidden = {
                val disappears = when (filter) {
                    ManageChannelsFilter.ALL -> false
                    ManageChannelsFilter.VISIBLE -> !row.isHidden
                    ManageChannelsFilter.HIDDEN -> row.isHidden
                }
                if (disappears) recoverySignal += 1
                selectedRow = null
                screenViewModel.clearMutationResult()
                screenViewModel.setHidden(row.channelId, !row.isHidden)
            },
            onRename = {
                selectedRow = null
                editorError = null
                screenViewModel.clearMutationResult()
                editor = ManageEditorState(
                    row = row,
                    mode = ManageEditorMode.NAME,
                    value = row.displayName,
                )
            },
            onChangeNumber = {
                selectedRow = null
                editorError = null
                screenViewModel.clearMutationResult()
                editor = ManageEditorState(
                    row = row,
                    mode = ManageEditorMode.NUMBER,
                    value = row.customChannelNumber?.toString().orEmpty(),
                )
            },
            onReset = {
                if (filter == ManageChannelsFilter.HIDDEN && row.isHidden) recoverySignal += 1
                selectedRow = null
                screenViewModel.clearMutationResult()
                screenViewModel.resetCustomization(row.channelId)
            },
            onDismiss = { selectedRow = null },
        )
    }

    editor?.let { current ->
        ManageEditorDialog(
            state = current,
            pending = editorPending,
            errorMessage = editorError,
            onValueChange = { value ->
                editorError = null
                editor = current.copy(
                    value = when (current.mode) {
                        ManageEditorMode.NAME -> value
                        ManageEditorMode.NUMBER -> value.filter(Char::isDigit).take(4)
                    },
                )
            },
            onSave = {
                editorError = null
                screenViewModel.clearMutationResult()
                when (current.mode) {
                    ManageEditorMode.NAME -> {
                        editorPending = true
                        screenViewModel.setCustomName(current.row.channelId, current.value)
                    }

                    ManageEditorMode.NUMBER -> {
                        val number = current.value.takeIf(String::isNotBlank)?.toIntOrNull()
                        if (current.value.isNotBlank() && number == null) {
                            editorError = "Введите номер от 1 до 9999."
                        } else {
                            editorPending = true
                            screenViewModel.setChannelNumber(current.row.channelId, number)
                        }
                    }
                }
            },
            onRestoreDefault = {
                editorError = null
                screenViewModel.clearMutationResult()
                editorPending = true
                when (current.mode) {
                    ManageEditorMode.NAME -> screenViewModel.setCustomName(current.row.channelId, null)
                    ManageEditorMode.NUMBER -> screenViewModel.setChannelNumber(current.row.channelId, null)
                }
            },
            onDismiss = {
                if (!editorPending) {
                    editor = null
                    editorError = null
                    screenViewModel.clearMutationResult()
                }
            },
        )
    }
}

@Composable
private fun ManageChannelsContent(
    rows: LazyPagingItems<ManageChannelRowUiModel>,
    filter: ManageChannelsFilter,
    listState: LazyListState,
    focusAnchor: FocusAnchor?,
    recoverySignal: Int,
    statusMessage: String?,
    onFilterChanged: (ManageChannelsFilter) -> Unit,
    onFocusAnchorChanged: (FocusAnchor) -> Unit,
    onOpenActions: (ManageChannelRowUiModel) -> Unit,
    railFocusRequester: FocusRequester?,
    modifier: Modifier,
) {
    val focusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val allFocusRequester = remember { FocusRequester() }
    val visibleFocusRequester = remember { FocusRequester() }
    val hiddenFocusRequester = remember { FocusRequester() }
    val selectedFilterRequester = when (filter) {
        ManageChannelsFilter.ALL -> allFocusRequester
        ManageChannelsFilter.VISIBLE -> visibleFocusRequester
        ManageChannelsFilter.HIDDEN -> hiddenFocusRequester
    }
    var handledRecoverySignal by remember(filter) { mutableIntStateOf(-1) }
    val refreshState = rows.loadState.refresh

    LaunchedEffect(filter, refreshState, rows.itemCount, recoverySignal, focusAnchor) {
        if (handledRecoverySignal == recoverySignal || refreshState !is LoadState.NotLoading) {
            return@LaunchedEffect
        }
        if (rows.itemCount == 0) {
            withFrameNanos { }
            selectedFilterRequester.requestFocus()
            handledRecoverySignal = recoverySignal
            return@LaunchedEffect
        }

        val loadedKeys = (0 until rows.itemCount).mapNotNull { index -> rows.peek(index)?.channelId }
        if (loadedKeys.isEmpty()) return@LaunchedEffect
        val target = focusAnchor?.resolveAgainst(loadedKeys)
            ?: FocusTarget(loadedKeys.first(), 0, 0)
        listState.scrollToItem(target.index.coerceAtMost(rows.itemCount - 1), target.scrollOffset)
        val requester = snapshotFlow { focusRequesters[target.itemKey] }
            .filterNotNull()
            .first()
        withFrameNanos { }
        if (requester.requestFocus()) handledRecoverySignal = recoverySignal
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
                    .testTag(MANAGE_ALL_FILTER_TEST_TAG)
                    .focusRequester(allFocusRequester)
                    .focusProperties {
                        left = railFocusRequester ?: FocusRequester.Default
                        right = visibleFocusRequester
                    },
            )
            MuxTvActionButton(
                text = "Видимые",
                onClick = { onFilterChanged(ManageChannelsFilter.VISIBLE) },
                selected = filter == ManageChannelsFilter.VISIBLE,
                modifier = Modifier
                    .testTag(MANAGE_VISIBLE_FILTER_TEST_TAG)
                    .focusRequester(visibleFocusRequester)
                    .focusProperties {
                        left = allFocusRequester
                        right = hiddenFocusRequester
                    },
            )
            MuxTvActionButton(
                text = "Скрытые",
                onClick = { onFilterChanged(ManageChannelsFilter.HIDDEN) },
                selected = filter == ManageChannelsFilter.HIDDEN,
                modifier = Modifier
                    .testTag(MANAGE_HIDDEN_FILTER_TEST_TAG)
                    .focusRequester(hiddenFocusRequester)
                    .focusProperties { left = visibleFocusRequester },
            )
        }

        statusMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        when {
            refreshState is LoadState.Loading && rows.itemCount == 0 ->
                Text("Загрузка каналов…", color = MaterialTheme.colorScheme.onSurfaceVariant)

            refreshState is LoadState.Error && rows.itemCount == 0 ->
                MuxTvActionButton(text = "Повторить", onClick = rows::retry)

            refreshState is LoadState.NotLoading && rows.itemCount == 0 ->
                Text(filter.emptyMessage(), color = MaterialTheme.colorScheme.onSurfaceVariant)

            else -> {
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
                        key = rows.itemKey(ManageChannelRowUiModel::channelId),
                        contentType = rows.itemContentType { "manage-channel-row" },
                    ) { index ->
                        val row = rows[index]
                        if (row == null) {
                            Text("Загрузка…")
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
                            fun captureAnchor() = onFocusAnchorChanged(
                                FocusAnchor(
                                    itemKey = row.channelId,
                                    previousIndex = index,
                                    scrollOffset = listState.firstVisibleItemScrollOffset,
                                ),
                            )
                            ManageChannelRow(
                                row = row,
                                onClick = {
                                    captureAnchor()
                                    onOpenActions(row)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("$MANAGE_ROW_TEST_TAG_PREFIX$index")
                                    .focusRequester(focusRequester)
                                    .focusProperties {
                                        if (index == 0) {
                                            up = selectedFilterRequester
                                            left = railFocusRequester ?: FocusRequester.Default
                                        }
                                    }
                                    .onFocusChanged { state ->
                                        if (state.isFocused) captureAnchor()
                                    },
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
}

@Composable
private fun ManageChannelRow(
    row: ManageChannelRowUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.rowCorner)
    val metadata = buildList {
        if (row.isHidden) add("Скрыт") else add("Виден")
        if (row.isFavorite) add("Избранное")
        if (row.hasCustomName) add("Имя изменено")
        if (row.hasCustomNumber) add("Номер изменён")
        if (row.variantCount > 1) add("${row.variantCount} источника")
    }.joinToString("  ·  ")

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
        Box(modifier = Modifier.width(72.dp), contentAlignment = Alignment.Center) {
            Text(
                text = row.channelNumber?.takeIf(String::isNotBlank) ?: "—",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(TvTokens.Spacing.small))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.displayName,
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.originalDisplayName?.let { "Исходное имя: $it" } ?: metadata,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (row.originalDisplayName != null) {
            Spacer(Modifier.width(TvTokens.Spacing.medium))
            Text(
                text = metadata,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (row.hasCustomNumber) {
            Spacer(Modifier.width(TvTokens.Spacing.medium))
            Text(
                text = "По умолчанию: ${row.defaultChannelNumber ?: "—"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ManageActionsDialog(
    row: ManageChannelRowUiModel,
    onToggleHidden: () -> Unit,
    onRename: () -> Unit,
    onChangeNumber: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        firstFocusRequester.requestFocus()
    }
    Dialog(onDismissRequest = onDismiss) {
        ModalCard(title = row.displayName) {
            MuxTvActionButton(
                text = if (row.isHidden) "Показать канал" else "Скрыть канал",
                onClick = onToggleHidden,
                modifier = Modifier
                    .testTag(MANAGE_TOGGLE_HIDDEN_TEST_TAG)
                    .focusRequester(firstFocusRequester),
            )
            MuxTvActionButton(text = "Переименовать", onClick = onRename)
            MuxTvActionButton(text = "Номер канала", onClick = onChangeNumber)
            MuxTvActionButton(text = "Сбросить настройки", onClick = onReset)
            MuxTvActionButton(text = "Закрыть", onClick = onDismiss)
        }
    }
}

@Composable
private fun ManageEditorDialog(
    state: ManageEditorState,
    pending: Boolean,
    errorMessage: String?,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onRestoreDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val inputFocusRequester = remember(state.mode, state.row.channelId) { FocusRequester() }
    LaunchedEffect(state.mode, state.row.channelId) {
        withFrameNanos { }
        inputFocusRequester.requestFocus()
    }
    Dialog(onDismissRequest = onDismiss) {
        ModalCard(
            title = when (state.mode) {
                ManageEditorMode.NAME -> "Название канала"
                ManageEditorMode.NUMBER -> "Номер канала"
            },
        ) {
            ManageTextInput(
                value = state.value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .testTag(
                        if (state.mode == ManageEditorMode.NAME) {
                            MANAGE_NAME_INPUT_TEST_TAG
                        } else {
                            MANAGE_NUMBER_INPUT_TEST_TAG
                        },
                    )
                    .focusRequester(inputFocusRequester),
            )
            if (state.mode == ManageEditorMode.NUMBER) {
                Text(
                    text = "Пустое значение вернёт номер провайдера: ${state.row.defaultChannelNumber ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                MuxTvActionButton(
                    text = "Сохранить",
                    onClick = onSave,
                    enabled = !pending,
                    modifier = Modifier.testTag(MANAGE_EDITOR_SAVE_TEST_TAG),
                )
                MuxTvActionButton(
                    text = if (state.mode == ManageEditorMode.NAME) {
                        "Исходное имя"
                    } else {
                        "Номер провайдера"
                    },
                    onClick = onRestoreDefault,
                    enabled = !pending,
                )
                MuxTvActionButton(text = "Отмена", onClick = onDismiss, enabled = !pending)
            }
        }
    }
}

@Composable
private fun ModalCard(
    title: String,
    content: @Composable Column.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.62f)
            .clip(RoundedCornerShape(TvTokens.Shape.detailsCorner))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.border,
                shape = RoundedCornerShape(TvTokens.Shape.detailsCorner),
            )
            .padding(TvTokens.Spacing.large),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

@Composable
private fun ManageTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (focused) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant,
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
    )
}

private enum class ManageEditorMode {
    NAME,
    NUMBER,
}

private data class ManageEditorState(
    val row: ManageChannelRowUiModel,
    val mode: ManageEditorMode,
    val value: String,
)

private fun ManageChannelsFilter.emptyMessage(): String = when (this) {
    ManageChannelsFilter.ALL -> "Активных каналов пока нет."
    ManageChannelsFilter.VISIBLE -> "Видимых каналов пока нет."
    ManageChannelsFilter.HIDDEN -> "Скрытых каналов пока нет."
}

private fun ManageChannelsFilter.countLabel(count: Int): String = when (this) {
    ManageChannelsFilter.ALL -> "Каналов: $count"
    ManageChannelsFilter.VISIBLE -> "Видимых: $count"
    ManageChannelsFilter.HIDDEN -> "Скрытых: $count"
}

internal const val MANAGE_CHANNELS_TITLE_TEST_TAG = "manage-channels-title"
internal const val MANAGE_ALL_FILTER_TEST_TAG = "manage-filter-all"
internal const val MANAGE_VISIBLE_FILTER_TEST_TAG = "manage-filter-visible"
internal const val MANAGE_HIDDEN_FILTER_TEST_TAG = "manage-filter-hidden"
internal const val MANAGE_ROW_TEST_TAG_PREFIX = "manage-channel-row-"
internal const val MANAGE_TOGGLE_HIDDEN_TEST_TAG = "manage-toggle-hidden"
internal const val MANAGE_NAME_INPUT_TEST_TAG = "manage-name-input"
internal const val MANAGE_NUMBER_INPUT_TEST_TAG = "manage-number-input"
internal const val MANAGE_EDITOR_SAVE_TEST_TAG = "manage-editor-save"
