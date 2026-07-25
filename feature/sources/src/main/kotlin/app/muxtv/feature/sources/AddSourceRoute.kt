package app.muxtv.feature.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import kotlinx.coroutines.launch

@Composable
fun AddSourceRoute(
    onboarding: SourceEntryOnboarding,
    onCompleted: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val session = remember(onboarding) { SourceEntrySession(onboarding) }
    val state by session.state.collectAsState()
    val scope = rememberCoroutineScope()
    var sourceName by remember { mutableStateOf("") }
    val locatorState = remember { TextFieldState() }
    var revealLocator by remember { mutableStateOf(false) }

    fun cancelAndLeave() {
        scope.launch {
            if (session.cancel()) onBack()
        }
    }

    BackHandler(onBack = ::cancelAndLeave)

    LaunchedEffect(session) {
        session.restore()
    }
    LaunchedEffect(state) {
        if (state is SourceEntryUiState.Confirming) {
            locatorState.clearText()
            revealLocator = false
        }
        if (state is SourceEntryUiState.Completed) onCompleted()
    }
    DisposableEffect(session) {
        onDispose {
            locatorState.clearText()
            session.clearTransientLocator()
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text("Добавить источник", style = MaterialTheme.typography.displaySmall)

        when (val current = state) {
            SourceEntryUiState.Editing -> {
                TvTextInput(
                    label = "Название",
                    value = sourceName,
                    onValueChange = { sourceName = it.take(MAX_SOURCE_NAME_CHARACTERS) },
                )
                TvSecureTextInput(
                    label = "Ссылка M3U",
                    state = locatorState,
                    revealed = revealLocator,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    MuxTvActionButton(
                        text = if (revealLocator) "Скрыть ссылку" else "Показать временно",
                        onClick = { revealLocator = !revealLocator },
                    )
                    MuxTvActionButton(
                        text = "Проверить",
                        onClick = {
                            scope.launch {
                                session.prepare(locatorState.text.toString())
                            }
                        },
                        enabled = locatorState.text.isNotBlank(),
                    )
                    MuxTvActionButton(text = "Назад", onClick = ::cancelAndLeave)
                }
            }

            SourceEntryUiState.Restoring -> StatusText("Восстановление незавершённого добавления…")
            SourceEntryUiState.Preparing -> StatusText("Проверка источника…")
            SourceEntryUiState.Activating -> StatusText("Импорт и активация источника…")
            SourceEntryUiState.Completed -> StatusText("Источник добавлен.")

            SourceEntryUiState.HttpApprovalRequired -> {
                StatusText("Источник использует незащищённый HTTP. Продолжайте только для доверенной локальной сети.")
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    MuxTvActionButton(
                        text = "Разрешить HTTP",
                        onClick = { scope.launch { session.approveInsecureHttp() } },
                    )
                    MuxTvActionButton(text = "Отмена", onClick = ::cancelAndLeave)
                }
            }

            is SourceEntryUiState.Confirming -> {
                Text(
                    text = "Подтвердите адрес: ${current.endpoint}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                TvTextInput(
                    label = "Название источника",
                    value = sourceName,
                    onValueChange = { sourceName = it.take(MAX_SOURCE_NAME_CHARACTERS) },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    MuxTvActionButton(
                        text = "Добавить",
                        onClick = { scope.launch { session.activate(sourceName) } },
                        enabled = sourceName.isNotBlank(),
                    )
                    MuxTvActionButton(text = "Отмена", onClick = ::cancelAndLeave)
                }
            }

            is SourceEntryUiState.Failed -> {
                StatusText(current.reason.userMessage())
                Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                    if (current.cleanupPending) {
                        MuxTvActionButton(
                            text = "Повторить очистку",
                            onClick = { scope.launch { session.cancel() } },
                        )
                    } else {
                        MuxTvActionButton(text = "Изменить данные", onClick = session::editAgain)
                    }
                    MuxTvActionButton(text = "Назад", onClick = ::cancelAndLeave)
                }
            }
        }
    }
}

@Composable
private fun TvTextInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(
                    if (focused) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            singleLine = true,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun TvSecureTextInput(
    label: String,
    state: TextFieldState,
    revealed: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        BasicSecureTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .background(
                    if (focused) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(horizontal = 20.dp, vertical = 16.dp),
            inputTransformation = InputTransformation.maxLength(MAX_LOCATOR_CHARACTERS),
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface,
            ),
            textObfuscationMode = if (revealed) {
                TextObfuscationMode.Visible
            } else {
                TextObfuscationMode.Hidden
            },
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun StatusText(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun SourceEntryFailure.userMessage(): String = when (this) {
    SourceEntryFailure.InvalidLocator -> "Ссылка отклонена. Проверьте схему, адрес и отсутствие встроенных учётных данных."
    SourceEntryFailure.CredentialTooLarge -> "Данные доступа превышают допустимый размер."
    SourceEntryFailure.StorageUnavailable -> "Защищённое хранилище временно недоступно."
    SourceEntryFailure.InvalidSourceName -> "Введите название источника."
    SourceEntryFailure.AccessUnavailable -> "Подготовленные данные доступа недоступны."
    SourceEntryFailure.Network -> "Не удалось получить источник по сети."
    SourceEntryFailure.Http -> "Сервер вернул ошибку HTTP."
    SourceEntryFailure.EmptyPlaylist -> "Источник не содержит доступных каналов."
    SourceEntryFailure.Import -> "Не удалось импортировать список каналов."
    SourceEntryFailure.CleanupPending -> "Очистка не завершена. Повторите её перед выходом."
    SourceEntryFailure.SessionExpired -> "Подготовленное добавление больше недоступно."
    SourceEntryFailure.Unexpected -> "Не удалось добавить источник."
}

private const val MAX_SOURCE_NAME_CHARACTERS = 200
private const val MAX_LOCATOR_CHARACTERS = 4_096
