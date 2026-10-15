package app.muxtv.localcontrol

import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.foundation.Image
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext

private const val QR_SIZE = 288

/**
 * QR secret lives only in ephemeral Compose memory, never in NavKey/rememberSaveable.
 * Navigating Back triggers stop and revokes pairing. No provider data is exposed.
 */
@Composable
fun PhonePairingRoute(
    onStart: suspend () -> TvPairingController.ScreenSession?,
    onStop: () -> Unit,
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
    val closeFocus = remember { FocusRequester() }
    val denyFocus = remember { FocusRequester() }

    suspend fun restart() {
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

    LaunchedEffect(restartRequest) { restart() }
    LaunchedEffect(session) {
        while (coroutineContext.isActive) {
            if (session != null && !connectionIsActive()) {
                session = null
                error = true
            }
            pending = pendingFromTv()
            accepted = pairedCount()
            secondsRemaining = session?.let {
                ((it.expiresAtElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0L) + 999) / 1000
            } ?: 0
            delay(700)
        }
    }
    LaunchedEffect(loading) {
        if (!loading) {
            withFrameNanos { }
            closeFocus.requestFocus()
        }
    }
    LaunchedEffect(pending?.claimId) {
        if (pending != null && secondsRemaining > 0) {
            withFrameNanos { }
            denyFocus.requestFocus() // Safe default: do not preselect permission approval.
        }
    }
    DisposableEffect(Unit) {
        onDispose { onStop() }
    }

    MuxTvScreenScaffold(title = "Подключить телефон") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                        PhonePairQr(url = current.qrValue)
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
                            modifier = Modifier.focusRequester(denyFocus).testTag("local-pair-deny"),
                        )
                    }
                }
            }
            if (!loading && secondsRemaining == 0L && !error) {
                MuxTvActionButton(text = "Новый QR", onClick = { restartRequest++ })
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
private fun PhonePairQr(url: String) {
    // ZXing is offline and does not send the token to any remote QR generator.
    val bitmap = remember(url) {
        val encoded = QRCodeWriter().encode(
            url,
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
        contentDescription = "QR-код сопряжения с телефоном",
        modifier = Modifier.size(QR_SIZE.dp).testTag("local-pair-qr"),
    )
}
