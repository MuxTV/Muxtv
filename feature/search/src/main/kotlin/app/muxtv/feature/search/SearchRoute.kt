package app.muxtv.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.ChannelSearchRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun SearchRoute(
    repository: ChannelSearchRepository,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(repository, profileId) {
        viewModelFactory {
            initializer {
                SearchViewModel(
                    repository = repository,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: SearchViewModel = viewModel(factory = factory)
    val queryText by screenViewModel.queryText.collectAsStateWithLifecycle()
    val state by screenViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    var focusedChannelId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusedChannelIndex by rememberSaveable { mutableIntStateOf(0) }
    var focusedScrollOffset by rememberSaveable { mutableIntStateOf(0) }

    val focusAnchor = focusedChannelId?.let { channelId ->
        SearchFocusAnchor(
            channelId = channelId,
            previousIndex = focusedChannelIndex,
            scrollOffset = focusedScrollOffset,
        )
    }

    SearchContent(
        queryText = queryText,
        state = state,
        listState = listState,
        focusAnchor = focusAnchor,
        onQueryTextChanged = { value ->
            if (value != queryText) {
                focusedChannelId = null
                focusedChannelIndex = 0
                focusedScrollOffset = 0
            }
            screenViewModel.setQueryText(value)
        },
        onRetry = screenViewModel::retry,
        onFocusAnchorChanged = { anchor ->
            focusedChannelId = anchor.channelId
            focusedChannelIndex = anchor.previousIndex
            focusedScrollOffset = anchor.scrollOffset
        },
        onOpenChannel = onOpenChannel,
        modifier = modifier,
    )
}

@Composable
private fun SearchContent(
    queryText: String,
    state: SearchUiState,
    listState: LazyListState,
    focusAnchor: SearchFocusAnchor?,
    onQueryTextChanged: (String) -> Unit,
    onRetry: () -> Unit,
    onFocusAnchorChanged: (SearchFocusAnchor) -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
) {
    val content = state as? SearchUiState.Content
    val rows = content?.rows.orEmpty()
    val inputFocusRequester = remember { FocusRequester() }
    val retryFocusRequester = remember { FocusRequester() }
    val resultFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    val channelIds = content?.channelIds.orEmpty()
    val restorationAnchor = remember(queryText, channelIds) { focusAnchor }
    var restorationCompleted by remember(queryText, channelIds) { mutableStateOf(false) }
    val downFocusRequester = channelIds.firstOrNull()
        ?.let(resultFocusRequesters::get)
        ?: retryFocusRequester.takeIf { state == SearchUiState.Failed }

    LaunchedEffect(restorationAnchor, rows.isEmpty()) {
        if (restorationAnchor == null || rows.isEmpty()) {
            withFrameNanos { }
            inputFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(queryText, focusAnchor) {
        if (focusAnchor == null && queryText.isNotEmpty() && listState.firstVisibleItemIndex != 0) {
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(channelIds, restorationAnchor, restorationCompleted) {
        if (restorationCompleted || restorationAnchor == null || channelIds.isEmpty()) {
            return@LaunchedEffect
        }

        val target = restorationAnchor.resolveAgainst(channelIds) ?: return@LaunchedEffect
        fun targetIsPlaced(): Boolean = listState.layoutInfo.visibleItemsInfo.any { item ->
            item.index == target.index && item.key == target.channelId
        }

        if (!targetIsPlaced()) {
            listState.scrollToItem(target.index)
        }
        snapshotFlow { targetIsPlaced() }.first { placed -> placed }

        val requester = snapshotFlow { resultFocusRequesters[target.channelId] }
            .filterNotNull()
            .first()
        requester.requestFocus()
        restorationCompleted = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(
            text = "Поиск",
            style = MaterialTheme.typography.displaySmall,
        )

        SearchInput(
            value = queryText,
            onValueChange = onQueryTextChanged,
            focusRequester = inputFocusRequester,
            downFocusRequester = downFocusRequester,
            onSearchAction = {
                val firstResultRequester = channelIds.firstOrNull()
                    ?.let(resultFocusRequesters::get)
                when {
                    firstResultRequester != null -> firstResultRequester.requestFocus()
                    state == SearchUiState.Failed -> onRetry()
                }
            },
        )

        Text(
            text = state.statusLabel,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SEARCH_STATUS_TEST_TAG),
        )

        if (state == SearchUiState.Failed) {
            MuxTvActionButton(
                text = "Повторить",
                onClick = onRetry,
                modifier = Modifier
                    .testTag(SEARCH_RETRY_TEST_TAG)
                    .focusProperties { up = inputFocusRequester }
                    .focusRequester(retryFocusRequester),
            )
        }

        if (rows.isNotEmpty()) {
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
                        resultFocusRequesters[row.channelId] = focusRequester
                        onDispose {
                            if (resultFocusRequesters[row.channelId] === focusRequester) {
                                resultFocusRequesters.remove(row.channelId)
                            }
                        }
                    }

                    fun captureAnchor() {
                        onFocusAnchorChanged(
                            SearchFocusAnchor(
                                channelId = row.channelId,
                                previousIndex = index,
                                scrollOffset = listState.firstVisibleItemScrollOffset,
                            ),
                        )
                    }

                    SearchResultItem(
                        row = row,
                        onClick = {
                            captureAnchor()
                            onOpenChannel(row.channelId)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(row.resultTestTag)
                            .focusProperties {
                                if (index == 0) up = inputFocusRequester
                            }
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) captureAnchor()
                            },
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    downFocusRequester: FocusRequester?,
    onSearchAction: () -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.cardCorner)
    val borderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.border
    }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SEARCH_INPUT_TEST_TAG)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionDown) {
                    downFocusRequester?.requestFocus()
                    downFocusRequester != null
                } else {
                    false
                }
            }
            .focusProperties {
                downFocusRequester?.let { down = it }
            }
            .focusRequester(focusRequester)
            .onFocusChanged { focusState -> isFocused = focusState.isFocused },
        textStyle = MaterialTheme.typography.titleLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearchAction() }),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, shape)
                    .border(TvTokens.Focus.outlineWidth, borderColor, shape)
                    .padding(horizontal = 24.dp, vertical = 18.dp),
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = "Название, номер, группа или программа",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun SearchResultItem(
    row: SearchRowProjection,
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
                text = row.primaryLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.metadataLabel.ifEmpty { " " },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.currentProgrammeLabel,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val SEARCH_INPUT_TEST_TAG = "search-input"
private const val SEARCH_STATUS_TEST_TAG = "search-status"
private const val SEARCH_RETRY_TEST_TAG = "search-retry"
