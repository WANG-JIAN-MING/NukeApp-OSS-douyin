package io.github.nuke.oss.ui.hostsettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.ConsoleReply
import io.github.nuke.oss.app.shared.settings.ProtocolConsole
import io.github.nuke.oss.app.shared.settings.ProtocolInputException
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogBodyText
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeSegmentedControl
import io.github.nuke.oss.designsystem.NukeTextField
import io.github.nuke.oss.designsystem.NukeTheme
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val PROTOCOL_SENDER = "CustomProtocolSender"

internal enum class PacketTextEncoding { HEX, BASE64 }

// The text field's bytes. Decoding keeps every byte as written: it is independent of PB decoding.
internal object PacketText {
    const val MAX_EDITOR_CHARS = 65_536
    const val MAX_BODY_BYTES = 4 * 1024 * 1024
    private const val MAX_CLIPBOARD_CHARS = MAX_BODY_BYTES * 3

    // Throws PacketTextException naming the problem.
    fun decode(text: String, encoding: PacketTextEncoding): ByteArray {
        if (text.length > MAX_CLIPBOARD_CHARS) throw PacketTextException(PacketTextProblem.SIZE)
        val compact = text.filterNot(Char::isWhitespace)
        val bytes = when (encoding) {
            PacketTextEncoding.HEX -> {
                if (compact.length % 2 != 0 || compact.any { it.digitToIntOrNull(16) == null }) throw PacketTextException(PacketTextProblem.HEX)
                if (compact.length / 2 > MAX_BODY_BYTES) throw PacketTextException(PacketTextProblem.SIZE)
                ByteArray(compact.length / 2) { index -> ((compact[index * 2].digitToInt(16) shl 4) or compact[index * 2 + 1].digitToInt(16)).toByte() }
            }
            PacketTextEncoding.BASE64 -> try {
                Base64.getDecoder().decode(compact)
            } catch (_: IllegalArgumentException) {
                throw PacketTextException(PacketTextProblem.BASE64)
            }
        }
        if (bytes.size > MAX_BODY_BYTES) throw PacketTextException(PacketTextProblem.SIZE)
        return bytes
    }

    fun hex(bytes: ByteArray, limit: Int = bytes.size): String {
        val digits = "0123456789abcdef"
        val count = minOf(bytes.size, limit)
        return buildString(count * 2) {
            for (index in 0 until count) {
                val value = bytes[index].toInt() and 0xff
                append(digits[value ushr 4])
                append(digits[value and 15])
            }
        }
    }
}

internal enum class PacketTextProblem { COMMAND, INBOX, TAG, HEX, BASE64, SIZE }

internal class PacketTextException(val problem: PacketTextProblem) : IllegalArgumentException(problem.name)

private data class PacketDraft(val revision: Long, val cmd: String, val inbox: String, val bodyTag: String?, val encoding: PacketTextEncoding, val text: String, val imported: ByteArray?)
private class PacketPreview(val revision: Long, val cmd: Int, val inbox: Int, val body: ByteArray, val hash: String, val hex: String)
private class PacketReply(val bytes: ByteArray, val hex: String, val status: Long?, val error: String?)

