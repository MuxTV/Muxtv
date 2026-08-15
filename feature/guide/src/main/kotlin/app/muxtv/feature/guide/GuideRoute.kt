package app.muxtv.feature.guide

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import kotlinx.coroutines.delay

@Composable
fun GuideRoute(
    repository: GuideWindowRepository,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    val factory = remember(repository, profileId) {
        viewModelFactory {
            initializer {
                GuideViewModel(
                    repository = repository,
                    profileId = profileId,
                )
            }
        }
    }
    val screenViewModel: GuideViewModel = viewModel(factory = factory)
    val state by screenViewModel.uiState.collectAsStateWithLifecycle()

    GuideScreen(
        state = state,
        focusAnchor = screenViewModel.currentFocusAnchor(),
        onFocusAnchorChanged = screenViewModel::updateFocusAnchor,
        onRetry = screenViewModel::reload,
        onPreviousPage = screenViewModel::loadPreviousPage,
        onNextPage = screenViewModel::loadNextPage,
        onResetToFirstPage = screenViewModel::resetToFirstPage,
        onOpenChannel = onOpenChannel,
        railFocusRequester = railFocusRequester,
        modifier = modifier,
    )
}

@Composable
private fun GuideScreen(
    state: GuideUiState,
    focusAnchor: GuideFocusAnchor?,
    onFocusAnchorChanged: (GuideFocusAnchor) -> Unit,
    onRetry: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onResetToFirstPage: () -> Unit,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 48.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        Text(
            text = "Телепрограмма",
            style = MaterialTheme.typography.displaySmall,
        )

        when (state) {
            GuideUiState.Loading -> GuideMessage(state.statusLabel)
            GuideUiState.Empty -> GuideFailure(
                message = state.statusLabel,
                onRetry = onRetry,
                onResetToFirstPage = onResetToFirstPage,
            )
            GuideUiState.Failed -> GuideFailure(
                message = state.statusLabel,
                onRetry = onRetry,
                onResetToFirstPage = onResetToFirstPage,
            )
            GuideUiState.Incomplete -> GuideFailure(
                message = state.statusLabel,
                onRetry = onRetry,
                onResetToFirstPage = onResetToFirstPage,
            )
            is GuideUiState.Content -> GuideContent(
                state = state,
                focusAnchor = focusAnchor,
                onFocusAnchorChanged = onFocusAnchorChanged,
                onPreviousPage = onPreviousPage,
                onNextPage = onNextPage,
                onResetToFirstPage = onResetToFirstPage,
                onOpenChannel = onOpenChannel,
                railFocusRequester = railFocusRequester,
            )
        }
    }
}

@Composable
private fun GuideMessage(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(GUIDE_STATUS_TAG),
    )
}

@Composable
private fun GuideFailure(
    message: String,
    onRetry: () -> Unit,
    onResetToFirstPage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        GuideMessage(message)
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = "Повторить",
                onClick = onRetry,
                modifier = Modifier.testTag(GUIDE_RETRY_TAG),
            )
            MuxTvActionButton(
                text = "В начало",
                onClick = onResetToFirstPage,
                modifier = Modifier.testTag(GUIDE_FIRST_PAGE_TAG),
            )
        }
    }
}

