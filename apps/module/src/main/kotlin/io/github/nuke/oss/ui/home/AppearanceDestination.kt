package io.github.nuke.oss.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.LanguageChoice
import io.github.nuke.oss.app.shared.settings.ThemeChoice
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.app.shared.settings.withAccentHistory
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeSelectRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeSlider
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTheme
import kotlinx.coroutines.launch

// Theme, language, accent colour, click haptics and the home background. The caller stores the result; the whole
// screen re-themes from it. Picking a background copies the image, which can fail, so that call reports a result.
@Composable
internal fun AppearanceDestination(
    config: UiConfig,
    onChange: (UiConfig) -> Unit,
    onPickBackground: suspend (Uri) -> Boolean,
    onRemoveBackground: () -> Unit,
    onBack: (Offset) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }
    var pickFailed by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { pickFailed = !onPickBackground(uri) }
    }
    val accentHex = config.accent ?: DefaultAccentHex
    val themeLabels = mapOf(
        ThemeChoice.SYSTEM to stringResource(R.string.home_theme_system),
        ThemeChoice.LIGHT to stringResource(R.string.home_theme_light),
        ThemeChoice.DARK to stringResource(R.string.home_theme_dark),
    )
    val languageLabels = mapOf(
        LanguageChoice.SYSTEM to stringResource(R.string.home_language_system),
        LanguageChoice.ENGLISH to stringResource(R.string.home_language_english),
        LanguageChoice.CHINESE to stringResource(R.string.home_language_chinese),
    )
    HomePage(stringResource(R.string.home_appearance), onBack) {
        item(key = "theme") {
            NukeSettingGroup(title = stringResource(R.string.home_theme)) {
                NukeSelectRow(
                    title = stringResource(R.string.home_theme),
                    subtitle = stringResource(R.string.home_theme_subtitle),
                    options = ThemeChoice.entries,
                    selectedOption = config.theme,
                    onOptionSelected = { onChange(config.copy(theme = it)) },
                    optionToString = { themeLabels.getValue(it) },
                )
            }
        }
        item(key = "language") {
            NukeSettingGroup(title = stringResource(R.string.home_language)) {
                NukeSelectRow(
                    title = stringResource(R.string.home_language),
                    subtitle = stringResource(R.string.home_language_subtitle),
                    options = LanguageChoice.entries,
                    selectedOption = config.language,
                    onOptionSelected = { onChange(config.copy(language = it)) },
                    optionToString = { languageLabels.getValue(it) },
                )
            }
        }
        item(key = "interaction") {
            NukeSettingGroup(title = stringResource(R.string.home_interaction)) {
                NukeSettingRow(
                    title = stringResource(R.string.home_haptics),
                    subtitle = stringResource(R.string.home_haptics_subtitle),
                    onClick = { onChange(config.copy(clickHaptic = !config.clickHaptic)) },
                    role = Role.Switch,
                    trailingContent = {
                        NukeSwitch(checked = config.clickHaptic, onCheckChanged = { onChange(config.copy(clickHaptic = it)) })
                    },
                )
            }
        }
        item(key = "background") {
            NukeSettingGroup(title = stringResource(R.string.home_background)) {
                NukeNavRow(
                    title = stringResource(R.string.home_background),
                    subtitle = stringResource(R.string.home_background_description),
                    statusText = stringResource(
                        if (config.hasBackground) R.string.home_background_selected else R.string.home_background_none,
                    ),
                    onClick = { picker.launch(arrayOf("image/*")) },
                )
                if (pickFailed) {
                    NukeDivider()
                    BasicText(
                        stringResource(R.string.home_background_failed),
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        style = TextStyle(color = NukeTheme.colors.danger, fontSize = 13.sp, lineHeight = 18.sp),
                    )
                }
                if (config.hasBackground) {
                    SliderRow(stringResource(R.string.home_background_blur), config.backgroundBlur, 0f..40f, "%.1f".format(config.backgroundBlur)) {
                        onChange(config.copy(backgroundBlur = it))
                    }
                    SliderRow(stringResource(R.string.home_background_scrim), config.backgroundScrim, 0f..1f, "%.2f".format(config.backgroundScrim)) {
                        onChange(config.copy(backgroundScrim = it))
                    }
                    SliderRow(stringResource(R.string.home_card_opacity), config.cardOpacity, 0.2f..1f, "%.2f".format(config.cardOpacity)) {
                        onChange(config.copy(cardOpacity = it))
                    }
                    NukeSettingRow(
                        title = stringResource(R.string.home_card_blur),
                        subtitle = stringResource(if (config.cardBlur) R.string.home_card_blur_on else R.string.home_card_blur_off),
                        onClick = { onChange(config.copy(cardBlur = !config.cardBlur)) },
                        role = Role.Switch,
                        trailingContent = { NukeSwitch(checked = config.cardBlur, onCheckChanged = { onChange(config.copy(cardBlur = it)) }) },
                    )
                    NukeDialogAccentButton(
                        stringResource(R.string.home_background_remove),
                        { pickFailed = false; onRemoveBackground() },
                        Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                    )
                }
            }
        }
        item(key = "accent") {
            NukeSettingGroup(title = stringResource(R.string.home_accent)) {
                NukeNavRow(
                    title = stringResource(R.string.home_accent),
                    subtitle = stringResource(R.string.home_accent_subtitle),
                    leadingIcon = { Swatch(accentHex.toAccentColor(), size = 34.dp) },
                    statusText = accentHex,
                    onClick = { picking = true },
                )
            }
        }
    }
    AccentColorDialog(
        visible = picking,
        currentHex = accentHex,
        recent = config.recentAccents,
        onDismiss = { picking = false },
        onSave = { hex ->
            // The default is stored as no choice, so a later change of the default still applies.
            onChange(config.copy(
                accent = hex.takeUnless { it == DefaultAccentHex },
                recentAccents = config.recentAccents.withAccentHistory(hex, PresetAccentHexes.toSet()),
            ))
            picking = false
        },
    )
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, text: String, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText(title, style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 15.sp, lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NukeSlider(value, onChange, Modifier.weight(1f), valueRange = range)
            Spacer(Modifier.width(10.dp))
            BasicText(text, Modifier.width(42.dp), style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 12.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.End))
        }
    }
}