// NukeApp's custom protocol sender: cmd and inboxType, a complete RequestBody or a business PB under an explicit
// bodyTag, Hex or Base64 text (or a large PB loaded from the clipboard), a live preview of what would be sent, and the
// response with copy and save. Only Send submits, once per press; closing stops the local wait and cannot undo a
// request already handed to the host.
@Composable
internal fun ProtocolSenderDialog(console: ProtocolConsole, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    var cmd by remember { mutableStateOf("") }
    var inbox by remember { mutableStateOf("1") }
    var bodyTag by remember { mutableStateOf("") }
    var mode by remember { mutableIntStateOf(0) }
    var encoding by remember { mutableStateOf(PacketTextEncoding.HEX) }
    var input by remember { mutableStateOf("") }
    var imported by remember { mutableStateOf<ByteArray?>(null) }
    var revision by remember { mutableLongStateOf(0L) }
    var preview by remember { mutableStateOf<PacketPreview?>(null) }
    var invalid by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<PacketReply?>(null) }

    fun errorText(error: Exception): String = when (error) {
        is PacketTextException -> context.getString(when (error.problem) {
            PacketTextProblem.COMMAND -> R.string.host_packet_invalid_cmd
            PacketTextProblem.INBOX -> R.string.host_packet_invalid_inbox
            PacketTextProblem.TAG -> R.string.host_packet_invalid_tag
            PacketTextProblem.HEX -> R.string.host_packet_invalid_hex
            PacketTextProblem.BASE64 -> R.string.host_packet_invalid_base64
            PacketTextProblem.SIZE -> R.string.host_packet_invalid_size
        })
        else -> error.message ?: error.javaClass.simpleName
    }

    LaunchedEffect(Unit) {
        account = withContext(Dispatchers.IO) { console.account() }
        if (account == null) notice = context.getString(R.string.host_packet_no_account)
    }
    val draft = PacketDraft(revision, cmd, inbox, bodyTag.takeIf { mode == 1 }, encoding, input, imported)
    LaunchedEffect(draft) {
        preview = null
        invalid = null
        delay(PREVIEW_DELAY_MILLIS)
        try {
            val prepared = withContext(Dispatchers.Default) {
                val command = draft.cmd.trim().toIntOrNull()?.takeIf { it > 0 } ?: throw PacketTextException(PacketTextProblem.COMMAND)
                val inboxType = draft.inbox.trim().toIntOrNull()?.takeIf { it >= 0 } ?: throw PacketTextException(PacketTextProblem.INBOX)
                val tag = draft.bodyTag?.let { it.trim().toIntOrNull()?.takeIf { value -> value in 1..MAX_TAG } ?: throw PacketTextException(PacketTextProblem.TAG) }
                val bytes = draft.imported ?: PacketText.decode(draft.text, draft.encoding)
                val body = console.requestBody(command, inboxType, tag, bytes)
                PacketPreview(draft.revision, command, inboxType, body,
                    PacketText.hex(MessageDigest.getInstance("SHA-256").digest(body)), PacketText.hex(body, PREVIEW_BYTES))
            }
            if (prepared.revision == revision) preview = prepared
        } catch (error: IllegalArgumentException) {
            if (draft.revision == revision) invalid = errorText(error)
        } catch (error: IllegalStateException) {
            if (draft.revision == revision) invalid = errorText(error)
        }
    }

    fun edit(action: () -> Unit) {
        if (busy) return
        revision++
        preview = null
        notice = null
        reply = null
        action()
    }
    fun copy(text: String, label: String) {
        val board = clipboard ?: return
        board.setPrimaryClip(ClipData.newPlainText(label, text))
        notice = context.getString(R.string.host_packet_copied)
    }
    fun send() {
        val prepared = preview ?: return
        val owner = account ?: return
        if (busy || prepared.revision != revision) return
        if (!console.running()) {
            notice = context.getString(R.string.host_packet_not_ready)
            return
        }
        busy = true
        reply = null
        notice = null
        scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { console.send(owner, prepared.cmd, prepared.inbox, prepared.body) }) {
                    is ConsoleReply.Response -> reply = withContext(Dispatchers.Default) {
                        PacketReply(result.bytes, PacketText.hex(result.bytes, RESPONSE_PREVIEW_BYTES), result.status, result.error)
                    }
                    is ConsoleReply.Failed -> notice = "${result.code}: ${result.message}" + (result.hostCode?.let { " (hostCode=$it)" } ?: "")
                }
            } catch (error: IllegalStateException) {
                notice = errorText(error)
            } finally {
                busy = false
            }
        }
    }
    fun paste() {
        if (busy) return
        val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (text == null) {
            notice = context.getString(R.string.host_packet_empty_clipboard)
            return
        }
        val pasteEncoding = encoding
        busy = true
        revision++
        preview = null
        scope.launch {
            try {
                imported = withContext(Dispatchers.Default) { PacketText.decode(text, pasteEncoding) }
                input = ""
                revision++
                reply = null
                notice = null
            } catch (error: PacketTextException) {
                notice = errorText(error)
            } finally {
                busy = false
            }
        }
    }
    fun save(bytes: ByteArray) {
        busy = true
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { saveResponse(context, bytes) }
                notice = context.getString(R.string.host_packet_saved, path)
            } catch (error: IOException) {
                notice = errorText(error)
            } catch (error: IllegalStateException) {
                notice = errorText(error)
            } finally {
                busy = false
            }
        }
    }

    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.host_feature_protocol_sender),
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 600.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogTextButton(stringResource(R.string.host_packet_close), onDismiss, Modifier.weight(1f))
            NukeDialogAccentButton(
                stringResource(if (busy) R.string.host_packet_waiting else R.string.host_packet_send),
                ::send, Modifier.weight(1f), enabled = !busy && preview?.revision == revision && account != null,
            )
        },
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            NukeDialogBodyText(stringResource(R.string.host_packet_hint))
            account?.let { NukeDialogBodyText(stringResource(R.string.host_packet_account, it.toString())) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NukeTextField(cmd, { value -> edit { cmd = value.take(12) } }, Modifier.weight(1f), placeholder = "cmd", enabled = !busy)
                NukeTextField(inbox, { value -> edit { inbox = value.take(12) } }, Modifier.weight(1f), placeholder = "inboxType", enabled = !busy)
            }
            NukeSegmentedControl(mode, listOf("RequestBody", stringResource(R.string.host_packet_business_pb)), { value -> edit { mode = value } })
            if (mode == 1) {
                NukeTextField(bodyTag, { value -> edit { bodyTag = value.take(10) } }, placeholder = stringResource(R.string.host_packet_body_tag), enabled = !busy)
            }
            NukeSegmentedControl(encoding.ordinal, listOf("Hex", "Base64"), { value -> edit { encoding = PacketTextEncoding.entries[value] } })
            val loaded = imported
            if (loaded == null) {
                val tooLarge = stringResource(R.string.host_packet_large_input)
                NukeTextField(
                    input,
                    { value -> edit { if (value.length <= PacketText.MAX_EDITOR_CHARS) input = value else notice = tooLarge } },
                    placeholder = stringResource(R.string.host_packet_payload),
                    singleLine = false, minLines = 3, maxLines = 6, enabled = !busy,
                )
            } else {
                NukeDialogBodyText(stringResource(R.string.host_packet_imported, loaded.size))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NukeDialogTextButton(stringResource(R.string.host_packet_paste), ::paste, Modifier.weight(1f), enabled = !busy)
                NukeDialogTextButton(stringResource(R.string.host_packet_clear), { edit { input = ""; imported = null } }, Modifier.weight(1f), enabled = !busy)
            }
            preview?.let { shown ->
                NukeDialogBodyText(stringResource(R.string.host_packet_preview, shown.body.size))
                SelectionContainer { PacketCode("SHA-256: ${shown.hash}\n${shown.hex}") }
            }
            invalid?.let { NukeDialogBodyText(it) }
            notice?.let { SelectionContainer { NukeDialogBodyText(it) } }
            if (busy) NukeDialogBodyText(stringResource(R.string.host_packet_pending_hint))
            reply?.let { result ->
                NukeDialogBodyText(stringResource(R.string.host_packet_response, result.bytes.size, result.status?.toString() ?: "?"))
                if (!result.error.isNullOrEmpty()) NukeDialogBodyText(result.error)
                SelectionContainer { PacketCode(result.hex) }
                NukeDialogBodyText(stringResource(R.string.host_packet_response_hint))
                val copyable = result.bytes.size <= MAX_COPY_BYTES
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NukeDialogTextButton(stringResource(R.string.host_packet_copy_hex), { copy(PacketText.hex(result.bytes), "Response PB Hex") },
                        Modifier.weight(1f), enabled = !busy && copyable)
                    NukeDialogTextButton(stringResource(R.string.host_packet_copy_base64), { copy(Base64.getEncoder().encodeToString(result.bytes), "Response PB Base64") },
                        Modifier.weight(1f), enabled = !busy && copyable)
                }
                NukeDialogTextButton(stringResource(R.string.host_packet_save_response), { save(result.bytes) }, enabled = !busy)
            }
        }
    }
}

