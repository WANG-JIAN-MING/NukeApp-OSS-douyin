package io.github.nuke.oss.patcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.patcher.R

enum class PatchPhase { IDLE, IMPORTING, READY, PATCHING, SUCCEEDED, FAILED }

data class PatcherUiState(
    val appVersion: String,
    val selectedApkName: String? = null,
    val selectedPackageName: String? = null,
    val selectedVersionName: String? = null,
    val moduleApkName: String? = null,
    val moduleVersionName: String? = null,
    val targetSha256: String? = null,
    val moduleSha256: String? = null,
    val phase: PatchPhase = PatchPhase.IDLE,
    val progressMessage: String? = null,
    val outputName: String? = null,
    val outputSha256: String? = null,
    val error: String? = null,
    val logLines: List<String> = emptyList(),
    val isExporting: Boolean = false,
)

private enum class StepStatus(val label: Int) {
    PENDING(R.string.manager_step_pending),
    ACTIVE(R.string.manager_step_active),
    DONE(R.string.manager_step_done),
    FAILED(R.string.manager_step_failed),
}

@Composable
fun PatcherScreen(
    state: PatcherUiState,
    onSelectApk: () -> Unit,
    onPatch: () -> Unit,
    onExportPatchedApk: () -> Unit,
    onSelectModule: () -> Unit,
) {
    NukeTheme {
        var showAbout by rememberSaveable { mutableStateOf(false) }
        val busy = state.isExporting || state.phase == PatchPhase.IMPORTING || state.phase == PatchPhase.PATCHING
        val complete = state.phase == PatchPhase.SUCCEEDED && state.outputName != null
        val moduleReady = state.moduleApkName != null
        val targetReady = state.selectedApkName != null && state.selectedPackageName != null
        // The bottom action always advances the first unfinished step, so the timeline and button agree.
        val action = when {
            complete -> onExportPatchedApk
            !moduleReady -> onSelectModule
            !targetReady -> onSelectApk
            else -> onPatch
        }
        val label = when {
            state.isExporting -> R.string.manager_exporting
            state.phase == PatchPhase.IMPORTING -> R.string.manager_importing
            state.phase == PatchPhase.PATCHING -> R.string.manager_patching
            complete -> R.string.manager_export_patched_action
            !moduleReady -> R.string.manager_select_module
            !targetReady -> R.string.manager_select_apk
            state.phase == PatchPhase.FAILED -> R.string.manager_retry_patch
            else -> R.string.manager_patch_action
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { ActionBar(label, action, busy) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    item { Header { showAbout = true } }
                    item {
                        Panel {
                            Step(1, if (moduleReady) StepStatus.DONE else StepStatus.ACTIVE) { status ->
                                InputStep(
                                    title = stringResource(R.string.manager_module_input_title),
                                    status = status,
                                    filename = state.moduleApkName,
                                    meta = listOfNotNull(state.moduleVersionName),
                                    empty = stringResource(R.string.manager_module_empty),
                                    actionLabel = stringResource(if (moduleReady) R.string.manager_change_module else R.string.manager_select_module),
                                    enabled = !busy,
                                    onSelect = onSelectModule,
                                )
                            }
                            Step(2, when {
                                targetReady -> StepStatus.DONE
                                moduleReady -> StepStatus.ACTIVE
                                else -> StepStatus.PENDING
                            }) { status ->
                                InputStep(
                                    title = stringResource(R.string.manager_input_title),
                                    status = status,
                                    filename = state.selectedApkName,
                                    meta = listOfNotNull(state.selectedPackageName, state.selectedVersionName),
                                    empty = stringResource(R.string.manager_input_empty),
                                    actionLabel = stringResource(if (targetReady) R.string.manager_change_apk else R.string.manager_select_apk),
                                    enabled = !busy,
                                    onSelect = onSelectApk,
                                )
                            }
                            Step(3, when {
                                state.phase == PatchPhase.SUCCEEDED -> StepStatus.DONE
                                !moduleReady || !targetReady -> StepStatus.PENDING
                                state.phase == PatchPhase.FAILED -> StepStatus.FAILED
                                else -> StepStatus.ACTIVE
                            }) { status -> PatchStep(state, status, complete) }
                            Step(4, if (complete) StepStatus.ACTIVE else StepStatus.PENDING, last = true) { status ->
                                ExportStep(state, status, complete)
                            }
                        }
                    }
                    if (state.error != null || state.phase == PatchPhase.FAILED) {
                        item { ErrorPanel(state.error ?: stringResource(R.string.manager_failure_fallback)) }
                    }
                    item { Details(state) }
                }
            }
        }
        if (showAbout) AboutDialog(state.appVersion) { showAbout = false }
    }
}

