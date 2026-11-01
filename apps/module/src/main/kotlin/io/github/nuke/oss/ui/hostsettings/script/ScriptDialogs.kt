package io.github.nuke.oss.ui.hostsettings.script

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.compose.LazyMarkdownSuccess
import com.mikepenz.markdown.compose.components.MarkdownComponents
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.script.ConfigField
import io.github.nuke.oss.app.shared.script.ConfigProblem
import io.github.nuke.oss.app.shared.script.ConfigType
import io.github.nuke.oss.app.shared.script.ScriptLogEntry
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.GlyphIcon
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogBodyText
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeSelectRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTextField
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.nukeClick
import io.github.nuke.oss.script.ScriptPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LargeDialog = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp)

// Keeps the last script while a closing dialog animates out, as the legacy dialogs did.
@Composable
private fun rememberShown(script: ScriptItem?): ScriptItem? {
    var shown by remember { mutableStateOf<ScriptItem?>(null) }
    LaunchedEffect(script) {
        if (script != null) shown = script else {
            kotlinx.coroutines.delay(180)
            shown = null
        }
    }
    return script ?: shown
}

@Composable
private fun metadataOf(script: ScriptItem): String = remember(script.version, script.author) {
    listOfNotNull(script.version.trim().takeIf(String::isNotEmpty), script.author?.trim()?.takeIf(String::isNotEmpty))
        .joinToString(" • ")
}

@Composable
private fun Metadata(text: String) {
    if (text.isEmpty()) return
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth(),
        color = NukeTheme.colors.textSecondary,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(14.dp))
}

@Composable
private fun CenteredMessage(message: String, loading: Boolean = false, glyph: Glyph? = null) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(30.dp), color = NukeTheme.colors.accent, strokeWidth = 2.5.dp)
        } else if (glyph != null) {
            CompositionLocalProvider(LocalContentColor provides NukeTheme.colors.textSecondary) { GlyphIcon(glyph, null, iconSize = 32.dp) }
        }
        Spacer(Modifier.height(12.dp))
        Text(message, color = NukeTheme.colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center)
    }
}

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data object Failed : Loaded<Nothing>
    data class Ready<T>(val value: T) : Loaded<T>
}

@Composable
internal fun ScriptReadmeDialog(model: ScriptsModel, script: ScriptItem?, onDismiss: () -> Unit) {
    val shown = rememberShown(script) ?: return
    val readme by produceState<Loaded<String?>>(Loaded.Loading, shown.id) {
        value = try {
            Loaded.Ready(model.readme(shown.id)?.takeIf(String::isNotBlank))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Loaded.Failed
        }
    }
    val components = rememberMarkdownComponents()
    val typography = markdownTypographyOf()
    NukeDialog(
        visible = script != null,
        onDismissRequest = onDismiss,
        title = shown.name,
        modifier = LargeDialog.fillMaxHeight(0.78f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogAccentButton(stringResource(R.string.script_close), onDismiss, Modifier.weight(1f))
        },
    ) {
        Metadata(metadataOf(shown))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val state = readme) {
                Loaded.Loading -> CenteredMessage(stringResource(R.string.script_readme_loading), loading = true)
                Loaded.Failed -> CenteredMessage(stringResource(R.string.script_readme_failed), glyph = Glyph.Warning)
                is Loaded.Ready -> state.value?.let { content ->
                    Markdown(
                        content = content,
                        typography = typography,
                        components = components,
                        modifier = Modifier.fillMaxSize(),
                        loading = { CenteredMessage(stringResource(R.string.script_readme_loading), loading = true) },
                        success = { markdown, resolved, successModifier ->
                            LazyMarkdownSuccess(markdown, resolved, successModifier.fillMaxSize(), contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp))
                        },
                        error = { CenteredMessage(stringResource(R.string.script_readme_failed), glyph = Glyph.Warning) },
                    )
                } ?: CenteredMessage(stringResource(R.string.script_readme_empty), glyph = Glyph.Document)
            }
        }
    }
}

