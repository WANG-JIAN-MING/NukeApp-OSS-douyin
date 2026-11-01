package io.github.nuke.oss.ui.hostsettings.script

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.github.nuke.oss.R
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.GlyphIcon
import io.github.nuke.oss.designsystem.NukeEmptyState
import io.github.nuke.oss.designsystem.NukeScreenSurface
import io.github.nuke.oss.designsystem.NukeSegmentedControl
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.NukeTopAppBar
import io.github.nuke.oss.designsystem.nukeClick
import io.github.nuke.oss.ui.home.openInBrowser
import kotlinx.coroutines.launch

// Where the SDK and its README describe writing and packaging scripts.
internal const val SCRIPT_DOCS_URL = "https://github.com/dartcv/Nuke-OSS/tree/dev/sdk/script-sdk"

private enum class ScriptsTab(@StringRes val title: Int) {
    INSTALLED(R.string.script_tab_installed),
    STORE(R.string.script_tab_store),
}

// The scripts page, ported from the legacy module's script settings: installed scripts and the store as two tabs under
// the title bar, which carries the import and documentation buttons.
@Composable
internal fun ScriptsDestination(model: ScriptsModel, onBack: (Offset) -> Unit) {
    val context = LocalContext.current
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var importRequest by remember { mutableIntStateOf(0) }
    val tab = ScriptsTab.entries[selected.coerceIn(0, ScriptsTab.entries.lastIndex)]
    val padding = PaddingValues(top = 68.dp, bottom = 20.dp)

    NukeScreenSurface {
        Column(Modifier.fillMaxSize()) {
            NukeTopAppBar(
                title = stringResource(R.string.script_home_entry),
                onBackClickAt = onBack,
                actions = {
                    if (tab == ScriptsTab.INSTALLED && model.available) {
                        ToolbarButton(Glyph.Upload, stringResource(R.string.script_import_action)) { importRequest++ }
                    }
                    ToolbarButton(Glyph.Book, stringResource(R.string.script_docs_action)) { openInBrowser(context, SCRIPT_DOCS_URL) }
                },
            )
            Box(Modifier.fillMaxSize()) {
                AnimatedContent(
                    targetState = tab,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        val enter = fadeIn(tween(180)) + slideInHorizontally(tween(220)) { width -> direction * width / 12 }
                        val exit = fadeOut(tween(140)) + slideOutHorizontally(tween(180)) { width -> -direction * width / 16 }
                        enter togetherWith exit
                    },
                    label = "ScriptsTab",
                ) { current ->
                    when (current) {
                        ScriptsTab.INSTALLED -> InstalledScripts(model, Modifier.fillMaxSize(), padding)
                        ScriptsTab.STORE -> ScriptEmptyState(
                            Glyph.Store,
                            stringResource(R.string.script_store_empty_title),
                            stringResource(R.string.script_store_empty_message),
                            Modifier.fillMaxSize().padding(padding),
                        )
                    }
                }
                NukeSegmentedControl(
                    selectedIndex = selected,
                    options = ScriptsTab.entries.map { stringResource(it.title) },
                    onOptionSelected = { selected = it },
                    modifier = Modifier.align(Alignment.TopCenter).zIndex(2f).padding(start = 18.dp, top = 8.dp, end = 18.dp),
                )
            }
        }
    }
    if (model.available) ScriptImportFlow(model, importRequest)
}

