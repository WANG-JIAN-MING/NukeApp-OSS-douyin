package io.github.nuke.oss.ui.prompts

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.InfoField
import io.github.nuke.oss.app.shared.activity.InfoSheet
import io.github.nuke.oss.designsystem.NukeBottomSheet
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogBodyText
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTextField
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.nukeClick
import kotlinx.coroutines.launch

// NukeApp's post details sheet: title and close on top, search, the sections with rows that copy on a tap and expand
// on a long press, the last message, then copy-all and save. Only the list scrolls. Messages show in the sheet rather
// than as toasts, so a long path or reason stays readable.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun InfoSheetContent(
    sheet: InfoSheet,
    onSave: (text: String, done: (message: Int, argument: Any) -> Unit) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val shown = remember(query) { sheet.search(query) }
    val copied = stringResource(R.string.host_sheet_copied)
    val tooLarge = stringResource(R.string.host_sheet_copy_large)
    fun copy(text: String) {
        if (text.length > CLIPBOARD_LIMIT) {
            message = tooLarge
            return
        }
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(sheet.title, text))
        message = copied
    }
    fun save() {
        if (saving) return
        saving = true
        // done may come from a worker thread; the state is changed back on the main thread.
        onSave(sheet.fullText) { result, argument ->
            scope.launch {
                message = context.getString(result, argument)
                saving = false
            }
        }
    }
    NukeBottomSheet(
        onDismissRequest = onClose,
        contentModifier = Modifier.fillMaxHeight(0.5f),
        sheetState = state,
        sheetGesturesEnabled = false,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    sheet.title,
                    Modifier.weight(1f),
                    style = TextStyle(color = NukeTheme.colors.textPrimary, fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
                )
                NukeDialogTextButton(stringResource(R.string.host_sheet_close), {
                    scope.launch {
                        state.hide()
                        onClose()
                    }
                })
            }
            if (sheet.sections.isNotEmpty()) {
                NukeTextField(query, { query = it.take(MAX_QUERY) }, placeholder = sheet.searchHint)
                Spacer(Modifier.height(12.dp))
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sheet.sections.isEmpty()) {
                    item { NukeDialogBodyText(sheet.empty) }
                } else {
                    item { SheetText(sheet.hint) }
                    items(shown) { section ->
                        NukeSettingGroup(title = section.title) {
                            section.fields.forEach { field -> InfoRow(field) { copy(field.value) } }
                        }
                    }
                    if (shown.isEmpty()) item { NukeDialogBodyText(sheet.noMatch) }
                }
                sheet.footer?.let { footer -> item { NukeDialogBodyText(footer) } }
            }
            if (message.isNotEmpty()) {
                BasicText(
                    message,
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NukeDialogTextButton(stringResource(R.string.host_sheet_copy_all), { copy(sheet.fullText) }, Modifier.weight(1f),
                    enabled = sheet.fullText.isNotEmpty())
                NukeDialogAccentButton(
                    stringResource(if (saving) R.string.host_sheet_saving else R.string.host_sheet_save),
                    ::save, Modifier.weight(1f), enabled = sheet.fullText.isNotEmpty() && !saving,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(field: InfoField, copy: () -> Unit) {
    var expanded by remember(field) { mutableStateOf(false) }
    NukeSettingRow(
        title = field.label,
        modifier = Modifier.nukeClick(onLongClick = { expanded = !expanded }, onClick = copy),
        supportingContent = {
            BasicText(
                field.value,
                maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp),
            )
        },
    )
}

@Composable
private fun SheetText(text: String) = BasicText(text, style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 18.sp))

// NukeApp's limit: past it the clipboard is not used and the user is asked to save a file instead.
private const val CLIPBOARD_LIMIT = 32_000
private const val MAX_QUERY = 200
private const val COLLAPSED_LINES = 4