@Composable
private fun GuideContent(
    state: GuideUiState.Content,
    focusAnchor: GuideFocusAnchor?,
    onFocusAnchorChanged: (GuideFocusAnchor) -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onResetToFirstPage: () -> Unit,
    onOpenChannel: (String) -> Unit,
    railFocusRequester: FocusRequester? = null,
) {
    val listState = rememberLazyListState()
    val rowCells = remember(state) { state.rows.map(GuideRowProjection::cells) }
    val focusChannels = remember(state) { state.rows.map(GuideRowProjection::focusChannel) }
    val requesterShape = remember(rowCells) { rowCells.map { cells -> cells.size } }
    val requesters = remember(requesterShape) {
        requesterShape.map { count -> List(count) { FocusRequester() } }
    }
    val pageIdentity = state.rows.firstOrNull()?.channel?.channelId
    var initialFocusRestored by remember(pageIdentity) { mutableStateOf(false) }
    var focusedDetail by remember(pageIdentity) { mutableStateOf<String?>(null) }
    var timeOffset by remember(pageIdentity) { mutableStateOf(0.dp) }
    var nowEpochMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(CURRENT_TIME_REFRESH_MILLIS)
            nowEpochMillis = System.currentTimeMillis()
        }
    }

    LaunchedEffect(pageIdentity, focusAnchor, focusChannels, initialFocusRestored) {
        val exactIdentitySurvives = focusAnchor?.hasExactIdentityIn(focusChannels) == true
        if (initialFocusRestored && (focusAnchor == null || exactIdentitySurvives)) {
            return@LaunchedEffect
        }
        if (state.rows.isEmpty()) return@LaunchedEffect

        val target = focusAnchor?.resolveAgainst(focusChannels)
        val rowIndex = target?.channelIndex ?: 0
        val cellIndex = target?.programmeIndex ?: 0
        listState.scrollToItem(rowIndex)
        withFrameNanos { }
        requesters.getOrNull(rowIndex)?.getOrNull(cellIndex)?.requestFocus()
        initialFocusRestored = true
    }

    val spanMillis = state.viewport.toEpochMillis - state.viewport.fromEpochMillis
    val totalTimelineWidth = timelineWidth(spanMillis)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        Text(
            text = state.statusLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).testTag(GUIDE_STATUS_TAG),
        )
        if (focusedDetail != null) {
            Text(
                text = focusedDetail.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(2f),
            )
        }
    }

    GuidePager(
        viewport = state.viewport,
        onPreviousPage = onPreviousPage,
        onNextPage = onNextPage,
        onResetToFirstPage = onResetToFirstPage,
        railFocusRequester = railFocusRequester,
    )

    TimelineHeader(
        viewport = state.viewport,
        ticks = state.window.ticks,
        totalWidth = totalTimelineWidth,
        timeOffset = timeOffset,
        nowEpochMillis = nowEpochMillis,
    )

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = state.rows,
            key = { _, row -> row.channel.channelId },
        ) { rowIndex, row ->
            GuideTimelineRow(
                rowIndex = rowIndex,
                row = row,
                cells = rowCells[rowIndex],
                requesters = requesters[rowIndex],
                viewport = state.viewport,
                totalWidth = totalTimelineWidth,
                timeOffset = timeOffset,
                nowEpochMillis = nowEpochMillis,
                railFocusRequester = railFocusRequester,
                onFocused = { cellIndex, cell ->
                    focusedDetail = cell.detailLabel
                    onFocusAnchorChanged(
                        GuideFocusAnchor(
                            channelId = row.channel.channelId,
                            programmeKey = cell.programmeKey,
                            previousChannelIndex = rowIndex,
                            previousProgrammeIndex = cellIndex,
                        ),
                    )
                    timeOffset = (
                        epochOffsetDp(
                            epochMillis = cell.startEpochMillis,
                            viewportStartEpochMillis = state.viewport.fromEpochMillis,
                        ) - FOCUS_SCROLL_LEADING_SPACE
                    ).coerceAtLeast(0.dp)
                },
                onOpenChannel = { onOpenChannel(row.channel.channelId) },
            )
        }
    }
}

