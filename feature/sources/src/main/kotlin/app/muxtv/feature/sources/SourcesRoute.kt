package app.muxtv.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.sync.SourceRefreshScheduler
import app.muxtv.database.SourceRefreshOverview
import app.muxtv.database.SourceRefreshPolicy
import app.muxtv.database.SourceRefreshRunState
import app.muxtv.database.SourceRefreshStore
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.designsystem.component.MuxTvEmptyState
import app.muxtv.designsystem.component.MuxTvScreenScaffold
import app.muxtv.designsystem.icon.MuxTvIcons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private sealed interface SourcesUiState {
    data object Loading : SourcesUiState
    data object Empty : SourcesUiState
    data object Failed : SourcesUiState
    data class Content(val sources: List<SourceRefreshOverview>) : SourcesUiState
}

private enum class SourcesFocusState {
    Loading,
    Empty,
    Failed,
    Content,
}

@Composable
fun SourcesRoute(
    refreshStore: SourceRefreshStore,
    refreshScheduler: SourceRefreshScheduler,
    onAddSource: () -> Unit,
    topNavigationFocusRequester: FocusRequester? = null,
    playbackApprovalActions: SourcePlaybackApprovalActions =
        SourcePlaybackApprovalActions.Unavailable,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    val scope = rememberCoroutineScope()
    val busySources = remember { mutableStateMapOf<String, Boolean>() }
    val addSourceFocusRequester = remember { FocusRequester() }
    val configureFocusRequesters = remember { mutableStateMapOf<String, FocusRequester>() }
    var mutationError by remember { mutableStateOf<String?>(null) }
    var detailsSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    val state by produceState<SourcesUiState>(
        initialValue = SourcesUiState.Loading,
        refreshStore,
    ) {
        refreshStore.observeOverviews()
            .catch { value = SourcesUiState.Failed }
            .collect { overviews ->
                value = if (overviews.isEmpty()) {
                    SourcesUiState.Empty
                } else {
                    SourcesUiState.Content(overviews)
                }
            }
    }
    val focusState = when (state) {
        SourcesUiState.Loading -> SourcesFocusState.Loading
        SourcesUiState.Empty -> SourcesFocusState.Empty
        SourcesUiState.Failed -> SourcesFocusState.Failed
        is SourcesUiState.Content -> SourcesFocusState.Content
    }

    LaunchedEffect(focusState) {
        withFrameNanos { }
        addSourceFocusRequester.requestFocus()
    }

    fun mutate(sourceId: String, operation: suspend () -> Unit) {
        if (busySources[sourceId] == true) return
        busySources[sourceId] = true
        mutationError = null
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutationError = "Не удалось изменить настройки источника."
            } finally {
                busySources.remove(sourceId)
            }
        }
    }

    fun dismissDetails() {
        val sourceId = detailsSourceId
        detailsSourceId = null
        scope.launch {
            withFrameNanos { }
            sourceId?.let { configureFocusRequesters[it]?.requestFocus() }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (val current = state) {
            SourcesUiState.Loading -> MessageRoute(
                message = "Загрузка источников…",
                addSourceFocusRequester = addSourceFocusRequester,
                topNavigationFocusRequester = topNavigationFocusRequester,
                railFocusRequester = railFocusRequester,
                onAddSource = onAddSource,
            )

            SourcesUiState.Empty -> EmptyRoute(
                addSourceFocusRequester = addSourceFocusRequester,
                railFocusRequester = railFocusRequester,
                onAddSource = onAddSource,
            )

            SourcesUiState.Failed -> MessageRoute(
                message = "Не удалось прочитать список источников.",
                addSourceFocusRequester = addSourceFocusRequester,
                topNavigationFocusRequester = topNavigationFocusRequester,
                railFocusRequester = railFocusRequester,
                onAddSource = onAddSource,
            )

            is SourcesUiState.Content -> SourcesContent(
                sources = current.sources,
                busySources = busySources,
                mutationError = mutationError,
                addSourceFocusRequester = addSourceFocusRequester,
                topNavigationFocusRequester = topNavigationFocusRequester,
                railFocusRequester = railFocusRequester,
                configureFocusRequesters = configureFocusRequesters,
                onAddSource = onAddSource,
                onOpenDetails = { sourceId -> detailsSourceId = sourceId },
                onRefreshNow = refreshScheduler::refreshNow,
            )
        }

        val detailsSource = (state as? SourcesUiState.Content)?.sources
            ?.firstOrNull { it.sourceId == detailsSourceId }
        if (detailsSource != null) {
            SourceDetailsSheet(
                source = detailsSource,
                mutationInFlight = busySources[detailsSource.sourceId] == true,
                onUpdatePolicy = { policy ->
                    mutate(policy.sourceId) { refreshScheduler.updatePolicy(policy) }
                },
                onRemovePolicy = { sourceId ->
                    mutate(sourceId) { refreshScheduler.removePolicy(sourceId) }
                },
                onResetPlaybackApprovals = { sourceId ->
                    mutate(sourceId) {
                        when (playbackApprovalActions.revokeAll(sourceId)) {
                            SourcePlaybackApprovalResetResult.Reset,
                            SourcePlaybackApprovalResetResult.Unchanged,
                            -> Unit

                            SourcePlaybackApprovalResetResult.SourceNotFound,
                            SourcePlaybackApprovalResetResult.AccessUnavailable,
                            -> mutationError = "Не удалось сбросить HTTP-разрешения источника."
                        }
                    }
                },
                onDismiss = ::dismissDetails,
            )
        }
    }
}

