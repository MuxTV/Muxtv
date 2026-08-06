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
import app.muxtv.catalog.GuideProgrammeCell
import app.muxtv.catalog.GuideProgrammeKey
import app.muxtv.catalog.GuideProjectionState
import app.muxtv.catalog.GuideWindowRepository
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

@Composable
fun GuideRoute(
    repository: GuideWindowRepository,
    profileId: String,
    onOpenChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
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
            GuideUiState.Loading -> GuideMessage("Загружаем программу…")
            GuideUiState.Empty -> GuideMessage("Нет доступных каналов.")
            GuideUiState.Failed -> GuideFailure(
                message = "Не удалось загрузить программу.",
                onRetry = onRetry,
                onResetToFirstPage = onResetToFirstPage,
            )
            GuideUiState.Incomplete -> GuideFailure(
                message = "Программа слишком большая для безопасного окна. Попробуйте ещё раз.",
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
) {
    val listState = rememberLazyListState()
    val rowCells = remember(state) {
        state.rows.map { row -> row.toCells(state.viewport) }
    }
    val focusChannels = remember(state, rowCells) {
        state.rows.mapIndexed { index, row ->
            GuideFocusChannel(
                channelId = row.channel.channelId,
                programmeKeys = rowCells[index].mapNotNull(GuideCellUi::programmeKey),
            )
        }
    }
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
    val narrowed = spanMillis < GuideViewportPolicy.DEFAULT_TIME_SPAN_MILLIS

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        Text(
            text = if (narrowed) {
                "Безопасное окно: ${formatDuration(spanMillis)}"
            } else {
                "Окно: ${formatDuration(spanMillis)}"
            },
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
    )

    TimelineHeader(
        viewport = state.viewport,
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
                onFocused = { cellIndex, cell ->
                    focusedDetail = cell.detailLabel(row.channel.displayName)
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
) {
    if (!viewport.hasMoreChannels && !viewport.canResetToFirstPage) return

    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        if (viewport.canGoPrevious) {
            MuxTvActionButton(
                text = "Предыдущие каналы",
                onClick = onPreviousPage,
                modifier = Modifier.testTag(GUIDE_PREVIOUS_PAGE_TAG),
            )
        }
        if (viewport.canResetToFirstPage) {
            MuxTvActionButton(
                text = "В начало",
                onClick = onResetToFirstPage,
                modifier = Modifier.testTag(GUIDE_FIRST_PAGE_TAG),
            )
        }
        if (viewport.hasMoreChannels) {
            MuxTvActionButton(
                text = "Следующие каналы",
                onClick = onNextPage,
                modifier = Modifier.testTag(GUIDE_NEXT_PAGE_TAG),
            )
        }
    }
}

@Composable
private fun TimelineHeader(
    viewport: GuideViewport,
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
            timelineTicks(viewport).forEach { tick ->
                Text(
                    text = formatTime(tick),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.offset(
                        x = epochOffsetDp(tick, viewport.fromEpochMillis),
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
    row: GuideRow,
    cells: List<GuideCellUi>,
    requesters: List<FocusRequester>,
    viewport: GuideViewport,
    totalWidth: Dp,
    timeOffset: Dp,
    nowEpochMillis: Long,
    onFocused: (Int, GuideCellUi) -> Unit,
    onOpenChannel: () -> Unit,
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
private fun ChannelRailCell(row: GuideRow) {
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
                text = buildString {
                    row.channel.channelNumber?.let { append(it).append("  ") }
                    if (row.channel.isFavorite) append("★ ")
                    append(row.channel.displayName)
                },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            row.channel.groupTitle?.let { group ->
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
    cell: GuideCellUi,
    viewport: GuideViewport,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
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
        MaterialTheme.colorScheme.outline
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
            if (cell.programmeKey != null) {
                Text(
                    text = "${formatTime(cell.startEpochMillis)}–${formatTime(cell.endEpochMillis)}",
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

private data class GuideCellUi(
    val programmeKey: GuideProgrammeKey?,
    val state: GuideProjectionState,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val title: String,
) {
    fun composeKey(): Any = programmeKey ?: "status:${state.name}"

    fun detailLabel(channelName: String): String = when (state) {
        GuideProjectionState.READY ->
            "$channelName · $title · ${formatTime(startEpochMillis)}–${formatTime(endEpochMillis)}"
        GuideProjectionState.NO_GUIDE -> "$channelName · программа не найдена"
        GuideProjectionState.SOURCE_CONFLICT -> "$channelName · конфликт источников программы"
    }

    override fun toString(): String =
        "GuideCellUi(state=$state, programmePresent=${programmeKey != null}, " +
            "startEpochMillis=$startEpochMillis, endEpochMillis=$endEpochMillis)"
}

private fun GuideRow.toCells(viewport: GuideViewport): List<GuideCellUi> {
    if (state == GuideProjectionState.NO_GUIDE) {
        return listOf(statusCell(viewport, state, "Нет программы"))
    }
    if (state == GuideProjectionState.SOURCE_CONFLICT) {
        return listOf(statusCell(viewport, state, "Конфликт источников"))
    }

    val visible = programmes
        .asSequence()
        .filter { programme ->
            programme.endEpochMillis > viewport.fromEpochMillis &&
                programme.startEpochMillis < viewport.toEpochMillis
        }
        .sortedBy(GuideProgrammeCell::startEpochMillis)
        .map { programme ->
            GuideCellUi(
                programmeKey = programme.key,
                state = GuideProjectionState.READY,
                startEpochMillis = maxOf(programme.startEpochMillis, viewport.fromEpochMillis),
                endEpochMillis = minOf(programme.endEpochMillis, viewport.toEpochMillis),
                title = programme.title?.takeIf(String::isNotBlank) ?: "Без названия",
            )
        }
        .toList()

    return visible.ifEmpty {
        listOf(statusCell(viewport, GuideProjectionState.READY, "Нет передач в этом окне"))
    }
}

private fun statusCell(
    viewport: GuideViewport,
    state: GuideProjectionState,
    title: String,
): GuideCellUi = GuideCellUi(
    programmeKey = null,
    state = state,
    startEpochMillis = viewport.fromEpochMillis,
    endEpochMillis = minOf(
        viewport.toEpochMillis,
        viewport.fromEpochMillis + STATUS_CELL_SPAN_MILLIS,
    ),
    title = title,
)

private fun timelineTicks(viewport: GuideViewport): List<Long> {
    val first = nextHalfHour(viewport.fromEpochMillis)
    if (first > viewport.toEpochMillis) return emptyList()
    val ticks = mutableListOf<Long>()
    var tick = first
    while (tick <= viewport.toEpochMillis) {
        ticks += tick
        if (tick > Long.MAX_VALUE - HALF_HOUR_MILLIS) break
        tick += HALF_HOUR_MILLIS
    }
    return ticks
}

private fun nextHalfHour(epochMillis: Long): Long {
    val remainder = Math.floorMod(epochMillis, HALF_HOUR_MILLIS)
    return if (remainder == 0L) epochMillis else epochMillis + (HALF_HOUR_MILLIS - remainder)
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

private fun formatTime(epochMillis: Long): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private fun formatDuration(durationMillis: Long): String {
    val minutes = durationMillis / MILLIS_PER_MINUTE
    val hours = minutes / 60L
    val remainingMinutes = minutes % 60L
    return when {
        remainingMinutes == 0L -> "$hours ч"
        hours == 0L -> "$remainingMinutes мин"
        else -> "$hours ч $remainingMinutes мин"
    }
}

private const val GUIDE_STATUS_TAG = "guide-status"
private const val GUIDE_RETRY_TAG = "guide-retry"
private const val GUIDE_PREVIOUS_PAGE_TAG = "guide-page-previous"
private const val GUIDE_FIRST_PAGE_TAG = "guide-page-first"
private const val GUIDE_NEXT_PAGE_TAG = "guide-page-next"
private const val DP_PER_MINUTE = 5f
private const val MINIMUM_TIMELINE_DP = 1f
private const val MILLIS_PER_MINUTE = 60_000L
private const val HALF_HOUR_MILLIS = 30L * MILLIS_PER_MINUTE
private const val STATUS_CELL_SPAN_MILLIS = HALF_HOUR_MILLIS
private const val CURRENT_TIME_REFRESH_MILLIS = 60_000L
private val CHANNEL_RAIL_WIDTH = 260.dp
private val TIME_HEADER_HEIGHT = 34.dp
private val GUIDE_ROW_HEIGHT = 72.dp
private val FOCUS_SCROLL_LEADING_SPACE = 32.dp
private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
