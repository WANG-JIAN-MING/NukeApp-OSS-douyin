package io.github.nuke.oss.ui.init

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.dex.AnalysisStep
import io.github.nuke.oss.app.shared.init.InitStage
import io.github.nuke.oss.app.shared.init.InitState
import io.github.nuke.oss.app.shared.init.progress
import io.github.nuke.oss.app.shared.settings.LanguageChoice
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogBodyText
import io.github.nuke.oss.designsystem.NukeLinearProgressIndicator
import io.github.nuke.oss.designsystem.NukeSegmentedControl
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.ui.home.NukeThemed
import kotlinx.coroutines.delay

// NukeCore waits this long on the finished bar before restarting the host, so the result can be read.
private const val RESTART_DELAY_MILLIS = 2_500L

// The init shown over the host on a new host or module build. The choices, asked once, use the shared dialog; the
// analysis then shows NukeCore's Dex analysis dialog: a title with the percentage beside it, the step, the progress
// bar, and retry on failure. Only its own buttons dismiss it: features must not start half-way through an analysis.
@Composable
internal fun InitDialog(
    state: InitState,
    onLanguage: (LanguageChoice) -> Unit,
    onBugReports: (Boolean) -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onRestart: () -> Unit,
) {
    NukeThemed(state.ui) {
        when (val stage = state.stage) {
            is InitStage.Choices -> NukeDialog(
                visible = true,
                onDismissRequest = {},
                title = stringResource(R.string.init_title),
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                actions = { NukeDialogAccentButton(stringResource(R.string.init_continue), onContinue, Modifier.weight(1f)) },
            ) { Choices(state.ui.language, stage.bugReports, onLanguage, onBugReports) }
            InitStage.Closed -> Unit
            else -> AnalysisDialog(stage, onRetry, onClose, onRestart)
        }
    }
}

@Composable
private fun AnalysisDialog(stage: InitStage, onRetry: () -> Unit, onClose: () -> Unit, onRestart: () -> Unit) {
    // A failure keeps the bar where the analysis stopped, frozen, as NukeCore's dialog does.
    var last by remember { mutableFloatStateOf(0f) }
    val current = stage.progress()
    if (current != null) SideEffect { last = current }
    val progress = current ?: last
    val failed = stage is InitStage.Failed
    val colors = NukeTheme.colors
    if (stage is InitStage.Restarting) {
        LaunchedEffect(Unit) {
            delay(RESTART_DELAY_MILLIS)
            onRestart()
        }
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(horizontal = 24.dp).widthIn(min = 300.dp, max = 520.dp).fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            color = colors.surface,
            tonalElevation = 6.dp,
            shadowElevation = 10.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.init_analysis_title),
                        modifier = Modifier.weight(1f),
                        color = colors.textPrimary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.sp,
                    )
                    Text(
                        text = "${(progress * 100f).toInt()}%",
                        modifier = Modifier.padding(start = 16.dp),
                        color = colors.textSecondary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                Text(
                    text = analysisMessage(stage),
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = colors.textSecondary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    letterSpacing = 0.sp,
                )
                NukeLinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), animated = !failed)
                if (failed) {
                    Row(Modifier.align(Alignment.End)) {
                        TextButton(onClick = onClose) { Text(stringResource(R.string.init_close), color = colors.textSecondary) }
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.init_retry), color = colors.accentText) }
                    }
                }
            }
        }
    }
}

@Composable
private fun analysisMessage(stage: InitStage): String = when (stage) {
    is InitStage.Analyzing -> when (stage.step) {
        AnalysisStep.LOADING_NATIVE -> stringResource(R.string.init_step_native)
        AnalysisStep.OPENING_DEX -> stringResource(R.string.init_step_environment, 0, stage.total)
        AnalysisStep.SEARCHING -> stringResource(R.string.init_analyzing, stage.done, stage.total)
    }
    is InitStage.Failed -> stringResource(R.string.init_failed, stage.reason)
    is InitStage.Restarting ->
        if (stage.failed == 0) stringResource(R.string.init_restarting)
        else stringResource(R.string.init_restarting_failed, stage.failed, stage.searched)
    else -> ""
}

@Composable
private fun ColumnScope.Choices(
    language: LanguageChoice,
    bugReports: Boolean,
    onLanguage: (LanguageChoice) -> Unit,
    onBugReports: (Boolean) -> Unit,
) {
    NukeDialogBodyText(stringResource(R.string.init_intro))
    Spacer(Modifier.height(20.dp))
    Text(
        stringResource(R.string.home_language),
        style = MaterialTheme.typography.labelMedium,
        color = NukeTheme.colors.textSecondary,
    )
    Spacer(Modifier.height(8.dp))
    val languages = LanguageChoice.entries
    NukeSegmentedControl(
        selectedIndex = languages.indexOf(language),
        options = languages.map { stringResource(it.label) },
        onOptionSelected = { onLanguage(languages[it]) },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    BugReportsRow(bugReports, onBugReports)
}

// The opt-in shared by the init and the host settings. What it promises has to match what the build does.
@Composable
internal fun BugReportsRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    NukeSettingRow(
        title = stringResource(R.string.init_bug_reports),
        subtitle = stringResource(R.string.init_bug_reports_description),
        onClick = { onChange(!checked) },
        role = Role.Switch,
        modifier = Modifier.semantics { toggleableState = ToggleableState(checked) },
        // The row carries the toggle semantics; the switch only mirrors the value.
        trailingContent = { NukeSwitch(checked = checked, onCheckChanged = null) },
    )
}

private val LanguageChoice.label: Int
    get() = when (this) {
        LanguageChoice.SYSTEM -> R.string.home_language_system
        LanguageChoice.ENGLISH -> R.string.home_language_english
        LanguageChoice.CHINESE -> R.string.home_language_chinese
    }
