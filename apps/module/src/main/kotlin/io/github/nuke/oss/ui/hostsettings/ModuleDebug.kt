package io.github.nuke.oss.ui.hostsettings

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.diagnostics.FailurePhases
import io.github.nuke.oss.app.shared.diagnostics.FailureRecord
import io.github.nuke.oss.app.shared.settings.ConfigImportRejected
import io.github.nuke.oss.app.shared.settings.FeatureDisplay
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.display
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.GlyphIcon
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeEmptyState
import io.github.nuke.oss.designsystem.NukeIconBadge
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTextField
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.nukeClick
import io.github.nuke.oss.ui.home.HomePage
import io.github.nuke.oss.ui.home.SearchBackHandler
import io.github.nuke.oss.ui.init.BugReportsRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// NukeApp's three hooker states. LOCKED: the feature has a failure record, or failed to load, and stays off until it
// is unlocked. UNAVAILABLE: switched on but not running in this process, for example until a restart or in safe
// mode. NORMAL: running as switched, on or off.
internal enum class DebugStatus { NORMAL, UNAVAILABLE, LOCKED }

internal fun debugStatus(feature: HostFeature, record: FailureRecord?): DebugStatus = when {
    record != null || feature.display() == FeatureDisplay.FAILED -> DebugStatus.LOCKED
    feature.display() == FeatureDisplay.ON_AFTER_RESTART -> DebugStatus.UNAVAILABLE
    else -> DebugStatus.NORMAL
}

// One feature as the debug page lists it, with every string resolved, so filtering and the detail text need no
// composition.
internal class DebugItem(
    val feature: HostFeature,
    val title: String,
    val category: String,
    val status: DebugStatus,
    val reason: String,
    val record: FailureRecord?,
) {
    fun matches(query: String): Boolean {
        val texts = listOfNotNull(title, feature.id, reason, category, record?.phase, record?.errorClass, record?.message)
        return texts.any { it.contains(query, ignoreCase = true) }
    }

    // The full report the detail dialog shows and copies, in NukeApp's field order and English labels.
    fun detailText(safeMode: Boolean, host: String?): String = buildString {
        appendLine("Name: $title")
        appendLine("ID: ${feature.id}")
        appendLine("Status: ${status.name}")
        appendLine("Reason: $reason")
        appendLine("Category: $category")
        appendLine("Switch: ${if (feature.configured) "on" else "off"}")
        appendLine("Runtime state: ${feature.state?.name ?: "N/A"}")
        appendLine("Runtime reason: ${feature.reason ?: "N/A"}")
        appendLine("Safe mode: $safeMode")
        appendLine("Host: ${host ?: "N/A"}")
        appendLine("Module version: ${BuildConfig.VERSION_NAME}")
        appendLine("Phase: ${record?.phase ?: "N/A"}")
        appendLine("Time: ${record?.timestamp?.let(::formatTime) ?: "N/A"}")
        appendLine()
        appendLine("Throwable:")
        appendLine(record?.errorClass ?: "N/A")
        appendLine()
        appendLine("Message:")
        appendLine(record?.message ?: "N/A")
        appendLine()
        appendLine("Stack trace:")
        append(record?.stackTrace ?: "N/A")
    }
}

private fun formatTime(millis: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(millis))

@Composable
internal fun debugItems(state: HostSettingsUiState): List<DebugItem> = state.features.map { feature ->
    val record = state.failures[feature.id]
    DebugItem(
        feature = feature,
        title = featureLabels[feature.id]?.let { stringResource(it.title) } ?: feature.id,
        category = stringResource(categoryOf(feature.id).title),
        status = debugStatus(feature, record),
        reason = debugReason(feature, record, state.safeMode),
        record = record,
    )
}.sortedWith(compareByDescending<DebugItem> { it.status.ordinal }.thenBy { it.title })

@Composable
internal fun debugReason(feature: HostFeature, record: FailureRecord?, safeMode: Boolean): String {
    if (record != null) {
        val detail = record.message?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: record.errorClass.substringAfterLast('.')
        return stringResource(R.string.host_debug_reason_record, phaseLabel(record.phase), detail)
    }
    return when (feature.display()) {
        FeatureDisplay.FAILED -> stringResource(R.string.host_feature_failed, feature.reason ?: stringResource(R.string.host_feature_failed_unknown))
        FeatureDisplay.ON_AFTER_RESTART ->
            if (safeMode) stringResource(R.string.host_debug_reason_safe_mode) else stringResource(R.string.host_feature_on_after_restart)
        FeatureDisplay.OFF_AFTER_RESTART -> stringResource(R.string.host_feature_off_after_restart)
        FeatureDisplay.ACTIVE -> stringResource(R.string.host_feature_active)
        FeatureDisplay.OFF -> stringResource(R.string.host_feature_off)
    }
}