@Composable
private fun rememberMarkdownComponents(): MarkdownComponents = remember {
    markdownComponents(
        codeBlock = { MarkdownHighlightedCodeBlock(content = it.content, node = it.node, style = it.typography.code, showHeader = true) },
        codeFence = { MarkdownHighlightedCodeFence(content = it.content, node = it.node, style = it.typography.code, showHeader = true) },
        table = {
            MarkdownTable(
                content = it.content,
                node = it.node,
                style = it.typography.table,
                headerBlock = { content, header, tableWidth, style ->
                    MarkdownTableHeader(content = content, header = header, tableWidth = tableWidth, style = style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                },
                rowBlock = { content, row, tableWidth, style ->
                    MarkdownTableRow(content = content, header = row, tableWidth = tableWidth, style = style, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                },
            )
        },
    )
}

@Composable
private fun markdownTypographyOf(): MarkdownTypography {
    val body = TextStyle(fontSize = 14.sp, lineHeight = 22.sp)
    val code = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace)
    return markdownTypography(
        h1 = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
        h2 = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
        h3 = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
        h4 = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
        h5 = TextStyle(fontSize = 14.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
        h6 = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        quote = body.copy(fontStyle = FontStyle.Italic),
        code = code,
        inlineCode = code.copy(fontSize = 12.sp, lineHeight = 18.sp),
        table = code.copy(fontFamily = FontFamily.Default),
        textLink = TextLinkStyles(style = SpanStyle(fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline)),
    )
}

@Composable
internal fun ScriptLogDialog(model: ScriptsModel, script: ScriptItem?, onDismiss: () -> Unit) {
    val shown = rememberShown(script) ?: return
    val lines by remember(shown.id) { model.logs(shown.id) }.collectAsState(initial = null)
    NukeDialog(
        visible = script != null,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.script_logs_title, shown.name),
        modifier = LargeDialog.fillMaxHeight(0.78f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogTextButton(
                text = stringResource(R.string.script_logs_clear),
                enabled = !lines.isNullOrEmpty(),
                onClick = { model.clearLogs(shown.id) },
                modifier = Modifier.weight(1f),
            )
            NukeDialogAccentButton(stringResource(R.string.script_close), onDismiss, Modifier.weight(1f))
        },
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            val current = lines
            when {
                current == null -> CenteredMessage(stringResource(R.string.script_readme_loading), loading = true)
                current.isEmpty() -> CenteredMessage(stringResource(R.string.script_logs_empty), glyph = Glyph.Terminal)
                else -> LogViewer(current)
            }
        }
    }
}

@Composable
private fun LogViewer(lines: List<ScriptLogEntry>) {
    val scroll = rememberScrollState()
    val format = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT) }
    val text = remember(lines) { lines.joinToString("\n") { "${format.format(Date(it.time))} ${it.level.name.first()} ${it.message}" } }
    // Follows the newest line unless the user scrolled up to read.
    LaunchedEffect(text) {
        val following = scroll.maxValue - scroll.value <= 48
        kotlinx.coroutines.delay(16)
        if (following) scroll.scrollTo(scroll.maxValue)
    }
    SelectionContainer(Modifier.fillMaxSize()) {
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
            color = NukeTheme.colors.textPrimary,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 17.sp,
        )
    }
}

@Composable
internal fun permissionLabel(name: String): String = when (name) {
    ScriptPermissions.NETWORK -> stringResource(R.string.script_permission_network)
    ScriptPermissions.JAVA -> stringResource(R.string.script_permission_java)
    ScriptPermissions.HOOK -> stringResource(R.string.script_permission_hook)
    ScriptPermissions.EXTERNAL_FILES -> stringResource(R.string.script_permission_external_files)
    ScriptPermissions.HOST_FILES -> stringResource(R.string.script_permission_host_files)
    // Host capabilities are named by the host that offers them; this screen is shared, so it only knows their names.
    "douyin.account" -> stringResource(R.string.script_permission_douyin_account)
    "douyin.messaging" -> stringResource(R.string.script_permission_douyin_messaging)
    else -> name
}

@Composable
private fun PermissionList(script: ScriptItem) {
    script.dangerous.forEachIndexed { index, permission ->
        if (index > 0) Spacer(Modifier.height(10.dp))
        Text(permissionLabel(permission.name), color = NukeTheme.colors.textPrimary, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(permission.reason ?: stringResource(R.string.script_no_reason), color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp)
    }
}

@Composable
internal fun ScriptAuthorizationDialog(
    visible: Boolean,
    script: ScriptItem,
    granting: Boolean,
    confirmText: String,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    NukeDialog(
        visible = visible,
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(if (granting) R.string.script_authorize_title else R.string.script_revoke_title),
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f), enabled = !busy)
            NukeDialogAccentButton(confirmText, onConfirm, Modifier.weight(1f), enabled = !busy)
        },
    ) {
        NukeDialogBodyText(
            when {
                granting -> stringResource(R.string.script_authorization_request, script.name)
                script.running -> stringResource(R.string.script_revoke_running, script.name)
                else -> stringResource(R.string.script_revoke_stopped, script.name)
            },
        )
        Spacer(Modifier.height(14.dp))
        PermissionList(script)
    }
}

