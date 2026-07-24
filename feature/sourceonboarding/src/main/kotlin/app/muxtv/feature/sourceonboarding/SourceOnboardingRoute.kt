package app.muxtv.feature.sourceonboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.catalog.refresh.RemoteSourceActivationFailure
import app.muxtv.catalog.refresh.RemoteSourceActivationResult
import app.muxtv.catalog.refresh.RemoteSourceOnboarding
import app.muxtv.catalog.refresh.RemoteSourceOnboardingInput
import app.muxtv.catalog.refresh.RemoteSourcePreparationResult
import app.muxtv.catalog.refresh.RemoteSourcePreparationToken
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import kotlinx.coroutines.launch

private sealed interface WizardState {
    data object Editing : WizardState
    data object Preparing : WizardState
    data object HttpApprovalRequired : WizardState

    data class Prepared(
        val token: RemoteSourcePreparationToken,
        val endpoint: String,
    ) : WizardState

    data class Activating(
        val token: RemoteSourcePreparationToken,
        val endpoint: String,
    ) : WizardState

    data class Failed(
        val message: String,
        val token: RemoteSourcePreparationToken? = null,
        val endpoint: String? = null,
    ) : WizardState

    data object Cancelling : WizardState
}

@Composable
fun SourceOnboardingRoute(
    onboarding: RemoteSourceOnboarding,
    onActivated: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var sourceName by remember { mutableStateOf("") }
    var locator by remember { mutableStateOf("") }
    var revealLocator by remember { mutableStateOf(false) }
    var state by remember { mutableStateOf<WizardState>(WizardState.Editing) }

    fun prepare(allowHttp: Boolean) {
        val normalizedName = sourceName.trim()
        val normalizedLocator = locator.trim()
        if (normalizedName.isEmpty() || normalizedName.length > MAX_SOURCE_NAME_CHARACTERS) {
            state = WizardState.Failed("Введите название источника длиной до 200 символов.")
            return
        }
        if (normalizedLocator.isEmpty() || normalizedLocator.length > MAX_LOCATOR_CHARACTERS) {
            state = WizardState.Failed("Введите ссылку длиной до 8192 символов.")
            return
        }

        state = WizardState.Preparing
        scope.launch {
            state = when (
                val result = onboarding.prepare(
                    RemoteSourceOnboardingInput(
                        locator = normalizedLocator,
                        insecureHttpApproved = allowHttp,
                    ),
                )
            ) {
                is RemoteSourcePreparationResult.Prepared -> {
                    locator = ""
                    revealLocator = false
                    WizardState.Prepared(
                        token = result.token,
                        endpoint = "${result.scheme}://${result.host}",
                    )
                }

                RemoteSourcePreparationResult.InsecureTransportApprovalRequired ->
                    WizardState.HttpApprovalRequired

                is RemoteSourcePreparationResult.UrlRejected ->
                    WizardState.Failed("Ссылка отклонена политикой безопасности.")

                RemoteSourcePreparationResult.InvalidAccess ->
                    WizardState.Failed("Параметры доступа недействительны.")

                is RemoteSourcePreparationResult.CredentialTooLarge ->
                    WizardState.Failed("Параметры доступа превышают допустимый размер.")

                is RemoteSourcePreparationResult.CredentialUnavailable ->
                    WizardState.Failed("Защищённое хранилище сейчас недоступно.")
            }
        }
    }

    fun activate(token: RemoteSourcePreparationToken, endpoint: String) {
        state = WizardState.Activating(token = token, endpoint = endpoint)
        scope.launch {
            when (val result = onboarding.activate(token, sourceName)) {
                is RemoteSourceActivationResult.Activated -> {
                    locator = ""
                    revealLocator = false
                    onActivated(result.sourceId)
                }

                is RemoteSourceActivationResult.Failed -> {
                    val cleanupComplete =
                        result.credentialCleanupFailure == null && result.sourceCleanupFailure == null
                    state = WizardState.Failed(
                        message = result.failure.userMessage(),
                        token = token.takeUnless { cleanupComplete },
                        endpoint = endpoint.takeUnless { cleanupComplete },
                    )
                }
            }
        }
    }

    fun leaveSafely() {
        when (val current = state) {
            WizardState.Preparing,
            is WizardState.Activating,
            WizardState.Cancelling,
            -> Unit

            is WizardState.Prepared -> {
                state = WizardState.Cancelling
                scope.launch {
                    onboarding.cancel(current.token)
                    locator = ""
                    onBack()
                }
            }

            is WizardState.Failed -> {
                val token = current.token
                if (token == null) {
                    locator = ""
                    onBack()
                } else {
                    state = WizardState.Cancelling
                    scope.launch {
                        onboarding.cancel(token)
                        locator = ""
                        onBack()
                    }
                }
            }

            WizardState.Editing,
            WizardState.HttpApprovalRequired,
            -> {
                locator = ""
                onBack()
            }
        }
    }

    BackHandler(enabled = true, onBack = ::leaveSafely)

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text("Добавить источник", style = MaterialTheme.typography.displaySmall)
        when (val current = state) {
            WizardState.Editing -> EditingContent(
                sourceName = sourceName,
                onSourceNameChanged = { sourceName = it.take(MAX_SOURCE_NAME_CHARACTERS) },
                locator = locator,
                onLocatorChanged = { locator = it.take(MAX_LOCATOR_CHARACTERS) },
                revealLocator = revealLocator,
                onToggleLocatorVisibility = { revealLocator = !revealLocator },
                onPrepare = { prepare(false) },
                onBack = ::leaveSafely,
            )

            WizardState.Preparing -> StatusContent("Проверка и защищённое сохранение параметров…")
            WizardState.HttpApprovalRequired -> HttpApprovalContent(
                onApprove = { prepare(true) },
                onEdit = { state = WizardState.Editing },
                onBack = ::leaveSafely,
            )

            is WizardState.Prepared -> PreparedContent(
                endpoint = current.endpoint,
                onActivate = { activate(current.token, current.endpoint) },
                onCancel = ::leaveSafely,
            )

            is WizardState.Activating -> StatusContent(
                "Загрузка плейлиста и активация каталога ${current.endpoint}…",
            )

            is WizardState.Failed -> FailureContent(
                message = current.message,
                canRetryActivation = current.token != null && current.endpoint != null,
                onRetryActivation = {
                    val token = current.token ?: return@FailureContent
                    val endpoint = current.endpoint ?: return@FailureContent
                    activate(token, endpoint)
                },
                onEdit = {
                    if (current.token == null) {
                        state = WizardState.Editing
                    } else {
                        state = WizardState.Cancelling
                        scope.launch {
                            onboarding.cancel(current.token)
                            state = WizardState.Editing
                        }
                    }
                },
                onBack = ::leaveSafely,
            )

            WizardState.Cancelling -> StatusContent("Безопасная отмена подготовки…")
        }
    }
}