@Composable
private fun Header(onAbout: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark()
            Overline(stringResource(R.string.manager_app_name), Modifier.weight(1f).padding(start = 10.dp))
            TextButton(onClick = onAbout, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.manager_tab_about))
            }
        }
        Text(stringResource(R.string.manager_patch_title), Modifier.semantics { heading() },
            style = MaterialTheme.typography.displaySmall)
        SecondaryText(stringResource(R.string.manager_patch_description))
    }
}

@Composable
private fun Step(index: Int, status: StepStatus, last: Boolean = false, content: @Composable ColumnScope.(StepStatus) -> Unit) {
    val line = if (status == StepStatus.DONE) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Row(
        Modifier.fillMaxWidth().drawBehind {
            // The rail runs from below the node to the next step, centred on the 28 dp node.
            if (!last) {
                val x = 14.dp.toPx()
                drawLine(line, Offset(x, 34.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), strokeWidth = 1.5.dp.toPx())
            }
        },
    ) {
        StepNode(index, status)
        Column(
            Modifier.weight(1f).padding(start = 16.dp, bottom = if (last) 0.dp else 28.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { content(status) }
    }
}

@Composable
private fun StepNode(index: Int, status: StepStatus) {
    val colors = MaterialTheme.colorScheme
    val fill = when (status) {
        StepStatus.DONE -> colors.primary
        StepStatus.FAILED -> colors.errorContainer
        else -> Color.Transparent
    }
    val border = when (status) {
        StepStatus.ACTIVE -> BorderStroke(1.5.dp, colors.primary)
        StepStatus.PENDING -> BorderStroke(1.dp, colors.outline)
        else -> null
    }
    Box(
        Modifier.size(28.dp).background(fill, CircleShape)
            .then(if (border != null) Modifier.border(border, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            StepStatus.DONE -> {
                val tick = colors.onPrimary
                Canvas(Modifier.size(12.dp)) {
                    val path = Path().apply {
                        moveTo(size.width * 0.08f, size.height * 0.55f)
                        lineTo(size.width * 0.4f, size.height * 0.84f)
                        lineTo(size.width * 0.94f, size.height * 0.2f)
                    }
                    drawPath(path, tick, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            StepStatus.FAILED -> Text("!", style = CodeStyle.copy(fontWeight = FontWeight.Bold), color = colors.onErrorContainer)
            else -> Text(index.toString(), style = CodeStyle.copy(fontWeight = FontWeight.SemiBold),
                color = if (status == StepStatus.ACTIVE) colors.primary else colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepTitle(title: String, status: StepStatus) {
    val description = stringResource(status.label)
    Text(title, Modifier.semantics { heading(); stateDescription = description },
        style = MaterialTheme.typography.titleMedium,
        color = if (status == StepStatus.PENDING) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun InputStep(
    title: String, status: StepStatus, filename: String?, meta: List<String>, empty: String,
    actionLabel: String, enabled: Boolean, onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = status == StepStatus.ACTIVE
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StepTitle(title, status)
            Text(filename ?: empty, style = MaterialTheme.typography.bodyMedium,
                color = if (filename != null) colors.onSurface else colors.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            meta.forEach {
                Text(it, style = CodeStyle.copy(fontSize = 12.sp), color = colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        OutlinedButton(
            onClick = onSelect,
            enabled = enabled,
            modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = actionLabel },
            shape = CircleShape,
            border = BorderStroke(1.dp, if (highlight) colors.primary else colors.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = if (highlight) colors.primaryContainer else Color.Transparent,
                contentColor = if (highlight) colors.onPrimaryContainer else colors.primary,
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(if (filename == null) R.string.manager_choose_file else R.string.manager_replace_file))
        }
    }
}

@Composable
private fun PatchStep(state: PatcherUiState, status: StepStatus, complete: Boolean) {
    StepTitle(stringResource(R.string.manager_step_patch), status)
    val progress = when {
        state.phase == PatchPhase.PATCHING -> state.progressMessage ?: stringResource(R.string.manager_patching)
        state.phase == PatchPhase.SUCCEEDED -> stringResource(R.string.manager_success_title)
        status == StepStatus.FAILED -> stringResource(R.string.manager_failure_retry)
        status == StepStatus.ACTIVE -> stringResource(R.string.manager_ready)
        else -> stringResource(R.string.manager_patch_waiting)
    }
    Text(progress, Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        style = MaterialTheme.typography.bodyMedium,
        color = if (state.phase == PatchPhase.SUCCEEDED) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.onSurfaceVariant)
    if (!complete) {
        Text(stringResource(R.string.manager_signature_brief), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExportStep(state: PatcherUiState, status: StepStatus, complete: Boolean) {
    StepTitle(stringResource(R.string.manager_step_export), status)
    if (!complete) {
        SecondaryText(stringResource(R.string.manager_export_pending))
        return
    }
    SelectionContainer { Text(state.outputName.orEmpty(), style = MaterialTheme.typography.bodyMedium) }
    state.outputSha256?.let { hash ->
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Overline(stringResource(R.string.manager_sha256_label))
            SelectionContainer {
                Text(hash, style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 17.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SecondaryText(stringResource(R.string.manager_success_description), Modifier.padding(top = 4.dp))
}

@Composable
private fun ActionBar(label: Int, onClick: () -> Unit, busy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                    Button(
                        onClick = onClick,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = CircleShape,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Text(stringResource(label), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
    }
}

@Composable
private fun Details(state: PatcherUiState) {
    Column {
        if (state.moduleApkName != null || state.selectedApkName != null) {
            Disclosure(stringResource(R.string.manager_input_details)) {
                state.moduleApkName?.let {
                    FileDetails(stringResource(R.string.manager_module_input_title), it,
                        stringResource(R.string.manager_module_package), state.moduleVersionName, state.moduleSha256)
                }
                state.selectedApkName?.let {
                    FileDetails(stringResource(R.string.manager_input_title), it,
                        state.selectedPackageName, state.selectedVersionName, state.targetSha256)
                }
                SecondaryText(stringResource(R.string.manager_module_source))
            }
        }
        if (state.logLines.isNotEmpty()) {
            Disclosure(stringResource(R.string.manager_log_title)) {
                SecondaryText(stringResource(R.string.manager_log_description))
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
                    SelectionContainer {
                        Text(state.logLines.takeLast(32).joinToString("\n"), Modifier.fillMaxWidth().padding(14.dp),
                            style = CodeStyle.copy(fontSize = 12.sp, lineHeight = 18.sp))
                    }
                }
            }
        }
        Disclosure(stringResource(R.string.manager_compatibility_title)) {
            SecondaryText(stringResource(R.string.manager_input_empty_description))
            SecondaryText(stringResource(R.string.manager_compatibility_description))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.manager_fixture_title))
                SecondaryText(stringResource(R.string.manager_fixture_description))
            }
            SectionTitle(stringResource(R.string.manager_patch_options_title))
            DetailRow(stringResource(R.string.manager_module_label), stringResource(R.string.manager_module_embedded))
            DetailRow(stringResource(R.string.manager_signature_label), stringResource(R.string.manager_signature_local))
            DetailRow(stringResource(R.string.manager_android_label), stringResource(R.string.manager_android_minimum))
            SecondaryText(stringResource(R.string.manager_patch_options_description))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.manager_signature_notice_title))
                SecondaryText(stringResource(R.string.manager_signature_notice))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun FileDetails(title: String, filename: String, packageName: String?, version: String?, hash: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionTitle(title)
        SelectionContainer { Text(filename, style = MaterialTheme.typography.bodyMedium) }
        packageName?.let { DetailRow(stringResource(R.string.manager_package_label), it, code = true) }
        version?.let { DetailRow(stringResource(R.string.manager_version_label), it, code = true) }
        hash?.let { DetailRow(stringResource(R.string.manager_sha256_label), it, code = true) }
    }
}

@Composable
private fun DetailRow(label: String, value: String, code: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Overline(label)
        SelectionContainer {
            Text(value, style = if (code) CodeStyle else MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AboutDialog(version: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
        icon = { BrandMark() },
        title = { Text(stringResource(R.string.manager_about_title), style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SecondaryText(stringResource(R.string.manager_about_description))
                DetailRow(stringResource(R.string.manager_version_label), version, code = true)
                DetailRow(stringResource(R.string.manager_package_label), stringResource(R.string.manager_app_package), code = true)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                SecondaryText(stringResource(R.string.manager_about_architecture))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SectionTitle(stringResource(R.string.manager_patch_engine_title))
                    SecondaryText(stringResource(R.string.manager_patch_engine_description))
                }
                SelectionContainer {
                    Text(stringResource(R.string.manager_patch_engine_source), style = CodeStyle.copy(fontSize = 12.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.manager_close))
            }
        },
    )
}