@Composable
private fun GuidePager(
    viewport: GuideViewport,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onResetToFirstPage: () -> Unit,
    railFocusRequester: FocusRequester? = null,
) {
    if (!viewport.hasMoreChannels && !viewport.canResetToFirstPage) return

    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        if (viewport.canGoPrevious) {
            MuxTvActionButton(
                text = "Предыдущие каналы",
                onClick = onPreviousPage,
                modifier = Modifier.testTag(GUIDE_PREVIOUS_PAGE_TAG)
                    .focusProperties { left = railFocusRequester ?: FocusRequester.Default },
            )
        }
        if (viewport.canResetToFirstPage) {
            MuxTvActionButton(
                text = "В начало",
                onClick = onResetToFirstPage,
                modifier = Modifier.testTag(GUIDE_FIRST_PAGE_TAG)
                    .focusProperties { left = railFocusRequester ?: FocusRequester.Default },
            )
        }
        if (viewport.hasMoreChannels) {
            MuxTvActionButton(
                text = "Следующие каналы",
                onClick = onNextPage,
                modifier = Modifier.testTag(GUIDE_NEXT_PAGE_TAG)
                    .focusProperties { left = railFocusRequester ?: FocusRequester.Default },
            )
        }
    }
}

@Composable
private fun TimelineHeader(
    viewport: GuideViewport,
    ticks: List<GuideTickProjection>,
    totalWidth: Dp,
    timeOffset: Dp,
    nowEpochMillis: Long,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .width(CHANNEL_RAIL_WIDTH)
                .height(TIME_HEADER_HEIGHT)
                .padding(end = 12.dp),
        ) {
            Text(
                text = "Канал",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TimelineViewport(
            totalWidth = totalWidth,
            requestedOffset = timeOffset,
            height = TIME_HEADER_HEIGHT,
            modifier = Modifier.weight(1f),
        ) {
            ticks.forEach { tick ->
                Text(
                    text = tick.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(
                        x = epochOffsetDp(tick.epochMillis, viewport.fromEpochMillis),
                    ),
                )
            }
            CurrentTimeMarker(
                viewport = viewport,
                nowEpochMillis = nowEpochMillis,
                height = TIME_HEADER_HEIGHT,
            )
        }
    }
}

@Composable
private fun GuideTimelineRow(
    rowIndex: Int,
    row: GuideRowProjection,
    cells: List<GuideCellProjection>,
    requesters: List<FocusRequester>,
    viewport: GuideViewport,
    totalWidth: Dp,
    timeOffset: Dp,
    nowEpochMillis: Long,
    onFocused: (Int, GuideCellProjection) -> Unit,
    onOpenChannel: () -> Unit,
    railFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(GUIDE_ROW_HEIGHT),
    ) {
        ChannelRailCell(row = row)
        TimelineViewport(
            totalWidth = totalWidth,
            requestedOffset = timeOffset,
            height = GUIDE_ROW_HEIGHT,
            modifier = Modifier.weight(1f),
        ) {
            cells.forEachIndexed { cellIndex, cell ->
                key(cell.composeKey()) {
                    ProgrammeCell(
                        rowIndex = rowIndex,
                        cellIndex = cellIndex,
                        cell = cell,
                        viewport = viewport,
                        focusRequester = requesters[cellIndex],
                        onFocused = { onFocused(cellIndex, cell) },
                        onClick = onOpenChannel,
                        leftFocusRequester = if (cellIndex == 0) railFocusRequester else null,
                    )
                }
            }
            CurrentTimeMarker(
                viewport = viewport,
                nowEpochMillis = nowEpochMillis,
                height = GUIDE_ROW_HEIGHT,
            )
        }
    }
}

@Composable
private fun TimelineViewport(
    totalWidth: Dp,
    requestedOffset: Dp,
    height: Dp,
    modifier: Modifier,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier
            .height(height)
            .clipToBounds(),
    ) {
        val maximumOffset = (totalWidth - maxWidth).coerceAtLeast(0.dp)
        val appliedOffset = requestedOffset.coerceIn(0.dp, maximumOffset)
        Box(
            modifier = Modifier
                .offset(x = 0.dp - appliedOffset)
                .width(totalWidth)
                .height(height),
            content = content,
        )
    }
}

