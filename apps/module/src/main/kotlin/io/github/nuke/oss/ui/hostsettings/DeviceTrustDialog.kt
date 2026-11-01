package io.github.nuke.oss.ui.hostsettings

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.integrity.FlagClass
import io.github.nuke.oss.app.shared.integrity.TrustCheck
import io.github.nuke.oss.app.shared.integrity.TrustFlag
import io.github.nuke.oss.app.shared.integrity.TrustLevel
import io.github.nuke.oss.app.shared.integrity.TrustReport
import io.github.nuke.oss.app.shared.integrity.TrustResult
import io.github.nuke.oss.app.shared.integrity.TrustVerdict
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeTheme

// The most serious findings first, the normal ones last; checks of one level keep the order the native side gave.
internal fun trustRows(report: TrustReport): List<TrustCheck> = report.checks.sortedBy { trustRank(it.level) }

private fun trustRank(level: TrustLevel) = when (level) {
    TrustLevel.ALERT -> 0
    TrustLevel.WARN -> 1
    TrustLevel.INFO -> 2
    TrustLevel.UNKNOWN -> 3
    TrustLevel.OK -> 4
}

@StringRes
internal fun trustCheckLabel(id: String): Int? = when (id) {
    "debugger" -> R.string.device_trust_check_debugger
    "injection" -> R.string.device_trust_check_injection
    "frida" -> R.string.device_trust_check_frida
    "descriptors" -> R.string.device_trust_check_descriptors
    "root" -> R.string.device_trust_check_root
    "bootloader" -> R.string.device_trust_check_bootloader
    "selinux" -> R.string.device_trust_check_selinux
    "build" -> R.string.device_trust_check_build
    "emulator" -> R.string.device_trust_check_emulator
    "adbd" -> R.string.device_trust_check_adbd
    "signature_path" -> R.string.device_trust_check_signature_path
    "selinux_policy" -> R.string.device_trust_check_selinux_policy
    "kernel_exposure" -> R.string.device_trust_check_kernel_exposure
    "mounts" -> R.string.device_trust_check_mounts
    "maps_tools" -> R.string.device_trust_check_maps_tools
    "maps_memory" -> R.string.device_trust_check_maps_memory
    "network" -> R.string.device_trust_check_network
    else -> null
}

// The flags of a report grouped by class, the hard ones first, each group in the order the native side gave them.
internal fun trustFlagGroups(report: TrustReport): List<Pair<FlagClass, List<TrustFlag>>> =
    FlagClass.entries.mapNotNull { flagClass ->
        report.flags.filter { it.flagClass == flagClass }.takeIf { it.isNotEmpty() }?.let { flagClass to it }
    }

@StringRes
private fun trustFlagClassLabel(flagClass: FlagClass) = when (flagClass) {
    FlagClass.HARD -> R.string.device_trust_flag_class_hard
    FlagClass.WEAK -> R.string.device_trust_flag_class_weak
    FlagClass.ENV -> R.string.device_trust_flag_class_env
    FlagClass.NOTE -> R.string.device_trust_flag_class_note
}

@StringRes
private fun trustLevelLabel(level: TrustLevel) = when (level) {
    TrustLevel.OK -> R.string.device_trust_level_ok
    TrustLevel.INFO -> R.string.device_trust_level_info
    TrustLevel.WARN -> R.string.device_trust_level_warn
    TrustLevel.ALERT -> R.string.device_trust_level_alert
    TrustLevel.UNKNOWN -> R.string.device_trust_level_unknown
}

@StringRes
private fun trustVerdictLabel(verdict: TrustVerdict) = when (verdict) {
    TrustVerdict.TRUSTED -> R.string.device_trust_verdict_trusted
    TrustVerdict.CAUTION -> R.string.device_trust_verdict_caution
    TrustVerdict.ALERT -> R.string.device_trust_verdict_alert
    TrustVerdict.UNKNOWN -> R.string.device_trust_verdict_unknown
}

@Composable
private fun levelColor(level: TrustLevel) = when (level) {
    TrustLevel.OK -> NukeTheme.colors.success
    TrustLevel.INFO -> NukeTheme.colors.accent
    TrustLevel.WARN -> NukeTheme.colors.warning
    TrustLevel.ALERT -> NukeTheme.colors.danger
    TrustLevel.UNKNOWN -> NukeTheme.colors.textSecondary
}

@Composable
private fun verdictColor(verdict: TrustVerdict) = when (verdict) {
    TrustVerdict.TRUSTED -> levelColor(TrustLevel.OK)
    TrustVerdict.CAUTION -> levelColor(TrustLevel.WARN)
    TrustVerdict.ALERT -> levelColor(TrustLevel.ALERT)
    TrustVerdict.UNKNOWN -> levelColor(TrustLevel.UNKNOWN)
}