@Composable
private fun phaseLabel(phase: String): String = when (phase) {
    FailurePhases.INSTALL -> stringResource(R.string.host_debug_phase_install)
    FailurePhases.DEX_FIND -> stringResource(R.string.host_debug_phase_dex)
    FailurePhases.UNINSTALL -> stringResource(R.string.host_debug_phase_uninstall)
    FailurePhases.BEFORE_CALLBACK -> stringResource(R.string.host_debug_phase_before)
    FailurePhases.AFTER_CALLBACK -> stringResource(R.string.host_debug_phase_after)
    FailurePhases.SCRIPT -> stringResource(R.string.host_debug_phase_script)
    else -> phase
}

// The module settings and debugging page, laid out like NukeApp's: a search field, then actions and the status
// overview, then every feature with its state. Tapping a feature shows the full report, which a long press copies,
// and unlocks it when it has a failure record.
@Composable
internal fun ModuleDebugPage(
    state: HostSettingsUiState,
    onBugReports: (Boolean) -> Unit,
    onUnlock: (String) -> Unit,
    onCleanConfig: () -> Unit,
    onExportFailures: () -> Unit,
    onRestartHost: () -> Unit,
    onBack: (Offset) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmClean by rememberSaveable { mutableStateOf(false) }
    SearchBackHandler(query) { query = "" }
    val items = debugItems(state)
    val needle = query.trim()
    val shown = if (needle.isEmpty()) items else items.filter { it.matches(needle) }
    val featuresTitle = stringResource(R.string.host_debug_features)

    HomePage(stringResource(R.string.host_debug_title), onBack) {
        item(key = "search") {
            NukeTextField(
                value = query,
                onValueChange = { query = it.take(200) },
                placeholder = stringResource(R.string.host_debug_search),
                leadingIcon = {
                    CompositionLocalProvider(LocalContentColor provides NukeTheme.colors.textSecondary) {
                        GlyphIcon(Glyph.Search, null, iconSize = 18.dp)
                    }
                },
            )
        }
        if (needle.isEmpty()) {
            item(key = "actions") {
                NukeSettingGroup(title = stringResource(R.string.host_debug_actions)) {
                    NukeSettingRow(
                        title = stringResource(R.string.host_debug_restart),
                        subtitle = stringResource(R.string.host_debug_restart_description),
                        leadingIcon = { NukeIconBadge(Glyph.Restart) },
                        onClick = onRestartHost,
                    )
                    NukeSettingRow(
                        title = stringResource(R.string.host_debug_failures_export),
                        subtitle = stringResource(R.string.host_debug_failures_export_description),
                        leadingIcon = { NukeIconBadge(Glyph.Bug) },
                        onClick = onExportFailures,
                    )
                    NukeSettingRow(
                        title = stringResource(R.string.host_debug_clean),
                        subtitle = stringResource(R.string.host_debug_clean_description),
                        leadingIcon = { NukeIconBadge(Glyph.Trash, color = NukeTheme.colors.danger) },
                        onClick = { confirmClean = true },
                    )
                    BugReportsRow(state.bugReports == true, onBugReports)
                }
            }
            item(key = "summary") { DebugSummary(items) }
        }
        if (shown.isEmpty()) {
            item(key = "empty") {
                NukeEmptyState(stringResource(R.string.host_debug_empty_title), stringResource(R.string.host_debug_empty_message))
            }
        } else {
            itemsIndexed(shown, key = { _, item -> item.feature.id }) { index, item ->
                NukeSettingGroup(title = if (index == 0) featuresTitle else null) {
                    NukeSettingRow(
                        title = item.title,
                        subtitle = "${item.reason}\n${item.feature.id}",
                        leadingIcon = { DebugStatusMark(item.status) },
                        trailingContent = { DebugStatusPill(item.status) },
                        onClick = { selected = item.feature.id },
                    )
                }
            }
        }
    }

    items.firstOrNull { it.feature.id == selected }?.let { item ->
        DebugDetailDialog(item, item.detailText(state.safeMode, state.host), onDismiss = { selected = null }) {
            onUnlock(item.feature.id)
            selected = null
        }
    }
    if (confirmClean) {
        NukeDialog(
            onDismissRequest = { confirmClean = false },
            title = stringResource(R.string.host_debug_clean_title),
            contentText = stringResource(R.string.host_debug_clean_message),
            confirmButtonText = stringResource(R.string.host_debug_clean_confirm),
            dismissButtonText = stringResource(R.string.home_cancel),
            onConfirm = {
                confirmClean = false
                onCleanConfig()
            },
        )
    }
}

@Composable
private fun DebugSummary(items: List<DebugItem>) {
    NukeSettingGroup(title = stringResource(R.string.host_debug_summary)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DebugPill(stringResource(R.string.host_debug_normal_count, items.count { it.status == DebugStatus.NORMAL }), DebugStatus.NORMAL)
            DebugPill(stringResource(R.string.host_debug_unavailable_count, items.count { it.status == DebugStatus.UNAVAILABLE }), DebugStatus.UNAVAILABLE)
            DebugPill(stringResource(R.string.host_debug_locked_count, items.count { it.status == DebugStatus.LOCKED }), DebugStatus.LOCKED)
        }
    }
}

