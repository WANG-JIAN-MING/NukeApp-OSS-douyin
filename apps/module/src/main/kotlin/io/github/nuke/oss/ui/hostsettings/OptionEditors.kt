package io.github.nuke.oss.ui.hostsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.ConversationsOption
import io.github.nuke.oss.app.shared.settings.FriendsOption
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.LinesOption
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.Option
import io.github.nuke.oss.app.shared.settings.TextOption
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.designsystem.NukeCheckbox
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeSelectRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTextField
import io.github.nuke.oss.designsystem.NukeTheme

// A feature's options in one dialog, laid out like NukeApp's configuration dialogs: switches and choices are edited
// in place, while lists, numbers, text and chats open a second-level dialog that replaces this one until it closes.
// Everything is edited on a draft and written together on Save; the running feature reads its options when the host
// starts, which the body says.
@Composable
internal fun FeatureOptionsDialog(
    feature: HostFeature,
    title: String,
    onSave: (Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
    loadContacts: suspend () -> HostContactList,
) {
    val initial = remember(feature.id) { feature.options.associate { it.option.key to it.saved } }
    var draft by remember(feature.id) { mutableStateOf(initial) }
    var editing by remember(feature.id) { mutableStateOf<Option?>(null) }
    val options = feature.options.map { it.option }
    fun set(option: Option, value: Any) { draft = draft + (option.key to value) }

    // A feature whose only option is a list of choices shows that list directly, as NukeApp's selection dialogs do.
    val single = options.singleOrNull() as? MultiOption
    val open = editing
    when {
        single != null -> MultiEditor(feature.id, single, title, draft.getValue(single.key).asSet(), onDismiss, saves = true) {
            onSave(mapOf(single.key to it))
            onDismiss()
        }
        open is MultiOption -> MultiEditor(feature.id, open, optionTitle(feature.id, open), draft.getValue(open.key).asSet(),
            { editing = null }) { set(open, it); editing = null }
        open is NumberOption -> NumberEditor(feature.id, open, draft.getValue(open.key) as Long, { editing = null }) {
            set(open, it); editing = null
        }
        open is TextOption -> TextEditor(feature.id, open, draft.getValue(open.key) as String, { editing = null }) {
            set(open, it); editing = null
        }
        open is LinesOption -> LinesEditor(feature.id, open, draft.getValue(open.key).asList(), { editing = null }) {
            set(open, it); editing = null
        }
        open is ConversationsOption -> ConversationPickerDialog(
            title = optionTitle(feature.id, open),
            initial = draft.getValue(open.key).asList().toSet(),
            load = loadContacts,
            onDismiss = { editing = null },
        ) { set(open, it.toList()); editing = null }
        open is FriendsOption -> ConversationPickerDialog(
            title = optionTitle(feature.id, open),
            initial = draft.getValue(open.key).asList().toSet(),
            load = loadContacts,
            onDismiss = { editing = null },
            friendsOnly = true,
        ) { set(open, it.sorted()); editing = null }
        else -> OptionsDialog(
            title = title,
            onDismiss = onDismiss,
            saveEnabled = draft != initial,
            onSave = {
                onSave(draft.filter { (key, value) -> initial[key] != value })
                onDismiss()
            },
        ) {
            NukeSettingGroup {
                for (option in options) OptionRow(feature.id, option, draft.getValue(option.key), { set(option, it) }) { editing = option }
            }
        }
    }
}

@Composable
private fun OptionRow(featureId: String, option: Option, value: Any, onChange: (Any) -> Unit, onOpen: () -> Unit) {
    val label = optionLabel(featureId, option.key)
    val title = label?.let { stringResource(it.title) } ?: option.key
    val subtitle = label?.description?.let { stringResource(it) }
    when (option) {
        is ToggleOption -> {
            val on = value as Boolean
            NukeSettingRow(
                title = title,
                subtitle = subtitle,
                onClick = { onChange(!on) },
                trailingContent = { NukeSwitch(checked = on, onCheckChanged = onChange) },
            )
        }
        is ChoiceOption -> {
            val names = option.choices.associateWith { choiceTitle(label, it) }
            NukeSelectRow(
                title = title,
                subtitle = subtitle,
                options = option.choices,
                selectedOption = value as String,
                onOptionSelected = onChange,
                optionToString = { names.getValue(it) },
            )
        }
        is MultiOption -> NukeNavRow(title = title, subtitle = subtitle, onClick = onOpen,
            statusText = stringResource(R.string.host_option_selected_count, value.asSet().size))
        is NumberOption -> NukeNavRow(title = title, subtitle = subtitle, onClick = onOpen, statusText = value.toString())
        is TextOption -> NukeNavRow(title = title, subtitle = subtitle, onClick = onOpen,
            statusText = (value as String).ifEmpty { stringResource(R.string.host_option_not_set) })
        is LinesOption -> NukeNavRow(title = title, subtitle = subtitle, onClick = onOpen,
            statusText = stringResource(R.string.host_option_entry_count, value.asList().size))
        is ConversationsOption, is FriendsOption -> NukeNavRow(title = title, subtitle = subtitle, onClick = onOpen,
            statusText = stringResource(R.string.host_option_selected_count, value.asList().size))
    }
}

// The dialog frame every options dialog shares: title, a scrolling body under a height cap, Cancel and Save.
@Composable
internal fun OptionsDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    saveEnabled: Boolean = true,
    hint: String? = stringResource(R.string.host_options_save_hint),
    content: @Composable () -> Unit,
) {
    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = title,
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f))
            NukeDialogAccentButton(stringResource(R.string.home_save), onSave, Modifier.weight(1f), enabled = saveEnabled)
        },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
            if (hint != null) OptionsBodyText(hint)
        }
    }
}