// What the device trust checks found, opened from the avatar on the settings screen: the verdict, then every check with
// its level and the short note the native side wrote. The checks run when the dialog opens and only read; nothing is
// stored or sent. The notes come from the native library and are in English.
@Composable
internal fun DeviceTrustDialog(load: suspend () -> TrustResult, onDismiss: () -> Unit) {
    var result by remember { mutableStateOf<TrustResult?>(null) }
    LaunchedEffect(Unit) { result = load() }
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.device_trust_title),
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = { NukeDialogAccentButton(stringResource(R.string.device_trust_close), onDismiss, Modifier.weight(1f)) },
    ) {
        // The body takes only the height the button leaves, so a short screen scrolls it instead of pushing the button out.
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            OptionsBodyText(stringResource(R.string.device_trust_intro))
            when (val current = result) {
                null -> OptionsBodyText(stringResource(R.string.device_trust_loading))
                is TrustResult.Unavailable -> OptionsBodyText(stringResource(R.string.device_trust_unavailable, current.reason), warning = true)
                is TrustResult.Ready -> ReportBody(current.report)
            }
        }
    }
}

// The verdict and the tripwire on one line each, the flags under them in small type, then every check as a two-line row
// in one group. Spacing is tight on purpose: the report is long and is read as a list.
@Composable
private fun ReportBody(report: TrustReport) {
    val verdict = report.verdict
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LevelDot(verdictColor(verdict))
        BasicText(
            stringResource(trustVerdictLabel(verdict)),
            Modifier.weight(1f),
            style = TextStyle(color = verdictColor(verdict), fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold),
        )
    }
    val tripped = report.tripped.size
    BasicText(
        buildAnnotatedString {
            append(stringResource(R.string.device_trust_tripwire))
            append(": ")
            withStyle(SpanStyle(color = if (tripped == 0) levelColor(TrustLevel.OK) else levelColor(TrustLevel.ALERT), fontWeight = FontWeight.SemiBold)) {
                append(if (tripped == 0) stringResource(R.string.device_trust_tripwire_clear) else stringResource(R.string.device_trust_tripwire_tripped, tripped))
            }
        },
        style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 12.sp, lineHeight = 16.sp),
    )
    FlagLines(report)
    NukeSettingGroup {
        Column {
            trustRows(report).forEachIndexed { index, check ->
                if (index > 0) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(0.5.dp).background(NukeTheme.colors.textSecondary.copy(alpha = 0.18f)))
                }
                CompactCheck(check)
            }
        }
    }
}

// The flags that were raised, one line per class: the class, then the names, those that tripped the wire in red.
@Composable
private fun FlagLines(report: TrustReport) {
    val groups = trustFlagGroups(report)
    BasicText(
        stringResource(R.string.device_trust_flags) + " · " + report.flags.size,
        style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    )
    if (groups.isEmpty()) {
        BasicText(stringResource(R.string.device_trust_flags_none), style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp))
        return
    }
    for ((flagClass, flags) in groups) {
        BasicText(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(stringResource(trustFlagClassLabel(flagClass))) }
                append("  ")
                flags.forEachIndexed { index, flag ->
                    if (index > 0) append("  ")
                    withStyle(SpanStyle(color = if (flag.tripped) levelColor(TrustLevel.ALERT) else NukeTheme.colors.textSecondary, fontWeight = if (flag.tripped) FontWeight.SemiBold else FontWeight.Normal)) {
                        append(flag.name)
                    }
                }
            },
            style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp),
        )
    }
    BasicText(stringResource(R.string.device_trust_flags_hint), style = TextStyle(color = NukeTheme.colors.textSecondary.copy(alpha = 0.8f), fontSize = 10.sp, lineHeight = 14.sp))
}

// One check on two lines: the dot, the name and the level; under them the note from the native side.
@Composable
private fun CompactCheck(check: TrustCheck) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LevelDot(levelColor(check.level))
            BasicText(
                trustCheckLabel(check.id)?.let { stringResource(it) } ?: check.id,
                Modifier.weight(1f),
                style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
            )
            BasicText(
                stringResource(trustLevelLabel(check.level)),
                style = TextStyle(color = levelColor(check.level), fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
            )
        }
        if (check.detail.isNotBlank()) {
            BasicText(
                check.detail,
                Modifier.padding(start = 14.dp),
                style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 11.sp, lineHeight = 15.sp),
            )
        }
    }
}

@Composable
private fun LevelDot(color: Color) {
    Box(Modifier.size(8.dp).background(color, CircleShape))
}