@Composable
private fun EditingContent(
    sourceName: String,
    onSourceNameChanged: (String) -> Unit,
    locator: String,
    onLocatorChanged: (String) -> Unit,
    revealLocator: Boolean,
    onToggleLocatorVisibility: () -> Unit,
    onPrepare: () -> Unit,
    onBack: () -> Unit,
) {
    Text("Название", style = MaterialTheme.typography.titleMedium)
    WizardTextField(
        value = sourceName,
        onValueChange = onSourceNameChanged,
        placeholder = "Например: Основной IPTV",
        masked = false,
    )
    Text("Ссылка M3U", style = MaterialTheme.typography.titleMedium)
    WizardTextField(
        value = locator,
        onValueChange = onLocatorChanged,
        placeholder = "https://provider.example/list.m3u",
        masked = !revealLocator,
    )
    Text(
        "Ссылка не сохраняется в навигации или восстановимом состоянии интерфейса.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        MuxTvActionButton(
            text = if (revealLocator) "Скрыть ссылку" else "Показать ссылку",
            onClick = onToggleLocatorVisibility,
        )
        MuxTvActionButton(text = "Проверить", onClick = onPrepare)
        MuxTvActionButton(text = "Назад", onClick = onBack)
    }
}

@Composable
private fun HttpApprovalContent(
    onApprove: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    Text(
        "Источник использует незашифрованный HTTP. Данные доступа могут быть перехвачены в сети.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.error,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        MuxTvActionButton(text = "Разрешить HTTP и продолжить", onClick = onApprove)
        MuxTvActionButton(text = "Изменить ссылку", onClick = onEdit)
        MuxTvActionButton(text = "Назад", onClick = onBack)
    }
}

@Composable
private fun PreparedContent(
    endpoint: String,
    onActivate: () -> Unit,
    onCancel: () -> Unit,
) {
    Text("Параметры защищённо сохранены.", style = MaterialTheme.typography.headlineSmall)
    Text(
        "Проверенный адрес: $endpoint",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        "Полная ссылка и её секретные параметры больше не находятся в состоянии экрана.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        MuxTvActionButton(text = "Загрузить и активировать", onClick = onActivate)
        MuxTvActionButton(text = "Отменить", onClick = onCancel)
    }
}

@Composable
private fun FailureContent(
    message: String,
    canRetryActivation: Boolean,
    onRetryActivation: () -> Unit,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
        if (canRetryActivation) {
            MuxTvActionButton(text = "Повторить активацию", onClick = onRetryActivation)
        }
        MuxTvActionButton(text = "Изменить данные", onClick = onEdit)
        MuxTvActionButton(text = "Назад", onClick = onBack)
    }
}

@Composable
private fun StatusContent(message: String) {
    Text(
        message,
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun WizardTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    masked: Boolean,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        singleLine = true,
        visualTransformation = if (masked) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        decorationBox = { innerTextField ->
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            innerTextField()
        },
    )
}

private fun RemoteSourceActivationFailure.userMessage(): String = when (this) {
    RemoteSourceActivationFailure.InvalidSourceName -> "Название источника недействительно."
    RemoteSourceActivationFailure.AccessCredentialNotFound,
    RemoteSourceActivationFailure.AccessCredentialCorrupted,
    -> "Защищённые параметры доступа потеряны или повреждены."

    is RemoteSourceActivationFailure.AccessCredentialUnavailable ->
        "Защищённое хранилище сейчас недоступно."

    is RemoteSourceActivationFailure.UrlRejected -> "Ссылка отклонена политикой безопасности."
    RemoteSourceActivationFailure.InsecureTransportApprovalRequired ->
        "Для этого источника требуется явное разрешение HTTP."

    is RemoteSourceActivationFailure.Http -> "Сервер вернул HTTP ${statusCode}."
    is RemoteSourceActivationFailure.ResponseTooLarge -> "Плейлист превышает допустимый размер."
    is RemoteSourceActivationFailure.RedirectRejected -> "Перенаправление сервера отклонено."
    is RemoteSourceActivationFailure.Network -> "Не удалось подключиться к источнику."
    RemoteSourceActivationFailure.EmptyRevisionRejected -> "Плейлист не содержит активных каналов."
    is RemoteSourceActivationFailure.ImportFailed -> "Не удалось импортировать плейлист."
    RemoteSourceActivationFailure.Unexpected -> "Неожиданная ошибка активации источника."
}

private const val MAX_SOURCE_NAME_CHARACTERS = 200
private const val MAX_LOCATOR_CHARACTERS = 8_192
