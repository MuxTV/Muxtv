package app.muxtv.feature.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
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
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val busySources = remember { mutableStateMapOf<String, Boolean>() }
    val addSourceFocusRequester = remember { FocusRequester() }
    var mutationError by remember { mutableStateOf<String?>(null) }
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

    when (val current = state) {
        SourcesUiState.Loading -> MessageRoute(
            message = "Загрузка источников…",
            addSourceFocusRequester = addSourceFocusRequester,
            onAddSource = onAddSource,
            modifier = modifier,
        )

        SourcesUiState.Empty -> MessageRoute(
            message = "Импортированных источников пока нет.",
            addSourceFocusRequester = addSourceFocusRequester,
            onAddSource = onAddSource,
            modifier = modifier,
        )

        SourcesUiState.Failed -> MessageRoute(
            message = "Не удалось прочитать список источников.",
            addSourceFocusRequester = addSourceFocusRequester,
            onAddSource = onAddSource,
            modifier = modifier,
        )

        is SourcesUiState.Content -> SourcesContent(
            sources = current.sources,
            busySources = busySources,
            mutationError = mutationError,
            addSourceFocusRequester = addSourceFocusRequester,
            onAddSource = onAddSource,
            onRefreshNow = refreshScheduler::refreshNow,
            onUpdatePolicy = { policy ->
                mutate(policy.sourceId) { refreshScheduler.updatePolicy(policy) }
            },
            onRemovePolicy = { sourceId ->
                mutate(sourceId) { refreshScheduler.removePolicy(sourceId) }
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun SourcesContent(
    sources: List<SourceRefreshOverview>,
    busySources: Map<String, Boolean>,
    mutationError: String?,
    addSourceFocusRequester: FocusRequester,
    onAddSource: () -> Unit,
    onRefreshNow: (String) -> Unit,
    onUpdatePolicy: (SourceRefreshPolicy) -> Unit,
    onRemovePolicy: (String) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        SourcesHeader(
            addSourceFocusRequester = addSourceFocusRequester,
            onAddSource = onAddSource,
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
                SourceCard(
                    source = source,
                    mutationInFlight = busySources[source.sourceId] == true,
                    onRefreshNow = onRefreshNow,
                    onUpdatePolicy = onUpdatePolicy,
                    onRemovePolicy = onRemovePolicy,
                )
            }
        }
    }
}

@Composable
private fun SourcesHeader(
    addSourceFocusRequester: FocusRequester,
    onAddSource: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Источники", style = MaterialTheme.typography.displaySmall)
        MuxTvActionButton(
            text = "Добавить источник",
            onClick = onAddSource,
            modifier = Modifier
                .testTag(SOURCES_ADD_TEST_TAG)
                .focusRequester(addSourceFocusRequester),
        )
    }
}

@Composable
private fun SourceCard(
    source: SourceRefreshOverview,
    mutationInFlight: Boolean,
    onRefreshNow: (String) -> Unit,
    onUpdatePolicy: (SourceRefreshPolicy) -> Unit,
    onRemovePolicy: (String) -> Unit,
) {
    val policy = source.policy ?: defaultPolicy(source.sourceId)
    val running = source.status?.state == SourceRefreshRunState.RUNNING
    val operationalControlsEnabled =
        !mutationInFlight && !running && source.hasCredentialReference

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        Text(source.sourceName, style = MaterialTheme.typography.headlineSmall)
        Text(
            source.summaryLine(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            source.statusLabel(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = if (running) "Обновляется…" else "Обновить сейчас",
                onClick = { onRefreshNow(source.sourceId) },
                enabled = operationalControlsEnabled,
            )
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
            )
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
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
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
            )
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
            )
            MuxTvActionButton(
                text = "Сбросить расписание",
                onClick = { onRemovePolicy(source.sourceId) },
                enabled = !mutationInFlight && source.policy != null,
            )
        }
    }
}

@Composable
private fun MessageRoute(
    message: String,
    addSourceFocusRequester: FocusRequester,
    onAddSource: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(56.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        SourcesHeader(
            addSourceFocusRequester = addSourceFocusRequester,
            onAddSource = onAddSource,
        )
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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

private const val SOURCES_ADD_TEST_TAG = "sources-add"
private const val DEFAULT_INTERVAL_MINUTES = 60L