@Composable
private fun DebugDetailDialog(item: DebugItem, text: String, onDismiss: () -> Unit, onUnlock: () -> Unit) {
    val context = LocalContext.current
    val copied = stringResource(R.string.host_author_copied)
    val label = stringResource(R.string.host_debug_detail_title)
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = label,
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f))
            NukeDialogAccentButton(stringResource(R.string.host_debug_unlock), onUnlock, Modifier.weight(1f), enabled = item.record != null)
        },
    ) {
        BasicText(
            text,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .nukeClick(onLongClick = {
                    context.getSystemService(ClipboardManager::class.java)?.let { clipboard ->
                        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    }
                }) {}
                .verticalScroll(rememberScrollState()),
            style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp),
        )
    }
}

@Composable
internal fun DebugStatusMark(status: DebugStatus) {
    val color = status.color()
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            DebugStatus.NORMAL -> CompositionLocalProvider(LocalContentColor provides color) {
                GlyphIcon(Glyph.Check, null, iconSize = 18.dp)
            }
            DebugStatus.UNAVAILABLE -> MarkText("-", color)
            DebugStatus.LOCKED -> MarkText("!", color)
        }
    }
}

@Composable
private fun MarkText(text: String, color: Color) =
    BasicText(text, style = TextStyle(color = color, fontSize = 18.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold))

@Composable
internal fun DebugStatusPill(status: DebugStatus) = DebugPill(status.label(), status)

@Composable
private fun DebugPill(text: String, status: DebugStatus) {
    val color = status.color()
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).background(color.copy(alpha = 0.13f)).padding(horizontal = 9.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(
            color = color, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold))
    }
}

@Composable
internal fun DebugStatus.color(): Color = when (this) {
    DebugStatus.NORMAL -> NukeTheme.colors.success
    DebugStatus.UNAVAILABLE -> NukeTheme.colors.warning
    DebugStatus.LOCKED -> NukeTheme.colors.danger
}

@Composable
internal fun DebugStatus.label(): String = when (this) {
    DebugStatus.NORMAL -> stringResource(R.string.host_debug_status_normal)
    DebugStatus.UNAVAILABLE -> stringResource(R.string.host_debug_status_unavailable)
    DebugStatus.LOCKED -> stringResource(R.string.host_debug_status_locked)
}

// An import only takes effect in the running features after a restart, so its dialog offers one.
@Composable
internal fun ConfigNoticeDialog(notice: ConfigNotice, onDismiss: () -> Unit, onRestartHost: () -> Unit) {
    val message = when (notice) {
        ConfigNotice.Exported -> stringResource(R.string.host_debug_config_exported)
        ConfigNotice.ExportFailed -> stringResource(R.string.host_debug_config_export_failed)
        is ConfigNotice.Imported -> stringResource(R.string.host_debug_config_imported, notice.count)
        ConfigNotice.ReadFailed -> stringResource(R.string.host_debug_config_read_failed)
        is ConfigNotice.FailuresExported -> stringResource(R.string.host_debug_failures_exported, notice.count)
        ConfigNotice.NoFailures -> stringResource(R.string.host_debug_failures_none)
        ConfigNotice.FailuresExportFailed -> stringResource(R.string.host_debug_config_export_failed)
        is ConfigNotice.Rejected -> when (notice.reason) {
            ConfigImportRejected.Reason.NOT_CONFIG -> stringResource(R.string.host_debug_config_not_config)
            ConfigImportRejected.Reason.OTHER_HOST -> stringResource(R.string.host_debug_config_other_host, notice.detail.orEmpty())
            ConfigImportRejected.Reason.NEWER_VERSION -> stringResource(R.string.host_debug_config_newer)
            ConfigImportRejected.Reason.INVALID_SETTING -> stringResource(R.string.host_debug_config_invalid, notice.detail.orEmpty())
        }
    }
    if (notice is ConfigNotice.Imported) {
        NukeDialog(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.host_debug_config_title),
            contentText = message,
            confirmButtonText = stringResource(R.string.host_debug_restart),
            dismissButtonText = stringResource(R.string.manager_close),
            onConfirm = {
                onDismiss()
                onRestartHost()
            },
        )
    } else {
        NukeDialog(
            onDismissRequest = onDismiss,
            title = stringResource(if (notice.isFailureReport()) R.string.host_debug_failures_title else R.string.host_debug_config_title),
            contentText = message,
            confirmButtonText = stringResource(R.string.manager_close),
            onConfirm = onDismiss,
        )
    }
}

private fun ConfigNotice.isFailureReport() =
    this is ConfigNotice.FailuresExported || this == ConfigNotice.NoFailures || this == ConfigNotice.FailuresExportFailed