@Composable
private fun MultiEditor(
    featureId: String,
    option: MultiOption,
    title: String,
    initial: Set<String>,
    onDismiss: () -> Unit,
    saves: Boolean = false,
    onSave: (Set<String>) -> Unit,
) {
    val label = optionLabel(featureId, option.key)
    var picked by remember(option) { mutableStateOf(initial) }
    val hint = if (saves) stringResource(R.string.host_options_save_hint) else null
    OptionsDialog(title, onDismiss, { onSave(picked) }, saveEnabled = picked != initial, hint = hint) {
        OptionsBodyText(stringResource(R.string.host_option_selected_count, picked.size))
        label?.description?.let { OptionsBodyText(stringResource(it)) }
        NukeSettingGroup {
            for (id in option.choices) {
                fun select(checked: Boolean) { picked = if (checked) picked + id else picked - id }
                NukeSettingRow(
                    title = choiceTitle(label, id),
                    onClick = { select(id !in picked) },
                    independentTrailingContent = {
                        NukeCheckbox(checked = id in picked, onCheckedChange = ::select, modifier = Modifier.padding(end = 14.dp))
                    },
                )
            }
        }
    }
}

// A number is saved only when it reads and lies in range; otherwise the dialog says what it takes.
@Composable
private fun NumberEditor(featureId: String, option: NumberOption, initial: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var text by remember(option) { mutableStateOf(initial.toString()) }
    val parsed = text.toLongOrNull()?.takeIf { it in option.min..option.max }
    OptionsDialog(optionTitle(featureId, option), onDismiss, { parsed?.let(onSave) }, saveEnabled = parsed != null && parsed != initial, hint = null) {
        optionLabel(featureId, option.key)?.description?.let { OptionsBodyText(stringResource(it)) }
        NukeTextField(
            value = text,
            onValueChange = { typed -> text = typed.filter { it.isDigit() || it == '-' }.take(MAX_NUMBER_CHARS) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OptionsBodyText(stringResource(R.string.host_option_range, option.min, option.max), warning = parsed == null)
    }
}

@Composable
private fun TextEditor(featureId: String, option: TextOption, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(option) { mutableStateOf(initial) }
    OptionsDialog(optionTitle(featureId, option), onDismiss, { onSave(text) }, saveEnabled = text != initial, hint = null) {
        optionLabel(featureId, option.key)?.description?.let { OptionsBodyText(stringResource(it)) }
        NukeTextField(value = text, onValueChange = { text = it.take(option.maxLength) })
    }
}

@Composable
private fun LinesEditor(featureId: String, option: LinesOption, initial: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    var text by remember(option) { mutableStateOf(initial.joinToString("\n")) }
    val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    OptionsDialog(optionTitle(featureId, option), onDismiss, { onSave(lines) }, saveEnabled = lines != initial, hint = null) {
        OptionsBodyText(optionLabel(featureId, option.key)?.description?.let { stringResource(it) }
            ?: stringResource(R.string.host_option_lines_hint))
        NukeTextField(value = text, onValueChange = { text = it }, singleLine = false, minLines = 4, maxLines = 10)
    }
}

@Composable
internal fun OptionsBodyText(text: String, modifier: Modifier = Modifier, warning: Boolean = false) = BasicText(
    text,
    modifier = modifier,
    style = TextStyle(
        color = if (warning) NukeTheme.colors.danger else NukeTheme.colors.textSecondary,
        fontSize = 13.sp,
        lineHeight = 19.sp,
    ),
)

@Composable
private fun optionTitle(featureId: String, option: Option): String =
    optionLabel(featureId, option.key)?.let { stringResource(it.title) } ?: option.key

@Composable
private fun choiceTitle(label: OptionLabel?, id: String): String =
    label?.choices?.get(id)?.let { stringResource(it) } ?: id

private fun Any.asSet(): Set<String> = (this as Set<*>).filterIsInstance<String>().toSet()
private fun Any.asList(): List<String> = (this as List<*>).filterIsInstance<String>()

private const val MAX_NUMBER_CHARS = 18