@Composable
private fun ChannelRailCell(row: GuideRowProjection) {
    Box(
        modifier = Modifier
            .width(CHANNEL_RAIL_WIDTH)
            .height(GUIDE_ROW_HEIGHT)
            .padding(end = 12.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = row.primaryLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.groupLabel?.let { group ->
                Text(
                    text = group,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProgrammeCell(
    rowIndex: Int,
    cellIndex: Int,
    cell: GuideCellProjection,
    viewport: GuideViewport,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    leftFocusRequester: FocusRequester? = null,
) {
    var focused by remember(cell) { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val background = if (focused) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        when (cell.state) {
            GuideProjectionState.READY -> MaterialTheme.colorScheme.surfaceVariant
            GuideProjectionState.NO_GUIDE -> MaterialTheme.colorScheme.surface
            GuideProjectionState.SOURCE_CONFLICT -> MaterialTheme.colorScheme.errorContainer
        }
    }
    val borderColor = if (focused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.border
    }
    val start = maxOf(cell.startEpochMillis, viewport.fromEpochMillis)
    val end = minOf(cell.endEpochMillis, viewport.toEpochMillis)
    val width = durationWidth(end - start)

    Box(
        modifier = Modifier
            .offset(x = epochOffsetDp(start, viewport.fromEpochMillis))
            .width(width)
            .height(GUIDE_ROW_HEIGHT)
            .focusRequester(focusRequester)
            .focusProperties { left = leftFocusRequester ?: FocusRequester.Default }
            .onFocusChanged { focusState ->
                focused = focusState.isFocused
                if (focusState.isFocused) onFocused()
            }
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .background(background, shape)
            .border(2.dp, borderColor, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag("guide-cell-$rowIndex-$cellIndex"),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = cell.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            cell.timeLabel?.let { timeLabel ->
                Text(
                    text = timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun CurrentTimeMarker(
    viewport: GuideViewport,
    nowEpochMillis: Long,
    height: Dp,
) {
    if (nowEpochMillis !in viewport.fromEpochMillis..viewport.toEpochMillis) return
    Box(
        modifier = Modifier
            .offset(x = epochOffsetDp(nowEpochMillis, viewport.fromEpochMillis))
            .width(2.dp)
            .height(height)
            .background(MaterialTheme.colorScheme.primary),
    )
}

private fun timelineWidth(spanMillis: Long): Dp = durationWidth(spanMillis)

private fun durationWidth(durationMillis: Long): Dp {
    val minutes = durationMillis.toFloat() / MILLIS_PER_MINUTE.toFloat()
    return maxOf(MINIMUM_TIMELINE_DP, minutes * DP_PER_MINUTE).dp
}

private fun epochOffsetDp(
    epochMillis: Long,
    viewportStartEpochMillis: Long,
): Dp {
    val minutes = (epochMillis - viewportStartEpochMillis).toFloat() / MILLIS_PER_MINUTE.toFloat()
    return maxOf(0f, minutes * DP_PER_MINUTE).dp
}

private const val GUIDE_STATUS_TAG = "guide-status"
private const val GUIDE_RETRY_TAG = "guide-retry"
private const val GUIDE_PREVIOUS_PAGE_TAG = "guide-page-previous"
private const val GUIDE_FIRST_PAGE_TAG = "guide-page-first"
private const val GUIDE_NEXT_PAGE_TAG = "guide-page-next"
private const val DP_PER_MINUTE = 5f
private const val MINIMUM_TIMELINE_DP = 1f
private const val MILLIS_PER_MINUTE = 60_000L
private const val CURRENT_TIME_REFRESH_MILLIS = 60_000L
private val CHANNEL_RAIL_WIDTH = 260.dp
private val TIME_HEADER_HEIGHT = 34.dp
private val GUIDE_ROW_HEIGHT = 72.dp
private val FOCUS_SCROLL_LEADING_SPACE = 32.dp