@Composable
private fun InstalledScripts(model: ScriptsModel, modifier: Modifier, padding: PaddingValues) {
    val scope = rememberCoroutineScope()
    val items by remember(model) { model.items() }.collectAsState(initial = null)
    var actionError by remember { mutableStateOf<String?>(null) }
    var authorizing by remember { mutableStateOf<ScriptItem?>(null) }
    var authorizingBusy by remember { mutableStateOf(false) }
    var readmeFor by rememberSaveable { mutableStateOf<String?>(null) }
    var logsFor by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsFor by rememberSaveable { mutableStateOf<String?>(null) }
    val scripts = items.orEmpty()

    fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                actionError = null
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                actionError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    when {
        !model.available -> ScriptEmptyState(
            Glyph.Warning,
            stringResource(R.string.script_unavailable_title),
            stringResource(R.string.script_unavailable_message),
            modifier.padding(padding),
        )
        items == null -> ScriptLoadingState(
            stringResource(R.string.script_scanning_title),
            stringResource(R.string.script_scanning_message),
            modifier.padding(padding),
        )
        scripts.isEmpty() -> ScriptEmptyState(
            Glyph.Puzzle,
            stringResource(R.string.script_none_title),
            stringResource(R.string.script_none_message),
            modifier.padding(padding),
        )
        else -> LazyColumn(
            modifier = modifier.padding(horizontal = 18.dp),
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(scripts, key = { it.id }) { script ->
                InstalledScriptCard(
                    script = script,
                    onRunningChange = { running ->
                        run {
                            if (!model.setRunning(script.id, running)) authorizing = script
                        }
                    },
                    onReadmeClick = { readmeFor = script.id },
                    onLogsClick = { logsFor = script.id },
                    onSettingsClick = { settingsFor = script.id },
                )
            }
            actionError?.let { message ->
                item(key = "error") { NukeEmptyState(stringResource(R.string.script_operation_failed), message) }
            }
        }
    }

    ScriptReadmeDialog(model, scripts.firstOrNull { it.id == readmeFor && it.hasReadme }) { readmeFor = null }
    ScriptLogDialog(model, scripts.firstOrNull { it.id == logsFor }) { logsFor = null }
    ScriptSettingsDialog(model, scripts.firstOrNull { it.id == settingsFor }) { settingsFor = null }
    authorizing?.let { script ->
        ScriptAuthorizationDialog(
            visible = true,
            script = script,
            granting = true,
            confirmText = stringResource(R.string.script_authorize_and_enable),
            busy = authorizingBusy,
            onDismiss = { if (!authorizingBusy) authorizing = null },
            onConfirm = {
                authorizingBusy = true
                run {
                    try {
                        model.grant(script.id, enable = true)
                        authorizing = null
                    } finally {
                        authorizingBusy = false
                    }
                }
            },
        )
    }
}

@Composable
private fun ToolbarButton(glyph: Glyph, description: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .semantics { this.contentDescription = description }
            .nukeClick(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, null, iconSize = 21.dp)
    }
}

@Composable
internal fun ScriptEmptyState(glyph: Glyph, title: String, message: String, modifier: Modifier = Modifier) {
    val colors = NukeTheme.colors
    ScriptStateLayout(title, message, modifier) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(RoundedCornerShape(topStart = 38.dp, topEnd = 22.dp, bottomEnd = 40.dp, bottomStart = 28.dp))
                .background(lerp(colors.surface, colors.accent, if (colors.isLight) 0.14f else 0.22f)),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.accent) {
                GlyphIcon(glyph, null, iconSize = 42.dp)
            }
        }
    }
}

@Composable
internal fun ScriptLoadingState(title: String, message: String, modifier: Modifier = Modifier) {
    val colors = NukeTheme.colors
    ScriptStateLayout(title, message, modifier) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 20.dp, bottomEnd = 32.dp, bottomStart = 24.dp))
                .background(lerp(colors.surface, colors.accent, if (colors.isLight) 0.12f else 0.20f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(38.dp), color = colors.accent, strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun ScriptStateLayout(title: String, message: String, modifier: Modifier, graphic: @Composable () -> Unit) {
    val colors = NukeTheme.colors
    Box(modifier.fillMaxSize().padding(horizontal = 28.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            graphic()
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                color = colors.textPrimary,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                color = colors.textSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// The document picker answers the settings Activity, which runs in the host process, so the package is read with
// the host's grant to it.
@Composable
private fun ScriptImportFlow(model: ScriptsModel, request: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<ImportState>(ImportState.Idle) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        state = ImportState.Inspecting
        scope.launch {
            state = try {
                ImportState.Ready(model.stage { context.contentResolver.openInputStream(uri) })
            } catch (error: ImportFailure) {
                ImportState.Failed(error.code, error.message.orEmpty())
            } catch (error: java.io.IOException) {
                ImportState.Failed("IO", error.message.orEmpty())
            }
        }
    }
    LaunchedEffect(request) {
        if (request > 0) launcher.launch(arrayOf("*/*"))
    }
    ScriptImportDialogs(
        state = state,
        onDismiss = { current ->
            state = ImportState.Idle
            if (current is ImportState.Ready) scope.launch { model.discard(current.import) }
        },
        onConfirm = { ready ->
            state = ImportState.Installing(ready.import)
            scope.launch {
                state = try {
                    model.install(ready.import)
                    ImportState.Idle
                } catch (error: ImportFailure) {
                    ImportState.Failed(error.code, error.message.orEmpty())
                }
            }
        },
    )
}

internal sealed interface ImportState {
    data object Idle : ImportState
    data object Inspecting : ImportState
    data class Ready(val import: StagedImport) : ImportState
    data class Installing(val import: StagedImport) : ImportState
    data class Failed(val code: String, val detail: String) : ImportState
}
