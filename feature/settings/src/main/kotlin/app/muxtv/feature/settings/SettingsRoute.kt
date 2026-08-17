package app.muxtv.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.muxtv.designsystem.TvTokens
import app.muxtv.designsystem.component.MuxTvScreenScaffold
import app.muxtv.designsystem.icon.MuxTvIcons

enum class SettingsSection {
    SOURCES,
    DOCTOR,
}

internal data class SettingsSectionModel(
    val section: SettingsSection,
    val label: String,
    val description: String,
    val icon: ImageVector,
    val testTag: String,
)

internal fun settingsSections(): List<SettingsSectionModel> = listOf(
    SettingsSectionModel(
        section = SettingsSection.SOURCES,
        label = "Источники",
        description = "Каталог каналов, обновление и добавление источников",
        icon = MuxTvIcons.Sources,
        testTag = SETTINGS_SOURCES_TEST_TAG,
    ),
    SettingsSectionModel(
        section = SettingsSection.DOCTOR,
        label = "Диагностика",
        description = "Состояние воспроизведения и экспорт отчёта",
        icon = MuxTvIcons.Doctor,
        testTag = SETTINGS_DOCTOR_TEST_TAG,
    ),
)

@Composable
fun SettingsRoute(
    onOpenSources: () -> Unit,
    onOpenDoctor: () -> Unit,
    modifier: Modifier = Modifier,
    railFocusRequester: FocusRequester? = null,
) {
    val sourcesFocusRequester = remember { FocusRequester() }
    val doctorFocusRequester = remember { FocusRequester() }
    var lastFocusedSection by rememberSaveable { mutableStateOf(SettingsSection.SOURCES) }
    val restoreFocusRequester = when (lastFocusedSection) {
        SettingsSection.SOURCES -> sourcesFocusRequester
        SettingsSection.DOCTOR -> doctorFocusRequester
    }

    LaunchedEffect(restoreFocusRequester) {
        withFrameNanos { }
        restoreFocusRequester.requestFocus()
    }

    MuxTvScreenScaffold(
        title = "Настройки",
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.small)) {
            settingsSections().forEach { model ->
                SettingsSectionRow(
                    model = model,
                    focusRequester = when (model.section) {
                        SettingsSection.SOURCES -> sourcesFocusRequester
                        SettingsSection.DOCTOR -> doctorFocusRequester
                    },
                    railFocusRequester = railFocusRequester,
                    onFocused = { lastFocusedSection = model.section },
                    onClick = when (model.section) {
                        SettingsSection.SOURCES -> onOpenSources
                        SettingsSection.DOCTOR -> onOpenDoctor
                    },
                    modifier = Modifier.testTag(model.testTag),
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionRow(
    model: SettingsSectionModel,
    focusRequester: FocusRequester,
    railFocusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(TvTokens.Shape.rowCorner)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(shape)
            .background(
                if (focused) TvTokens.Color.surfaceRaised else MaterialTheme.colorScheme.surface,
            )
            .border(
                width = if (focused) TvTokens.Focus.outlineWidth else 1.dp,
                color = if (focused) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.borderVariant
                },
                shape = shape,
            )
            .focusRequester(focusRequester)
            .focusProperties { left = railFocusRequester ?: FocusRequester.Default }
            .onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onFocused()
            }
            .clickable(role = Role.Button, onClick = onClick)
            .focusable()
            .padding(horizontal = TvTokens.Spacing.large, vertical = TvTokens.Spacing.medium),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = model.icon,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = if (focused) {
                TvTokens.Color.accentStrong
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(TvTokens.Spacing.large))
        Column(verticalArrangement = Arrangement.spacedBy(TvTokens.Spacing.micro)) {
            Text(
                text = model.label,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

const val SETTINGS_SOURCES_TEST_TAG = "settings-section-sources"
const val SETTINGS_DOCTOR_TEST_TAG = "settings-section-doctor"