@Composable
private fun EmptyRoute(
    addSourceFocusRequester: FocusRequester,
    railFocusRequester: FocusRequester?,
    onAddSource: () -> Unit,
) {
    MuxTvScreenScaffold(title = "Источники") {
        Box(modifier = Modifier.fillMaxSize()) {
            MuxTvEmptyState(
                icon = MuxTvIcons.Sources,
                title = "Источников пока нет",
                description = "Добавьте M3U-ссылку, чтобы MuxTV собрал каталог каналов.",
                actionLabel = "Добавить источник",
                actionTestTag = SOURCES_ADD_TEST_TAG,
                onAction = onAddSource,
                actionModifier = Modifier
                    .focusRequester(addSourceFocusRequester)
                    .focusProperties { left = railFocusRequester ?: FocusRequester.Default },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun SourcesContent(
    sources: List<SourceRefreshOverview>,
    busySources: Map<String, Boolean>,
    mutationError: String?,
    addSourceFocusRequester: FocusRequester,
    topNavigationFocusRequester: FocusRequester?,
    railFocusRequester: FocusRequester?,
    configureFocusRequesters: MutableMap<String, FocusRequester>,
    onAddSource: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onRefreshNow: (String) -> Unit,
) {
    MuxTvScreenScaffold(title = "Источники") {
        MuxTvActionButton(
            text = "Добавить источник",
            onClick = onAddSource,
            modifier = Modifier
                .testTag(SOURCES_ADD_TEST_TAG)
                .then(
                    if (topNavigationFocusRequester != null || railFocusRequester != null) {
                        Modifier.focusProperties {
                            topNavigationFocusRequester?.let { up = it }
                            railFocusRequester?.let { left = it }
                        }
                    } else {
                        Modifier
                    },
                )
                .focusRequester(addSourceFocusRequester),
        )
        mutationError?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
        ) {
            items(
                items = sources,
                key = SourceRefreshOverview::sourceId,
            ) { source ->
                val configureRequester = remember(source.sourceId) { FocusRequester() }
                DisposableEffect(source.sourceId, configureRequester) {
                    configureFocusRequesters[source.sourceId] = configureRequester
                    onDispose {
                        if (configureFocusRequesters[source.sourceId] === configureRequester) {
                            configureFocusRequesters.remove(source.sourceId)
                        }
                    }
                }
                SourceCard(
                    source = source,
                    mutationInFlight = busySources[source.sourceId] == true,
                    configureFocusRequester = configureRequester,
                    onRefreshNow = onRefreshNow,
                    onConfigure = { onOpenDetails(source.sourceId) },
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: SourceRefreshOverview,
    mutationInFlight: Boolean,
    configureFocusRequester: FocusRequester,
    onRefreshNow: (String) -> Unit,
    onConfigure: () -> Unit,
) {
    val running = source.status?.state == SourceRefreshRunState.RUNNING
    val operationalControlsEnabled =
        !mutationInFlight && !running && source.hasCredentialReference
    val shape = RoundedCornerShape(TvTokens.Shape.rowCorner)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.borderVariant, shape)
            .padding(TvTokens.Spacing.medium),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        Text(
            text = source.sourceName,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            source.summaryLine(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            source.statusLabel(),
            style = MaterialTheme.typography.bodyLarge,
            color = source.statusColor(),
        )
        Spacer(Modifier.height(TvTokens.Spacing.xSmall))
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = if (running) "Обновляется…" else "Обновить сейчас",
                onClick = { onRefreshNow(source.sourceId) },
                enabled = operationalControlsEnabled,
            )
            MuxTvActionButton(
                text = "Настроить",
                onClick = onConfigure,
                modifier = Modifier
                    .testTag(sourceConfigureTestTag(source.sourceId))
                    .focusRequester(configureFocusRequester),
            )
        }
    }
}

@Composable
private fun SourceDetailsSheet(
    source: SourceRefreshOverview,
    mutationInFlight: Boolean,
    onUpdatePolicy: (SourceRefreshPolicy) -> Unit,
    onRemovePolicy: (String) -> Unit,
    onResetPlaybackApprovals: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val policy = source.policy ?: defaultPolicy(source.sourceId)
    val running = source.status?.state == SourceRefreshRunState.RUNNING
    val operationalControlsEnabled =
        !mutationInFlight && !running && source.hasCredentialReference
    val resetScheduleEnabled = !mutationInFlight && source.policy != null
    val firstActionFocusRequester = remember { FocusRequester() }
    val closeFocusRequester = remember { FocusRequester() }
    val detailsListState = rememberLazyListState()
    var initialFocusClaimed by remember(source.sourceId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.detailsCorner)

    androidx.activity.compose.BackHandler(onBack = onDismiss)

    LaunchedEffect(operationalControlsEnabled) {
        if (initialFocusClaimed) return@LaunchedEffect
        if (!operationalControlsEnabled) {
            detailsListState.scrollToItem(SOURCE_DETAILS_CLOSE_ITEM_INDEX)
        }
        withFrameNanos { }
        if (operationalControlsEnabled) {
            firstActionFocusRequester.requestFocus()
        } else {
            closeFocusRequester.requestFocus()
        }
        initialFocusClaimed = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.60f)
                .fillMaxHeight(0.82f)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup()
                .clip(shape)
                .background(TvTokens.Color.surfaceRaised)
                .border(1.dp, TvTokens.Color.dividerStrong, shape)
                .padding(TvTokens.Spacing.large)
                .testTag(SOURCE_DETAILS_TEST_TAG),
            verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
        ) {
            Text(
                text = "Настройка: ${source.sourceName}",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                source.statusLabel(),
                style = MaterialTheme.typography.bodyLarge,
                color = source.statusColor(),
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                state = detailsListState,
                verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
            ) {
                item(key = "schedule") {
                    MuxTvActionButton(
                        text = if (policy.enabled) "Расписание: включено" else "Расписание: выключено",
                        onClick = {
                            onUpdatePolicy(
                                policy.copy(
                                    enabled = !policy.enabled,
                                    updatedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        },
                        enabled = operationalControlsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(firstActionFocusRequester),
                    )
                }
                item(key = "interval") {
                    MuxTvActionButton(
                        text = "Интервал: ${policy.intervalMinutes.intervalLabel()}",
                        onClick = {
                            onUpdatePolicy(
                                policy.copy(
                                    intervalMinutes = policy.intervalMinutes.nextInterval(),
                                    updatedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        },
                        enabled = operationalControlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "network") {
                    MuxTvActionButton(
                        text = if (policy.unmeteredOnly) "Сеть: безлимитная" else "Сеть: любая",
                        onClick = {
                            onUpdatePolicy(
                                policy.copy(
                                    unmeteredOnly = !policy.unmeteredOnly,
                                    updatedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        },
                        enabled = operationalControlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "power") {
                    MuxTvActionButton(
                        text = if (policy.requiresCharging) {
                            "Питание: только зарядка"
                        } else {
                            "Питание: без ограничений"
                        },
                        onClick = {
                            onUpdatePolicy(
                                policy.copy(
                                    requiresCharging = !policy.requiresCharging,
                                    updatedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        },
                        enabled = operationalControlsEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "reset-schedule") {
                    MuxTvActionButton(
                        text = "Сбросить расписание",
                        onClick = { onRemovePolicy(source.sourceId) },
                        enabled = resetScheduleEnabled,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item(key = "reset-http") {
                    MuxTvActionButton(
                        text = "Сбросить HTTP-разрешения",
                        onClick = { onResetPlaybackApprovals(source.sourceId) },
                        enabled = operationalControlsEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SOURCE_HTTP_RESET_TEST_TAG),
                    )
                }
                item(key = "close") {
                    MuxTvActionButton(
                        text = "Готово",
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(SOURCE_DETAILS_CLOSE_TEST_TAG)
                            .focusRequester(closeFocusRequester),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageRoute(
    message: String,
    addSourceFocusRequester: FocusRequester,
    topNavigationFocusRequester: FocusRequester?,
    railFocusRequester: FocusRequester?,
    onAddSource: () -> Unit,
) {
    MuxTvScreenScaffold(title = "Источники") {
        MuxTvActionButton(
            text = "Добавить источник",
            onClick = onAddSource,
            modifier = Modifier
                .testTag(SOURCES_ADD_TEST_TAG)
                .then(
                    if (topNavigationFocusRequester != null || railFocusRequester != null) {
                        Modifier.focusProperties {
                            topNavigationFocusRequester?.let { up = it }
                            railFocusRequester?.let { left = it }
                        }
                    } else {
                        Modifier
                    },
                )
                .focusRequester(addSourceFocusRequester),
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun sourceConfigureTestTag(sourceId: String): String =
    "source-configure-$sourceId"

private fun SourceRefreshOverview.summaryLine(): String = buildString {
    append(
        if (hasCredentialReference) {
            "Защищённая ссылка сохранена"
        } else {
            "Защищённая ссылка отсутствует"
        },
    )
    append("  ·  ")
    append(if (activeRevision > 0) "Активная версия: $activeRevision" else "Активной версии нет")
}

private fun SourceRefreshOverview.statusLabel(): String = when (status?.state) {
    null, SourceRefreshRunState.IDLE -> "Ещё не обновлялся"
    SourceRefreshRunState.RUNNING -> "Обновление выполняется"
    SourceRefreshRunState.SUCCEEDED -> "Последнее обновление успешно"
    SourceRefreshRunState.FAILED -> "Последнее обновление завершилось ошибкой"
    SourceRefreshRunState.NEEDS_AUTH -> "Требуется повторная авторизация"
    SourceRefreshRunState.CANCELLED -> "Последнее обновление отменено"
}

@Composable
private fun SourceRefreshOverview.statusColor() = when (status?.state) {
    SourceRefreshRunState.FAILED,
    SourceRefreshRunState.NEEDS_AUTH,
    -> MaterialTheme.colorScheme.error

    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun defaultPolicy(sourceId: String): SourceRefreshPolicy = SourceRefreshPolicy(
    sourceId = sourceId,
    enabled = false,
    intervalMinutes = DEFAULT_INTERVAL_MINUTES,
    unmeteredOnly = false,
    requiresCharging = false,
    updatedAtEpochMillis = 0,
)

private fun Long.nextInterval(): Long = when (this) {
    15L -> 60L
    60L -> 360L
    360L -> 1_440L
    else -> 15L
}

private fun Long.intervalLabel(): String = when (this) {
    15L -> "15 мин"
    60L -> "1 ч"
    360L -> "6 ч"
    1_440L -> "24 ч"
    else -> "$this мин"
}

const val SOURCES_ADD_TEST_TAG = "sources-add"
const val SOURCE_HTTP_RESET_TEST_TAG = "source-http-reset"
const val SOURCE_DETAILS_TEST_TAG = "source-details"
const val SOURCE_DETAILS_CLOSE_TEST_TAG = "source-details-close"
private const val SOURCE_DETAILS_CLOSE_ITEM_INDEX = 6
private const val DEFAULT_INTERVAL_MINUTES = 60L