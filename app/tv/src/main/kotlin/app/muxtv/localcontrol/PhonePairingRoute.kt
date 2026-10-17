package app.muxtv.localcontrol

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.component.MuxTvActionButton
import app.muxtv.designsystem.component.MuxTvScreenScaffold
import app.muxtv.localcontrol.PendingTvPairing
import app.muxtv.localcontrol.tvqr.TvHpkeQrPresentation
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val QR_SIZE = 288

/**
 * The old HTTP pairing QR and new MXLC04:1 public-key preview are intentionally distinct.
 * Neither route accepts provider data. QR material is not saved in navigation/Bundle state.
 */
@Composable
fun PhonePairingRoute(
    onStart: suspend () -> TvPairingController.ScreenSession?,
    onStop: () -> Unit,
    onStartPublicKeyPreview: suspend () -> TvHpkeQrPresentation?,
    onRevokePublicKeyPreview: () -> Unit,
    publicKeyPreviewIsActive: () -> Boolean,
    pendingFromTv: () -> PendingTvPairing?,
    approveFromTv: (String) -> Boolean,
    denyFromTv: (String) -> Boolean,
    pairedCount: () -> Int,
    connectionIsActive: () -> Boolean,
    onBack: () -> Unit,
) {
    var session by remember { mutableStateOf<TvPairingController.ScreenSession?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<PendingTvPairing?>(null) }
    var secondsRemaining by remember { mutableLongStateOf(0L) }
    var accepted by remember { mutableIntStateOf(0) }
    var restartRequest by remember { mutableIntStateOf(0) }

    var keyPreview by remember { mutableStateOf<TvHpkeQrPresentation?>(null) }
    var keyPreviewLoading by remember { mutableStateOf(false) }
    var keyPreviewError by remember { mutableStateOf(false) }
    var keySecondsRemaining by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()
    val closeFocus = remember { FocusRequester() }
    val denyFocus = remember { FocusRequester() }
    val returnFocus = remember { FocusRequester() }

    suspend fun restart() {
        onRevokePublicKeyPreview()
        keyPreview = null
        loading = true
        error = false
        pending = null
        session = null
        try {
            session = onStart()
            error = session == null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            error = true
        } finally {
            loading = false
        }
    }

    fun showPublicKeyPreview() {
        if (keyPreviewLoading) return
        scope.launch {
            onRevokePublicKeyPreview()
            keyPreview = null
            keyPreviewError = false
            keyPreviewLoading = true
            try {
                keyPreview = onStartPublicKeyPreview()
                keyPreviewError = keyPreview == null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                keyPreviewError = true
            } finally {
                keyPreviewLoading = false
            }
        }
    }

    LaunchedEffect(restartRequest) { restart() }
    LaunchedEffect(session) {
        while (coroutineContext.isActive) {
            if (session != null && !connectionIsActive()) {
                session = null
                error = true
            }
            pending = pendingFromTv()
            accepted = pairedCount()
            val now = SystemClock.elapsedRealtime()
            secondsRemaining = session?.let {
                ((it.expiresAtElapsedMillis - now).coerceAtLeast(0L) + 999) / 1000
            } ?: 0
            keySecondsRemaining = keyPreview?.let {
                if (publicKeyPreviewIsActive()) {
                    ((it.expiresAtElapsedMillis - now).coerceAtLeast(0L) + 999) / 1000
                } else {
                    0L
                }
            } ?: 0
            if (keyPreview != null && keySecondsRemaining == 0L) {
                onRevokePublicKeyPreview()
            }
            delay(700)
        }
    }
    LaunchedEffect(loading) {
        if (!loading && keyPreview == null) {
            withFrameNanos { }
            closeFocus.requestFocus()
        }
    }
    LaunchedEffect(pending?.claimId) {
        if (pending != null && secondsRemaining > 0 && keyPreview == null) {
            withFrameNanos { }
            denyFocus.requestFocus() // Safe default: deny request instead of preselecting approval.
        }
    }
    LaunchedEffect(keyPreview?.qrValue) {
        if (keyPreview != null) {
            withFrameNanos { }
            returnFocus.requestFocus()
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            onRevokePublicKeyPreview()
            onStop()
        }
    }

    MuxTvScreenScaffold(title = "Подключить телефон") {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val key = keyPreview
            if (key != null || keyPreviewLoading) {
                Text(
                    text = "Публичный ключ ТВ — предварительный просмотр",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Это отдельный QR для будущего защищённого соединения. " +
                        "Он не устанавливает соединение и не подтверждает подлинность телефона.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (keyPreviewLoading) {
                    Text("Генерация одноразового ключа…")
                } else if (key != null) {
                    if (keySecondsRemaining > 0 && publicKeyPreviewIsActive()) {
                        PairingQr(
                            value = key.qrValue,
                            description = "Одноразовый QR публичного HPKE-ключа ТВ",
                            testTag = "local-tv-key-qr",
                        )
                        Text("Отпечаток SHA-256: ${key.fingerprintSha256.chunked(8).joinToString(" ")}",
                            modifier = Modifier.testTag("local-tv-key-fingerprint"))
                        Text("QR действителен ещё $keySecondsRemaining с")
                        Text("Сравните отпечаток на телефоне с этим экраном ТВ. " +
                            "Сканирование пока не разрешает передачу M3U/Xtream или паролей.")
                    } else {
                        Text("Одноразовый ключ отозван или истёк. Создайте новый QR.")
                    }
                }
                MuxTvActionButton(
                    text = "Новый публичный ключ",
                    onClick = ::showPublicKeyPreview,
                    modifier = Modifier.testTag("local-tv-key-renew"),
                )
                MuxTvActionButton(
                    text = "Вернуться к обычному сопряжению",
                    onClick = {
                        onRevokePublicKeyPreview()
                        keyPreview = null
                    },
                    modifier = Modifier.focusRequester(returnFocus).testTag("local-tv-key-back"),
                )
            } else {
                Text(
                    text = "Откройте QR-код камерой телефона. Подтвердите новое устройство пультом.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "Внимание: локальный HTTP не шифрует соединение. " +
                        "На этом этапе доступно только сопряжение, без ввода IPTV-паролей.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (loading) {
                    Text("Подготовка локального соединения…")
                } else if (error) {
                    Text(
                        "Не удалось запустить сопряжение. Проверьте Wi-Fi/Ethernet и разрешение локальной сети.",
                        modifier = Modifier.testTag("local-pair-error"),
                    )
                    MuxTvActionButton(text = "Повторить", onClick = { restartRequest++ })
                } else {
                    val current = session
                    if (current != null) {
                        if (secondsRemaining > 0) {
                            PairingQr(current.qrValue, "QR обычного HTTP-сопряжения", "local-pair-qr")
                            Text("QR действителен ещё $secondsRemaining с")
                        } else {
                            Text("Срок действия QR завершился. Создайте новое сопряжение.")
                        }
                        if (accepted > 0) {
                            Text("Подключённых устройств в этой сессии: $accepted")
                        }
                    }
                    val claim = pending
                    if (claim != null && secondsRemaining > 0) {
                        Text("Телефон «${claim.clientLabel}» запрашивает доступ.")
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            MuxTvActionButton(
                                text = "Разрешить",
                                onClick = {
                                    approveFromTv(claim.claimId)
                                    pending = null
                                },
                                modifier = Modifier.testTag("local-pair-approve"),
                            )
                            MuxTvActionButton(
                                text = "Отклонить",
                                onClick = {
                                    denyFromTv(claim.claimId)
                                    pending = null
                                },
                                modifier = Modifier.focusRequester(denyFocus)
                                    .testTag("local-pair-deny"),
                            )
                        }
                    }
                }
                if (!loading && secondsRemaining == 0L && !error) {
                    MuxTvActionButton(text = "Новый QR", onClick = { restartRequest++ })
                }
                if (keyPreviewError) {
                    Text("Не удалось подготовить публичный ключ. Повторите попытку.")
                }
                MuxTvActionButton(
                    text = "Показать QR публичного ключа (предпросмотр)",
                    onClick = ::showPublicKeyPreview,
                    modifier = Modifier.testTag("local-tv-key-open"),
                )
            }
            Spacer(Modifier.height(8.dp))
            MuxTvActionButton(
                text = "Закрыть",
                onClick = onBack,
                modifier = Modifier.focusRequester(closeFocus).testTag("local-pair-close"),
            )
        }
    }
}

@Composable
private fun PairingQr(value: String, description: String, testTag: String) {
    // All QR generation is offline: never delegate token/key rendering to a remote service.
    val bitmap = remember(value) {
        val encoded = QRCodeWriter().encode(
            value,
            BarcodeFormat.QR_CODE,
            QR_SIZE,
            QR_SIZE,
            mapOf(EncodeHintType.MARGIN to 2),
        )
        Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until QR_SIZE) for (x in 0 until QR_SIZE) {
                setPixel(x, y, if (encoded[x, y]) Color.BLACK else Color.WHITE)
            }
        }.asImageBitmap()
    }
    Image(
        bitmap = bitmap,
        contentDescription = description,
        modifier = Modifier.size(QR_SIZE.dp).testTag(testTag),
    )
}
