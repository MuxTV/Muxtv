package app.muxtv.feature.sources

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicSecureTextField
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.focused
import androidx.compose.ui.semantics.inputText
import androidx.compose.ui.semantics.isEditable
import androidx.compose.ui.semantics.isSensitiveData
import androidx.compose.ui.semantics.maxTextLength
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.requestFocus
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.text.AnnotatedString
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
    var provider by remember { mutableStateOf(SourceProviderSelection.M3U) }
    val locatorState = remember { TextFieldState() }
    val xtreamEndpointState = remember { TextFieldState() }
    val xtreamUsernameState = remember { TextFieldState() }
    val xtreamPasswordState = remember { TextFieldState() }
    var revealSensitiveInput by remember { mutableStateOf(false) }

    val providerM3uFocusRequester = remember { FocusRequester() }
    val providerXtreamFocusRequester = remember { FocusRequester() }
    val sourceNameFocusRequester = remember { FocusRequester() }
    val sourceLocatorFocusRequester = remember { FocusRequester() }
    val xtreamEndpointFocusRequester = remember { FocusRequester() }
    val xtreamUsernameFocusRequester = remember { FocusRequester() }
    val xtreamPasswordFocusRequester = remember { FocusRequester() }
    val revealSensitiveFocusRequester = remember { FocusRequester() }
    val checkFocusRequester = remember { FocusRequester() }
    val httpCancelFocusRequester = remember { FocusRequester() }
    val confirmFocusRequester = remember { FocusRequester() }
    val editAgainFocusRequester = remember { FocusRequester() }
    val cleanupRetryFocusRequester = remember { FocusRequester() }

    fun providerFocusRequester(): FocusRequester = when (provider) {
        SourceProviderSelection.M3U -> providerM3uFocusRequester
        SourceProviderSelection.XTREAM -> providerXtreamFocusRequester
    }

    fun firstInputFocusRequester(): FocusRequester = when (provider) {
        SourceProviderSelection.M3U -> sourceLocatorFocusRequester
        SourceProviderSelection.XTREAM -> xtreamEndpointFocusRequester
    }

    fun clearTransientInputs() {
        locatorState.clearText()
        xtreamEndpointState.clearText()
        xtreamUsernameState.clearText()
        xtreamPasswordState.clearText()
        revealSensitiveInput = false
        session.clearTransientLocator()
    }

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
        val current = state
        if (current is SourceEntryUiState.Confirming) {
            clearTransientInputs()
        }
        if (current is SourceEntryUiState.Completed) {
            onCompleted()
            return@LaunchedEffect
        }

        withFrameNanos { }
        when (current) {
            SourceEntryUiState.Editing -> sourceNameFocusRequester.requestFocus()
            SourceEntryUiState.HttpApprovalRequired -> httpCancelFocusRequester.requestFocus()
            is SourceEntryUiState.Confirming -> {
                if (sourceName.isBlank()) {
                    sourceNameFocusRequester.requestFocus()
                } else {
                    confirmFocusRequester.requestFocus()
                }
            }

            is SourceEntryUiState.Failed -> {
                if (current.cleanupPending) {
                    cleanupRetryFocusRequester.requestFocus()
                } else {
                    editAgainFocusRequester.requestFocus()
                }
            }

            SourceEntryUiState.Restoring,
            SourceEntryUiState.Preparing,
            SourceEntryUiState.Activating,
            SourceEntryUiState.Completed,
            -> Unit
        }
    }
    DisposableEffect(session) {
        onDispose {
            clearTransientInputs()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
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
            Text("Добавить источник", style = MaterialTheme.typography.displaySmall)

            when (val current = state) {
                SourceEntryUiState.Editing -> {
                    Text("Тип источника", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                        MuxTvActionButton(
                            text = if (provider == SourceProviderSelection.M3U) "M3U · выбрано" else "M3U",
                            onClick = {
                                provider = SourceProviderSelection.M3U
                                xtreamEndpointState.clearText()
                                xtreamUsernameState.clearText()
                                xtreamPasswordState.clearText()
                                revealSensitiveInput = false
                                sourceNameFocusRequester.requestFocus()
                            },
                            modifier = Modifier
                                .testTag(SOURCE_PROVIDER_M3U_TEST_TAG)
                                .focusRequester(providerM3uFocusRequester),
                        )
                        MuxTvActionButton(
                            text = if (provider == SourceProviderSelection.XTREAM) "Xtream · выбрано" else "Xtream",
                            onClick = {
                                provider = SourceProviderSelection.XTREAM
                                locatorState.clearText()
                                revealSensitiveInput = false
                                sourceNameFocusRequester.requestFocus()
                            },
                            modifier = Modifier
                                .testTag(SOURCE_PROVIDER_XTREAM_TEST_TAG)
                                .focusRequester(providerXtreamFocusRequester),
                        )
                    }

                    TvTextInput(
                        label = "Название",
                        value = sourceName,
                        onValueChange = { sourceName = it.take(MAX_SOURCE_NAME_CHARACTERS) },
                        onNavigateUp = { providerFocusRequester().requestFocus() },
                        onNavigateDown = { firstInputFocusRequester().requestFocus() },
                        modifier = Modifier
                            .testTag(SOURCE_NAME_TEST_TAG)
                            .focusRequester(sourceNameFocusRequester),
                    )

                    when (provider) {
                        SourceProviderSelection.M3U -> {
                            TvSecureTextInput(
                                label = "Ссылка M3U",
                                state = locatorState,
                                revealed = revealSensitiveInput,
                                focusRequester = sourceLocatorFocusRequester,
                                testTag = SOURCE_LOCATOR_TEST_TAG,
                                maxCharacters = MAX_LOCATOR_CHARACTERS,
                                onNavigateUp = sourceNameFocusRequester::requestFocus,
                                onNavigateDown = revealSensitiveFocusRequester::requestFocus,
                            )
                        }

                        SourceProviderSelection.XTREAM -> {
                            TvSecureTextInput(
                                label = "Сервер Xtream",
                                state = xtreamEndpointState,
                                revealed = revealSensitiveInput,
                                focusRequester = xtreamEndpointFocusRequester,
                                testTag = SOURCE_XTREAM_ENDPOINT_TEST_TAG,
                                maxCharacters = MAX_LOCATOR_CHARACTERS,
                                onNavigateUp = sourceNameFocusRequester::requestFocus,
                                onNavigateDown = xtreamUsernameFocusRequester::requestFocus,
                            )
                            TvSecureTextInput(
                                label = "Логин",
                                state = xtreamUsernameState,
                                revealed = revealSensitiveInput,
                                focusRequester = xtreamUsernameFocusRequester,
                                testTag = SOURCE_XTREAM_USERNAME_TEST_TAG,
                                maxCharacters = MAX_CREDENTIAL_CHARACTERS,
                                onNavigateUp = xtreamEndpointFocusRequester::requestFocus,
                                onNavigateDown = xtreamPasswordFocusRequester::requestFocus,
                            )
                            TvSecureTextInput(
                                label = "Пароль",
                                state = xtreamPasswordState,
                                revealed = revealSensitiveInput,
                                focusRequester = xtreamPasswordFocusRequester,
                                testTag = SOURCE_XTREAM_PASSWORD_TEST_TAG,
                                maxCharacters = MAX_CREDENTIAL_CHARACTERS,
                                onNavigateUp = xtreamUsernameFocusRequester::requestFocus,
                                onNavigateDown = revealSensitiveFocusRequester::requestFocus,
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                        MuxTvActionButton(
                            text = if (revealSensitiveInput) "Скрыть данные" else "Показать временно",
                            onClick = { revealSensitiveInput = !revealSensitiveInput },
                            modifier = Modifier.focusRequester(revealSensitiveFocusRequester),
                        )
                        MuxTvActionButton(
                            text = "Проверить",
                            onClick = {
                                scope.launch {
                                    when (provider) {
                                        SourceProviderSelection.M3U ->
                                            session.prepare(locatorState.text.toString())

                                        SourceProviderSelection.XTREAM ->
                                            session.prepareXtream(
                                                endpoint = xtreamEndpointState.text.toString(),
                                                username = xtreamUsernameState.text.toString(),
                                                password = xtreamPasswordState.text.toString(),
                                            )
                                    }
                                }
                            },
                            enabled = when (provider) {
                                SourceProviderSelection.M3U -> locatorState.text.isNotBlank()
                                SourceProviderSelection.XTREAM ->
                                    xtreamEndpointState.text.isNotBlank() &&
                                        xtreamUsernameState.text.isNotBlank() &&
                                        xtreamPasswordState.text.isNotBlank()
                            },
                            modifier = Modifier
                                .testTag(SOURCE_CHECK_TEST_TAG)
                                .focusRequester(checkFocusRequester),
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
                        MuxTvActionButton(
                            text = "Отмена",
                            onClick = ::cancelAndLeave,
                            modifier = Modifier
                                .testTag(SOURCE_HTTP_CANCEL_TEST_TAG)
                                .focusRequester(httpCancelFocusRequester),
                        )
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
                        onNavigateDown = {
                            if (sourceName.isNotBlank()) confirmFocusRequester.requestFocus()
                        },
                        modifier = Modifier
                            .testTag(SOURCE_NAME_TEST_TAG)
                            .focusRequester(sourceNameFocusRequester),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
                        MuxTvActionButton(
                            text = "Добавить",
                            onClick = { scope.launch { session.activate(sourceName) } },
                            enabled = sourceName.isNotBlank(),
                            modifier = Modifier
                                .testTag(SOURCE_CONFIRM_TEST_TAG)
                                .focusRequester(confirmFocusRequester),
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
                                modifier = Modifier
                                    .testTag(SOURCE_CLEANUP_RETRY_TEST_TAG)
                                    .focusRequester(cleanupRetryFocusRequester),
                            )
                        } else {
                            MuxTvActionButton(
                                text = "Изменить данные",
                                onClick = session::editAgain,
                                modifier = Modifier
                                    .testTag(SOURCE_EDIT_AGAIN_TEST_TAG)
                                    .focusRequester(editAgainFocusRequester),
                            )
                        }
                        MuxTvActionButton(text = "Назад", onClick = ::cancelAndLeave)
                    }
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
    modifier: Modifier = Modifier,
    onNavigateUp: (() -> Unit)? = null,
    onNavigateDown: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .onPreviewDpadVertical(
                    onNavigateUp = onNavigateUp,
                    onNavigateDown = onNavigateDown,
                )
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
    focusRequester: FocusRequester,
    testTag: String,
    maxCharacters: Int,
    onNavigateUp: (() -> Unit)? = null,
    onNavigateDown: (() -> Unit)? = null,
) {
    var hasFocus by remember { mutableStateOf(false) }
    val maskedText = if (state.text.isEmpty()) {
        AnnotatedString("")
    } else {
        AnnotatedString("Скрыто")
    }

    Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        BasicSecureTextField(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
                .focusRequester(focusRequester)
                .onFocusChanged { hasFocus = it.isFocused }
                .onPreviewDpadVertical(
                    onNavigateUp = onNavigateUp,
                    onNavigateDown = onNavigateDown,
                )
                .background(
                    if (hasFocus) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                )
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .clearAndSetSemantics {
                    contentDescription = "$label, значение скрыто"
                    password()
                    focused = hasFocus
                    isEditable = true
                    isSensitiveData = true
                    maxTextLength = maxCharacters
                    editableText = maskedText
                    inputText = AnnotatedString("")
                    onClick {
                        focusRequester.requestFocus()
                        true
                    }
                    requestFocus {
                        focusRequester.requestFocus()
                        true
                    }
                    setText { replacement ->
                        state.setTextAndPlaceCursorAtEnd(
                            replacement.text.take(maxCharacters),
                        )
                        true
                    }
                },
            inputTransformation = InputTransformation.maxLength(maxCharacters),
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

private fun Modifier.onPreviewDpadVertical(
    onNavigateUp: (() -> Unit)?,
    onNavigateDown: (() -> Unit)?,
): Modifier = onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

    when (event.key) {
        Key.DirectionUp -> onNavigateUp?.let {
            it()
            true
        } ?: false

        Key.DirectionDown -> onNavigateDown?.let {
            it()
            true
        } ?: false

        else -> false
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
    SourceEntryFailure.InvalidLocator -> "Данные источника отклонены. Проверьте адрес и обязательные поля."
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

private enum class SourceProviderSelection {
    M3U,
    XTREAM,
}

private const val SOURCE_PROVIDER_M3U_TEST_TAG = "source-provider-m3u"
private const val SOURCE_PROVIDER_XTREAM_TEST_TAG = "source-provider-xtream"
private const val SOURCE_NAME_TEST_TAG = "source-name"
private const val SOURCE_LOCATOR_TEST_TAG = "source-locator"
private const val SOURCE_XTREAM_ENDPOINT_TEST_TAG = "source-xtream-endpoint"
private const val SOURCE_XTREAM_USERNAME_TEST_TAG = "source-xtream-username"
private const val SOURCE_XTREAM_PASSWORD_TEST_TAG = "source-xtream-password"
private const val SOURCE_CHECK_TEST_TAG = "source-check"
private const val SOURCE_HTTP_CANCEL_TEST_TAG = "source-http-cancel"
private const val SOURCE_CONFIRM_TEST_TAG = "source-confirm"
private const val SOURCE_EDIT_AGAIN_TEST_TAG = "source-edit-again"
private const val SOURCE_CLEANUP_RETRY_TEST_TAG = "source-cleanup-retry"
private const val MAX_SOURCE_NAME_CHARACTERS = 200
private const val MAX_LOCATOR_CHARACTERS = 4_096
private const val MAX_CREDENTIAL_CHARACTERS = 4_096
