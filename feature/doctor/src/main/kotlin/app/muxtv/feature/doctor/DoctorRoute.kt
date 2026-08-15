package app.muxtv.feature.doctor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.player.PlaybackFailureCategory
import app.muxtv.player.PlaybackObservation
import app.muxtv.player.PlaybackObservationKind
import app.muxtv.player.PlaybackObservationReader

enum class DoctorExportStatus {
    IDLE,
    AWAITING_DESTINATION,
    EXPORTED,
    FAILED,
}

internal object DoctorExportPolicy {
    fun isEnabled(
        snapshotReadSucceeded: Boolean,
        exportStatus: DoctorExportStatus,
    ): Boolean = snapshotReadSucceeded && exportStatus != DoctorExportStatus.AWAITING_DESTINATION
}

private sealed interface DoctorSnapshot {
    data class Ready(val observations: List<PlaybackObservation>) : DoctorSnapshot
    data object Failed : DoctorSnapshot
}

@Composable
fun DoctorRoute(
    observationReader: PlaybackObservationReader,
    exportStatus: DoctorExportStatus,
    onExport: (String) -> Unit,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    val refreshFocusRequester = remember { FocusRequester() }
    var snapshot by remember(observationReader) {
        mutableStateOf(readSnapshot(observationReader))
    }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        refreshFocusRequester.requestFocus()
    }

    val observations = (snapshot as? DoctorSnapshot.Ready)?.observations.orEmpty()
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.medium),
    ) {
        Text(
            text = "Диагностика",
            modifier = Modifier.testTag(DOCTOR_TITLE_TEST_TAG),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = "Здесь только обезличенные события воспроизведения. Адреса, заголовки и учётные данные не сохраняются.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            MuxTvActionButton(
                text = "Обновить",
                onClick = { snapshot = readSnapshot(observationReader) },
                modifier = Modifier
                    .testTag(DOCTOR_REFRESH_TEST_TAG)
                    .focusProperties { left = railFocusRequester ?: FocusRequester.Default }
                    .focusRequester(refreshFocusRequester),
            )
            MuxTvActionButton(
                text = "Экспортировать отчёт",
                onClick = {
                    onExport(
                        DoctorReportFormatter.format(
                            generatedAtEpochMillis = System.currentTimeMillis(),
                            observations = observations,
                        ),
                    )
                },
                modifier = Modifier.testTag(DOCTOR_EXPORT_TEST_TAG),
                enabled = DoctorExportPolicy.isEnabled(
                    snapshotReadSucceeded = snapshot is DoctorSnapshot.Ready,
                    exportStatus = exportStatus,
                ),
            )
        }
        exportStatus.message()?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = if (exportStatus == DoctorExportStatus.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        when (snapshot) {
            DoctorSnapshot.Failed -> Text(
                text = "Не удалось прочитать диагностические события.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )

            is DoctorSnapshot.Ready -> {
                if (observations.isEmpty()) {
                    Text(
                        text = "Событий воспроизведения пока нет.",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
                    ) {
                        itemsIndexed(
                            items = observations,
                            key = { index, item ->
                                "${item.timestampEpochMillis}-${item.kind}-${item.attemptNumber}-$index"
                            },
                            contentType = { _, item -> item.kind },
                        ) { index, observation ->
                            DoctorObservationCard(
                                observation = observation,
                                index = index,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DoctorObservationCard(
    observation: PlaybackObservation,
    index: Int,
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusBorderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(width = 3.dp, color = focusBorderColor)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .testTag(doctorObservationTestTag(index))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small),
    ) {
        Text(observation.kind.title(), style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Попытка ${observation.attemptNumber} из ${observation.attemptLimit}",
            style = MaterialTheme.typography.bodyLarge,
        )
        observation.failureCategory?.let { category ->
            Text(
                text = category.actionableMessage(observation.httpStatusCode),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun readSnapshot(reader: PlaybackObservationReader): DoctorSnapshot =
    runCatching { reader.snapshot() }
        .fold(
            onSuccess = { DoctorSnapshot.Ready(it) },
            onFailure = { DoctorSnapshot.Failed },
        )

private fun PlaybackObservationKind.title(): String = when (this) {
    PlaybackObservationKind.ATTEMPT_STARTED -> "Попытка начата"
    PlaybackObservationKind.ATTEMPT_FAILED -> "Попытка не удалась"
    PlaybackObservationKind.RECOVERY_SUCCEEDED -> "Воспроизведение восстановлено"
    PlaybackObservationKind.RECOVERY_FAILED -> "Варианты исчерпаны"
    PlaybackObservationKind.APPROVAL_REQUIRED -> "Требуется разрешение HTTP"
    PlaybackObservationKind.EXTERNAL_INTENT_ACCEPTED -> "Внешний запрос принят"
    PlaybackObservationKind.EXTERNAL_INTENT_REJECTED -> "Внешний запрос отклонён"
    PlaybackObservationKind.EXTERNAL_SETUP_STARTED -> "Внешнее воспроизведение начато"
    PlaybackObservationKind.EXTERNAL_FIRST_FRAME -> "Первый кадр внешнего потока"
    PlaybackObservationKind.EXTERNAL_PLAYBACK_FAILED -> "Внешний поток не воспроизведён"
}

private fun PlaybackFailureCategory.actionableMessage(httpStatusCode: Int?): String = when (this) {
    PlaybackFailureCategory.DNS -> "Не удалось найти сервер. Проверьте DNS и подключение."
    PlaybackFailureCategory.TLS -> "Защищённое соединение отклонено. Проверьте дату и сертификат провайдера."
    PlaybackFailureCategory.HTTP_RESPONSE ->
        "Провайдер вернул HTTP ${httpStatusCode ?: "ошибку"}. Проверьте доступ и повторите позже."
    PlaybackFailureCategory.REDIRECT_POLICY -> "Переадресация заблокирована политикой безопасности."
    PlaybackFailureCategory.TIMEOUT -> "Сервер не ответил вовремя. Проверьте сеть и повторите."
    PlaybackFailureCategory.NETWORK_UNREACHABLE -> "Сеть недоступна. Проверьте подключение устройства."
    PlaybackFailureCategory.MANIFEST_FORMAT -> "Формат потока не распознан."
    PlaybackFailureCategory.CODEC_DECODER -> "Поток требует неподдерживаемый декодер или кодек."
    PlaybackFailureCategory.PLAYER_RENDER -> "Устройство не смогло вывести кадр."
    PlaybackFailureCategory.CREDENTIAL_ACCESS -> "Источник или его учётные данные недоступны."
    PlaybackFailureCategory.UNKNOWN -> "Причина не определена. Попробуйте другой вариант."
}

private fun DoctorExportStatus.message(): String? = when (this) {
    DoctorExportStatus.IDLE -> null
    DoctorExportStatus.AWAITING_DESTINATION -> "Выберите место для сохранения отчёта."
    DoctorExportStatus.EXPORTED -> "Отчёт сохранён."
    DoctorExportStatus.FAILED -> "Не удалось сохранить отчёт."
}

const val DOCTOR_TITLE_TEST_TAG = "doctor-title"
const val DOCTOR_REFRESH_TEST_TAG = "doctor-refresh"
const val DOCTOR_EXPORT_TEST_TAG = "doctor-export"

fun doctorObservationTestTag(index: Int): String = "doctor-observation-$index"