@Composable
private fun PacketCode(text: String) = BasicText(
    text,
    Modifier.fillMaxWidth(),
    style = TextStyle(color = NukeTheme.colors.textSecondary, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
)

// Writes the full response to Downloads/Nuke (the app's own Downloads directory before Android 10) and returns where.
// A half-written file is removed.
private fun saveResponse(context: Context, bytes: ByteArray): String {
    val name = "Nuke-IM-Response-${System.currentTimeMillis()}.pb"
    fun write(stream: OutputStream) = stream.use { it.write(bytes) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        val directory = File(checkNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)) { "No downloads directory" }, "Nuke")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the response directory" }
        val file = File(directory, name)
        try {
            write(file.outputStream())
        } catch (error: IOException) {
            file.delete()
            throw error
        }
        return file.absolutePath
    }
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Nuke")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Cannot create the response file in Downloads" }
    try {
        write(checkNotNull(resolver.openOutputStream(uri)) { "Cannot open the response file" })
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) > 0) { "Cannot finish the response file" }
    } catch (error: IOException) {
        resolver.delete(uri, null, null)
        throw error
    } catch (error: IllegalStateException) {
        resolver.delete(uri, null, null)
        throw error
    }
    return "${Environment.DIRECTORY_DOWNLOADS}/Nuke/$name"
}

private const val PREVIEW_DELAY_MILLIS = 180L
private const val PREVIEW_BYTES = 256
private const val RESPONSE_PREVIEW_BYTES = 4096
private const val MAX_COPY_BYTES = 65_536
private const val MAX_TAG = 0x1fffffff

