package io.github.nuke.oss.ui.prompts

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.InfoSheet
import io.github.nuke.oss.app.shared.activity.ModulePackage
import io.github.nuke.oss.app.shared.activity.ModuleResourcesContext
import io.github.nuke.oss.app.shared.activity.ResumedActivity
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.core.BuildIdentity
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.designsystem.NukeCheckbox
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogBodyText
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeSegmentedControl
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.ui.home.NukeThemed
import io.github.nuke.oss.ui.overlay.HostOverlay
import kotlinx.coroutines.delay

// The prompts a feature shows inside the host, drawn with the Nuke design system in the module's appearance, the way
// NukeApp shows its host dialogs. Each dialog anchors on the resumed host Activity and closes with it, and every open
// dialog closes when the feature is turned off. Callbacks run on the main thread, at most once per dialog, and only
// while the feature is on.
internal class ComposePrompts(
    private val featureId: String,
    private val build: BuildIdentity,
    private val logger: Logger,
    private val ui: () -> UiConfig,
) : HostPrompts {
    private val activities = ResumedActivity()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var installed = false
    // Main thread only.
    private var module: Context? = null
    private val shown = LinkedHashSet<Shown>()

    // One dialog on screen, or about to be.
    private inner class Shown {
        var overlay: HostOverlay? = null
        var done = false
            private set

        fun close() {
            done = true
            shown -= this
            overlay?.close()
            overlay = null
        }

        fun finish(action: () -> Unit) {
            if (done) return
            close()
            if (installed) action()
        }
    }

    override fun install(scope: FeatureScope) {
        activities.install(scope, featureId)
        installed = true
        scope.own(AutoCloseable {
            installed = false
            main.post { shown.toList().forEach(Shown::close) }
        })
    }

    override fun toast(text: Int, vararg args: Any) = onMain({}) { activity ->
        val strings = strings(activity) ?: return@onMain
        Toast.makeText(activity.applicationContext, strings.getString(text, *args), Toast.LENGTH_SHORT).show()
    }

    override fun confirm(title: Int, message: Int, messageArgs: Array<out Any>, confirm: Int, onUnavailable: () -> Unit, onConfirm: () -> Unit) =
        dialog(onUnavailable) { dialog ->
            NukeDialog(
                visible = true,
                onDismissRequest = dialog::close,
                title = stringResource(title),
                actions = {
                    NukeDialogTextButton(stringResource(R.string.home_cancel), dialog::close, Modifier.weight(1f))
                    NukeDialogAccentButton(stringResource(confirm), { dialog.finish(onConfirm) }, Modifier.weight(1f))
                },
            ) { NukeDialogBodyText(stringResource(message, *messageArgs)) }
        }

    override fun choose(title: Int, items: List<Any>, cancel: Int, onUnavailable: () -> Unit, onCancel: () -> Unit, onChosen: (Int) -> Unit) =
        dialog(onUnavailable) { dialog ->
            WideDialog(
                title = stringResource(title),
                onDismiss = { dialog.finish(onCancel) },
                actions = { NukeDialogTextButton(stringResource(cancel), { dialog.finish(onCancel) }, Modifier.weight(1f)) },
            ) {
                NukeSettingGroup {
                    items.forEachIndexed { position, item ->
                        if (position > 0) NukeDivider()
                        NukeSettingRow(title = itemText(item), onClick = { dialog.finish { onChosen(position) } })
                    }
                }
            }
        }

    override fun chooseMany(title: Int, items: List<Any>, limit: Int, confirm: Int, cancel: Int, limitMessage: Int, onUnavailable: () -> Unit, onChosen: (Set<Int>) -> Unit) =
        dialog(onUnavailable) { dialog ->
            val context = LocalContext.current
            var ticked by remember { mutableStateOf(emptySet<Int>()) }
            fun tick(position: Int, checked: Boolean) {
                ticked = when {
                    !checked -> ticked - position
                    ticked.size >= limit -> ticked.also {
                        Toast.makeText(context.applicationContext, context.getString(limitMessage, limit), Toast.LENGTH_SHORT).show()
                    }
                    else -> ticked + position
                }
            }
            WideDialog(
                title = stringResource(title),
                onDismiss = dialog::close,
                actions = {
                    NukeDialogTextButton(stringResource(cancel), dialog::close, Modifier.weight(1f))
                    NukeDialogAccentButton(stringResource(confirm), { dialog.finish { onChosen(ticked) } }, Modifier.weight(1f),
                        enabled = ticked.isNotEmpty())
                },
            ) {
                NukeSettingGroup {
                    items.forEachIndexed { position, item ->
                        if (position > 0) NukeDivider()
                        NukeSettingRow(
                            title = itemText(item),
                            onClick = { tick(position, position !in ticked) },
                            independentTrailingContent = {
                                NukeCheckbox(checked = position in ticked, onCheckedChange = { tick(position, it) }, modifier = Modifier.padding(end = 14.dp))
                            },
                        )
                    }
                }
            }
        }

    override fun pick(
        title: Int, label: Int, items: List<Any>, confirm: Int, other: Int, cancel: Int,
        onUnavailable: () -> Unit, onCancel: () -> Unit, onOther: () -> Unit, onChosen: (Int) -> Unit,
    ) = dialog(onUnavailable) { dialog ->
        var picked by remember { mutableIntStateOf(0) }
        NukeDialog(
            visible = true,
            onDismissRequest = { dialog.finish(onCancel) },
            title = stringResource(title),
            actions = {
                NukeDialogTextButton(stringResource(cancel), { dialog.finish(onCancel) }, Modifier.weight(1f))
                NukeDialogTextButton(stringResource(other), { dialog.finish(onOther) }, Modifier.weight(1f))
                NukeDialogAccentButton(stringResource(confirm), { dialog.finish { onChosen(picked) } }, Modifier.weight(1f))
            },
        ) {
            BasicText(
                stringResource(label),
                modifier = Modifier.padding(bottom = 8.dp),
                style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.Medium),
            )
            NukeSegmentedControl(selectedIndex = picked, options = items.map { itemText(it) }, onOptionSelected = { picked = it })
        }
    }

    override fun sheet(content: (string: (Int, Array<out Any>) -> String) -> InfoSheet, onSave: (text: String, done: (message: Int, argument: Any) -> Unit) -> Unit) =
        dialog({}) { dialog ->
            // Built with the themed context, so the texts follow the module's language setting.
            val context = LocalContext.current
            val sheet = remember { content { id, args -> context.getString(id, *args) } }
            InfoSheetContent(sheet, onSave = { text, done -> if (installed) onSave(text, done) }, onClose = dialog::close)
        }

    override fun countdown(title: Int, message: Int, seconds: Int, cancel: Int, onCancel: () -> Unit, onFinish: () -> Unit): AutoCloseable {
        val handle = Shown()
        dialog({}, handle) { dialog ->
            var left by remember { mutableIntStateOf(seconds) }
            LaunchedEffect(Unit) {
                while (left > 0) {
                    delay(1000)
                    left--
                }
                dialog.finish(onFinish)
            }
            NukeDialog(
                visible = true,
                onDismissRequest = {},
                title = stringResource(title),
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                actions = { NukeDialogTextButton(stringResource(cancel), { dialog.finish(onCancel) }, Modifier.weight(1f)) },
            ) { NukeDialogBodyText(stringResource(message, left)) }
        }
        return AutoCloseable { main.post(handle::close) }
    }

    private fun dialog(onUnavailable: () -> Unit, handle: Shown = Shown(), content: @Composable (Shown) -> Unit) = onMain(onUnavailable) { activity ->
        if (handle.done) return@onMain
        strings(activity) ?: return@onMain onUnavailable()
        val context = ModuleResourcesContext.create(activity, build.moduleId, MODULE_THEME)
            ?: return@onMain skipped("module resources are not accessible from the host", onUnavailable)
        // Read once per dialog: the appearance does not change while one is open.
        val appearance = ui()
        handle.overlay = HostOverlay.attach(activity, context) { NukeThemed(appearance) { content(handle) } }
            ?: return@onMain skipped("no content view in ${activity.javaClass.name}", onUnavailable)
        shown += handle
    }

    private fun onMain(onUnavailable: () -> Unit, show: (Activity) -> Unit) {
        main.post {
            if (!installed) return@post
            val activity = activities.get() ?: return@post skipped("no resumed activity", onUnavailable)
            show(activity)
        }
    }

    private fun skipped(reason: String, onUnavailable: () -> Unit) {
        logger.log("feature=$featureId prompt_skipped $reason")
        onUnavailable()
    }

    private fun strings(activity: Activity): Context? = module ?: when (val opened = ModulePackage.open(activity, build)) {
        is ModulePackage.Opened.Ready -> opened.context.also { module = it }
        is ModulePackage.Opened.Unavailable -> null.also { logger.log("feature=$featureId prompt_skipped ${opened.reason}") }
    }

    private companion object {
        const val MODULE_THEME = android.R.style.Theme_Material_Light_NoActionBar
    }
}

// A dialog wide enough for a list, with the list scrolling under a height cap.
@Composable
private fun WideDialog(title: String, onDismiss: () -> Unit, actions: @Composable RowScope.() -> Unit, content: @Composable () -> Unit) {
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = title,
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = actions,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) { content() }
    }
}

// An item that is an Int is a module string ID, anything else is shown as its text.
@Composable
private fun itemText(item: Any): String = if (item is Int) stringResource(item) else item.toString()