private enum class SettingsPage { OVERVIEW, CONFIG, AUTHORIZE, REVOKE, DELETE }

@Composable
internal fun ScriptSettingsDialog(model: ScriptsModel, script: ScriptItem?, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf(SettingsPage.OVERVIEW) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(script?.id) {
        if (script != null) {
            page = SettingsPage.OVERVIEW
            error = null
        }
    }
    val shown = rememberShown(script) ?: return
    val separator = stringResource(R.string.script_permission_separator)

    fun act(block: suspend () -> Unit) {
        busy = true
        scope.launch {
            try {
                block()
                error = null
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: failure.javaClass.simpleName
            } finally {
                busy = false
                page = SettingsPage.OVERVIEW
            }
        }
    }

    NukeDialog(
        visible = script != null && page == SettingsPage.OVERVIEW,
        onDismissRequest = onDismiss,
        title = shown.name,
        modifier = LargeDialog.fillMaxHeight(0.78f),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogAccentButton(stringResource(R.string.script_close), onDismiss, Modifier.weight(1f), enabled = !busy)
        },
    ) {
        Metadata(metadataOf(shown))
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item(key = "config_section") { SectionTitle(stringResource(R.string.script_settings_config_section)) }
            item(key = "config") {
                SettingsRow(
                    glyph = Glyph.Sliders,
                    title = stringResource(R.string.script_settings_config_entry),
                    subtitle = if (shown.configFields == 0) stringResource(R.string.script_settings_no_config)
                    else stringResource(R.string.script_settings_config_count, shown.configFields),
                    enabled = shown.configFields > 0 && !busy,
                    onClick = { page = SettingsPage.CONFIG },
                )
            }
            item(key = "permissions_space") { Spacer(Modifier.height(14.dp)) }
            item(key = "permissions_section") { SectionTitle(stringResource(R.string.script_settings_permissions_section)) }
            item(key = "declared") {
                val declared = shown.declared.map { name ->
                    if (name == ScriptPermissions.NETWORK) {
                        val scope = if (shown.anyNetwork) stringResource(R.string.script_permission_network_any) else shown.networkOrigins.joinToString(separator)
                        "${permissionLabel(name)} ($scope)"
                    } else permissionLabel(name)
                }
                SettingsRow(
                    glyph = Glyph.Info,
                    title = stringResource(R.string.script_settings_declared),
                    subtitle = declared.takeIf { it.isNotEmpty() }?.joinToString(separator) ?: stringResource(R.string.script_settings_no_extra),
                )
            }
            if (shown.dangerous.isNotEmpty()) {
                item(key = "dangerous") {
                    SettingsRow(
                        glyph = Glyph.Shield,
                        title = stringResource(R.string.script_settings_dangerous),
                        subtitle = stringResource(
                            if (shown.granted) R.string.script_settings_dangerous_granted else R.string.script_settings_dangerous_pending,
                            shown.dangerous.size,
                        ),
                        accent = !shown.granted,
                        enabled = !busy,
                        onClick = { page = if (shown.granted) SettingsPage.REVOKE else SettingsPage.AUTHORIZE },
                    )
                }
                items(shown.dangerous, key = { "permission:${it.name}" }) { permission ->
                    PermissionReason(permissionLabel(permission.name), permission.reason, shown.granted)
                }
            }
            item(key = "manage_space") { Spacer(Modifier.height(14.dp)) }
            item(key = "manage_section") { SectionTitle(stringResource(R.string.script_settings_management_section)) }
            item(key = "delete") {
                SettingsRow(
                    glyph = Glyph.Trash,
                    title = stringResource(R.string.script_settings_delete),
                    subtitle = stringResource(R.string.script_settings_delete_summary),
                    destructive = true,
                    enabled = !busy,
                    onClick = { page = SettingsPage.DELETE },
                )
            }
            error?.let { message ->
                item(key = "error") {
                    Text(message, Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp), color = NukeTheme.colors.danger, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
        }
    }

    if (script != null && page == SettingsPage.CONFIG) {
        ScriptConfigDialog(model, shown, onDismiss = { page = SettingsPage.OVERVIEW })
    }
    ScriptAuthorizationDialog(
        visible = script != null && page == SettingsPage.AUTHORIZE,
        script = shown,
        granting = true,
        confirmText = stringResource(R.string.script_authorize),
        busy = busy,
        onDismiss = { page = SettingsPage.OVERVIEW },
        onConfirm = { act { model.grant(shown.id, enable = false) } },
    )
    ScriptAuthorizationDialog(
        visible = script != null && page == SettingsPage.REVOKE,
        script = shown,
        granting = false,
        confirmText = stringResource(R.string.script_revoke),
        busy = busy,
        onDismiss = { page = SettingsPage.OVERVIEW },
        onConfirm = { act { model.revoke(shown.id) } },
    )
    NukeDialog(
        visible = script != null && page == SettingsPage.DELETE,
        onDismissRequest = { if (!busy) page = SettingsPage.OVERVIEW },
        title = stringResource(R.string.script_delete_title),
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), { page = SettingsPage.OVERVIEW }, Modifier.weight(1f), enabled = !busy)
            NukeDialogAccentButton(
                text = stringResource(if (busy) R.string.script_deleting else R.string.script_delete_confirm),
                onClick = {
                    act {
                        model.delete(shown.id)
                        onDismiss()
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = !busy,
            )
        },
    ) {
        NukeDialogBodyText(stringResource(R.string.script_delete_message, shown.name))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(horizontal = 4.dp, vertical = 6.dp), color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SettingsRow(
    glyph: Glyph,
    title: String,
    subtitle: String,
    accent: Boolean = false,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val colors = NukeTheme.colors
    val highlight = if (destructive) colors.danger else colors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.nukeClick(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(highlight.copy(alpha = if (accent || destructive) 0.14f else 0.09f)),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalContentColor provides highlight) { GlyphIcon(glyph, null, iconSize = 19.dp) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = if (enabled || onClick == null) colors.textPrimary else colors.textSecondary, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, color = if (accent || destructive) highlight else colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
        if (onClick != null) {
            Spacer(Modifier.width(10.dp))
            CompositionLocalProvider(LocalContentColor provides colors.textSecondary.copy(alpha = 0.72f)) {
                GlyphIcon(Glyph.ChevronRight, null, iconSize = 20.dp)
            }
        }
    }
}

@Composable
private fun PermissionReason(label: String, reason: String?, granted: Boolean) {
    val colors = NukeTheme.colors
    Row(Modifier.fillMaxWidth().padding(start = 52.dp, top = 7.dp, end = 6.dp, bottom = 7.dp), verticalAlignment = Alignment.Top) {
        CompositionLocalProvider(LocalContentColor provides if (granted) colors.accent else colors.textSecondary.copy(alpha = 0.55f)) {
            GlyphIcon(if (granted) Glyph.CheckCircle else Glyph.Circle, null, Modifier.padding(top = 2.dp), iconSize = 15.dp)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textPrimary, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
            Text(reason ?: stringResource(R.string.script_no_reason), Modifier.padding(top = 1.dp), color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// Edits a draft of the script's settings; saving applies them to a running script at once.
@Composable
private fun ScriptConfigDialog(model: ScriptsModel, script: ScriptItem, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val form by produceState<Loaded<ScriptConfigForm?>>(Loaded.Loading, script.id) {
        value = try {
            Loaded.Ready(model.config(script.id))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Loaded.Failed
        }
    }
    var draft by remember(form) { mutableStateOf((form as? Loaded.Ready)?.value?.values.orEmpty()) }
    var editing by remember { mutableStateOf<ConfigField?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val loaded = (form as? Loaded.Ready)?.value
    NukeDialog(
        visible = editing == null,
        onDismissRequest = { if (!saving) onDismiss() },
        title = script.name,
        modifier = LargeDialog,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f), enabled = !saving)
            NukeDialogAccentButton(
                text = stringResource(R.string.home_save),
                enabled = loaded != null && !saving && draft != loaded.values,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            model.saveConfig(script.id, draft)
                            onDismiss()
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = failure.message ?: failure.javaClass.simpleName
                        } finally {
                            saving = false
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        when (val state = form) {
            Loaded.Loading -> Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = NukeTheme.colors.accent, strokeWidth = 2.5.dp)
            }
            Loaded.Failed -> NukeDialogBodyText(stringResource(R.string.script_operation_failed))
            is Loaded.Ready -> if (state.value == null || state.value.fields.isEmpty()) {
                NukeDialogBodyText(stringResource(R.string.script_settings_no_config))
            } else {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp)) {
                    item(key = "fields") {
                        NukeSettingGroup {
                            state.value.fields.forEach { field ->
                                ConfigRow(field, draft[field.key], enabled = !saving, onChange = { value -> draft = draft + (field.key to value) }) {
                                    editing = field
                                }
                            }
                        }
                    }
                    error?.let { message ->
                        item(key = "error") { NukeDialogBodyText(message, Modifier.padding(top = 12.dp)) }
                    }
                }
            }
        }
    }
    editing?.let { field ->
        ConfigEditDialog(field, draft[field.key], onDismiss = { editing = null }) { value ->
            draft = draft + (field.key to value)
            editing = null
        }
    }
}

@Composable
private fun ConfigRow(field: ConfigField, value: Any?, enabled: Boolean, onChange: (Any?) -> Unit, onEdit: () -> Unit) {
    when (field.type) {
        ConfigType.BOOLEAN -> {
            val checked = value == true
            NukeSettingRow(
                title = field.title,
                subtitle = field.description,
                enabled = enabled,
                onClick = { onChange(!checked) },
                trailingContent = { NukeSwitch(checked = checked, onCheckChanged = { onChange(it) }, enabled = enabled) },
            )
        }
        ConfigType.SELECT -> NukeSelectRow(
            title = field.title,
            subtitle = field.description,
            options = field.options,
            selectedOption = field.options.firstOrNull { it.value == value } ?: field.options.first(),
            onOptionSelected = { onChange(it.value) },
            optionToString = { it.label },
            enabled = enabled,
        )
        else -> NukeNavRow(
            title = field.title,
            subtitle = field.description,
            statusText = value?.let(::summaryOf) ?: stringResource(R.string.script_config_unset),
            enabled = enabled,
            onClick = onEdit,
        )
    }
}

private fun summaryOf(value: Any): String = when (value) {
    is Double -> if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    is String -> value.lineSequence().first().take(24)
    else -> value.toString()
}

@Composable
private fun ConfigEditDialog(field: ConfigField, value: Any?, onDismiss: () -> Unit, onSave: (Any?) -> Unit) {
    var text by remember(field.key) { mutableStateOf(value?.let(::summaryOf)?.let { if (value is String) value else it }.orEmpty()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val numeric = field.type == ConfigType.NUMBER || field.type == ConfigType.INTEGER
    val multiline = field.type == ConfigType.TEXT
    val messages = mapOf(
        ConfigProblem.TYPE to stringResource(
            when (field.type) {
                ConfigType.INTEGER -> R.string.script_config_invalid_integer
                ConfigType.NUMBER -> R.string.script_config_invalid_number
                else -> R.string.script_config_invalid
            },
        ),
        ConfigProblem.TOO_LONG to stringResource(R.string.script_config_too_long, field.maxLength ?: 0),
        ConfigProblem.BELOW_MIN to stringResource(R.string.script_config_below_min, field.min?.let(::summaryOf).orEmpty()),
        ConfigProblem.ABOVE_MAX to stringResource(R.string.script_config_above_max, field.max?.let(::summaryOf).orEmpty()),
    )
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = field.title,
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f))
            NukeDialogAccentButton(
                text = stringResource(R.string.home_save),
                onClick = {
                    val parsed: Any? = when (field.type) {
                        ConfigType.INTEGER -> text.trim().toLongOrNull()
                        ConfigType.NUMBER -> text.trim().toDoubleOrNull()
                        else -> text
                    }
                    // An emptied number goes back to its default.
                    if (numeric && text.isBlank()) return@NukeDialogAccentButton onSave(null)
                    val found = if (parsed == null) ConfigProblem.TYPE else field.problem(parsed)
                    if (found == null) onSave(parsed) else problem = messages.getValue(found)
                },
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        field.description?.let {
            NukeDialogBodyText(it)
            Spacer(Modifier.height(10.dp))
        }
        NukeTextField(
            value = text,
            onValueChange = {
                text = it
                problem = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = !multiline,
            minLines = if (multiline) 3 else 1,
            maxLines = if (multiline) 8 else 1,
            keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        )
        problem?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = NukeTheme.colors.danger, fontSize = 12.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun ScriptImportDialogs(state: ImportState, onDismiss: (ImportState) -> Unit, onConfirm: (ImportState.Ready) -> Unit) {
    when (state) {
        ImportState.Idle -> Unit
        ImportState.Inspecting -> NukeDialog(visible = true, onDismissRequest = {}, title = stringResource(R.string.script_import_title)) {
            Column(Modifier.fillMaxWidth().height(120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = NukeTheme.colors.accent, strokeWidth = 2.5.dp)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.script_import_inspecting), color = NukeTheme.colors.textSecondary, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
        is ImportState.Ready -> ImportConfirmation(state.import, installing = false, onDismiss = { onDismiss(state) }, onConfirm = { onConfirm(state) })
        is ImportState.Installing -> ImportConfirmation(state.import, installing = true, onDismiss = {}, onConfirm = {})
        is ImportState.Failed -> NukeDialog(
            visible = true,
            onDismissRequest = { onDismiss(state) },
            title = stringResource(R.string.script_import_failed_title),
            actions = { NukeDialogAccentButton(stringResource(R.string.script_close), { onDismiss(state) }, Modifier.weight(1f)) },
        ) {
            NukeDialogBodyText(stringResource(importErrorOf(state.code)))
            if (state.detail.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(state.detail, color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

private fun importErrorOf(code: String): Int = when (code) {
    "INVALID_PACKAGE" -> R.string.script_import_error_package
    "INVALID_MANIFEST" -> R.string.script_import_error_manifest
    "INVALID_CONFIG_SCHEMA" -> R.string.script_import_error_schema
    "UNSUPPORTED_API" -> R.string.script_import_error_api
    "TOO_LARGE" -> R.string.script_import_error_size
    "WRONG_HOST" -> R.string.script_import_error_host
    "NATIVE_UNAVAILABLE" -> R.string.script_import_error_native
    else -> R.string.script_import_error_other
}

@Composable
private fun ImportConfirmation(import: StagedImport, installing: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val manifest = import.staged.manifest
    val metadata = listOfNotNull(manifest.version.trim().takeIf(String::isNotEmpty), manifest.author?.trim()?.takeIf(String::isNotEmpty)).joinToString(" • ")
    val action = stringResource(
        when (import.kind) {
            ImportKind.NEW -> R.string.script_import_install
            ImportKind.UPDATE -> R.string.script_import_update
            ImportKind.REINSTALL -> R.string.script_import_reinstall
            ImportKind.DOWNGRADE -> R.string.script_import_downgrade
        },
    )
    val summary = when (import.kind) {
        ImportKind.NEW -> stringResource(R.string.script_import_new_summary)
        ImportKind.UPDATE -> stringResource(R.string.script_import_update_summary, import.previousVersion.orEmpty())
        ImportKind.REINSTALL -> stringResource(R.string.script_import_reinstall_summary)
        ImportKind.DOWNGRADE -> stringResource(R.string.script_import_downgrade_summary, import.previousVersion.orEmpty())
    }
    NukeDialog(
        visible = true,
        onDismissRequest = { if (!installing) onDismiss() },
        title = manifest.name,
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f), enabled = !installing)
            NukeDialogAccentButton(
                text = if (installing) stringResource(R.string.script_import_installing) else action,
                onClick = onConfirm,
                modifier = Modifier.weight(1f),
                enabled = !installing,
            )
        },
    ) {
        if (metadata.isNotEmpty()) {
            Text(metadata, Modifier.fillMaxWidth(), color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
        }
        Text(summary, Modifier.fillMaxWidth(), color = NukeTheme.colors.accent, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        manifest.description?.trim()?.takeIf(String::isNotEmpty)?.let { description ->
            Text(description, Modifier.fillMaxWidth().padding(top = 14.dp), color = NukeTheme.colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        if (installing) {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(22.dp), color = NukeTheme.colors.accent, strokeWidth = 2.dp)
            }
        }
    }
}
