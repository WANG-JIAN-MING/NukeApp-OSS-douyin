package io.github.nuke.oss.ui.hostsettings

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTextField
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val SHARE_GROUPS = "ShareFriendGroups"

// One group as the dialog edits it; picture is the file name of its custom picture, or null for the first friend's.
internal data class ShareGroupDraft(val key: String, val name: String, val uids: List<String>, val picture: String?)

// The feature's two options as groups: groups lines are name=uid,uid,… and avatars lines name=file. Lines that do not
// read are dropped here; the feature itself refuses a bad configuration when it installs.
internal fun shareGroupsOf(groups: List<String>, avatars: List<String>): List<ShareGroupDraft> {
    val pictures = avatars.mapNotNull { line ->
        val separator = line.lastIndexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        line.substring(0, separator).trim() to line.substring(separator + 1).trim()
    }.toMap()
    return groups.mapNotNull { line ->
        val separator = line.lastIndexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
        val name = line.substring(0, separator).trim()
        val uids = line.substring(separator + 1).split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        ShareGroupDraft(UUID.randomUUID().toString(), name, uids, pictures[name]?.takeIf(SHARE_PICTURE::matches))
    }
}

internal fun shareGroupLines(groups: List<ShareGroupDraft>): Pair<List<String>, List<String>> =
    groups.map { "${it.name}=${it.uids.joinToString(",")}" } to groups.mapNotNull { group -> group.picture?.let { "${group.name}=$it" } }

// Null when the group may be kept, otherwise the string that says why not: a name of 1 to 30 characters that no other
// group has, and 1 to 100 friends.
internal fun shareGroupProblem(group: ShareGroupDraft, others: List<ShareGroupDraft>): Int? = when {
    group.name.trim().length !in 1..MAX_NAME || '=' in group.name -> R.string.host_share_groups_invalid
    others.any { it.key != group.key && it.name == group.name.trim() } -> R.string.host_share_groups_invalid
    group.uids.isEmpty() -> R.string.host_share_groups_invalid
    group.uids.size > MAX_MEMBERS -> R.string.host_share_groups_limit
    else -> null
}

// NukeApp's share group settings: the list of groups with their pictures and sizes and a create row, and for one group
// its name, picture (custom or the first friend's) and friends, with delete. Edits stay on a draft until Save writes
// both options; pictures saved for groups that are gone are deleted then. The share panel reads the groups when Douyin
// starts.
@Composable
internal fun ShareGroupsDialog(
    feature: HostFeature,
    title: String,
    directory: File?,
    onSave: (Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
    loadContacts: suspend () -> HostContactList,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved = remember(feature.id) { feature.options.associate { it.option.key to it.saved } }
    val initial = remember(feature.id) { shareGroupsOf(lines(saved[GROUPS]), lines(saved[AVATARS])) }
    var groups by remember(feature.id) { mutableStateOf(initial) }
    var editing by remember { mutableStateOf<ShareGroupDraft?>(null) }
    var choosingFriends by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var friends by remember { mutableStateOf<List<HostFriend>>(emptyList()) }
    val avatars = remember { AvatarMemory() }
    LaunchedEffect(Unit) {
        // Names and avatars only decorate the list; without them it shows user IDs.
        friends = try {
            loadContacts().friends
        } catch (_: HostContactsUnavailable) {
            emptyList()
        }
    }
    val byUid = friends.associateBy { it.uid }
    val failedPicture = stringResource(R.string.host_share_groups_avatar_failed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = editing
        if (uri == null || target == null || directory == null) {
            importing = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val name = withContext(Dispatchers.IO) { importPicture(context.contentResolver, uri, directory) }
            importing = false
            if (name == null) problem = failedPicture
            else if (editing?.key == target.key) editing = editing?.copy(picture = name)
        }
    }

    @Composable
    fun Picture(group: ShareGroupDraft, size: Dp) {
        val first = group.uids.firstOrNull()?.let(byUid::get)
        GroupPicture(group.name, group.picture?.let { directory?.let { dir -> File(dir, it) } }, first?.avatarUrl, avatars, size)
    }

    val group = editing
    when {
        group != null && choosingFriends -> ConversationPickerDialog(
            title = stringResource(R.string.host_share_groups_friends_title),
            initial = group.uids.toSet(),
            load = loadContacts,
            onDismiss = { choosingFriends = false },
            friendsOnly = true,
        ) { picked ->
            // Keep the friends already in the group in their order, so the first friend's picture stays.
            editing = group.copy(uids = group.uids.filter { it in picked } + picked.filter { it !in group.uids }.sorted())
            choosingFriends = false
        }
        group != null -> NukeDialog(
            visible = true,
            onDismissRequest = { if (!importing) editing = null },
            title = stringResource(R.string.host_share_groups_edit),
            modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            actions = {
                NukeDialogTextButton(stringResource(R.string.host_streak_back), { editing = null; problem = null }, Modifier.weight(1f), enabled = !importing)
                NukeDialogAccentButton(stringResource(R.string.host_share_groups_done), {
                    val reason = shareGroupProblem(group, groups)
                    if (reason != null) {
                        problem = context.getString(reason)
                    } else {
                        val clean = group.copy(name = group.name.trim())
                        groups = if (groups.any { it.key == clean.key }) groups.map { if (it.key == clean.key) clean else it } else groups + clean
                        editing = null
                        problem = null
                    }
                }, Modifier.weight(1f), enabled = !importing)
            },
        ) {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                problem?.let { OptionsBodyText(it, warning = true) }
                NukeTextField(group.name, { editing = group.copy(name = it.take(MAX_NAME)) }, placeholder = stringResource(R.string.host_share_groups_name))
                NukeSettingGroup {
                    NukeSettingRow(
                        title = stringResource(R.string.host_share_groups_avatar),
                        subtitle = stringResource(if (group.picture == null) R.string.host_share_groups_avatar_default else R.string.host_share_groups_avatar_custom),
                        leadingIcon = { Picture(group, 42.dp) },
                        enabled = !importing && directory != null,
                        onClick = {
                            importing = true
                            picker.launch(arrayOf("image/*"))
                        },
                    )
                    if (group.picture != null) {
                        NukeDivider()
                        NukeSettingRow(title = stringResource(R.string.host_share_groups_avatar_reset), enabled = !importing,
                            onClick = { editing = group.copy(picture = null) })
                    }
                }
                if (importing) OptionsBodyText(stringResource(R.string.host_share_groups_avatar_loading))
                NukeSettingGroup {
                    NukeNavRow(title = stringResource(R.string.host_share_groups_selected, group.uids.size), onClick = { choosingFriends = true }, enabled = !importing)
                    if (group.uids.isNotEmpty()) {
                        Row(
                            Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            group.uids.take(PREVIEW_AVATARS).forEach { uid ->
                                val friend = byUid[uid]
                                ContactAvatar(friend?.name ?: uid, friend?.avatarUrl, avatars, size = 32.dp)
                            }
                            if (group.uids.size > PREVIEW_AVATARS) OptionsBodyText("+${group.uids.size - PREVIEW_AVATARS}")
                        }
                    }
                }
                if (groups.any { it.key == group.key }) {
                    NukeDialogTextButton(stringResource(R.string.host_share_groups_delete), {
                        groups = groups.filterNot { it.key == group.key }
                        editing = null
                        problem = null
                    }, enabled = !importing)
                }
            }
        }
        else -> OptionsDialog(
            title = title,
            onDismiss = onDismiss,
            saveEnabled = groups != initial,
            onSave = {
                val (groupLines, avatarLines) = shareGroupLines(groups)
                onSave(mapOf(GROUPS to groupLines, AVATARS to avatarLines).filter { (key, value) -> lines(saved[key]) != value })
                directory?.let { dir -> scope.launch(Dispatchers.IO) { deleteUnused(dir, groups.mapNotNull { it.picture }.toSet()) } }
                onDismiss()
            },
        ) {
            OptionsBodyText(stringResource(R.string.host_share_groups_hint))
            NukeSettingGroup {
                groups.forEachIndexed { index, item ->
                    if (index > 0) NukeDivider()
                    NukeSettingRow(
                        title = item.name,
                        subtitle = stringResource(R.string.host_share_groups_members, item.uids.size),
                        leadingIcon = { Picture(item, 38.dp) },
                        onClick = { editing = item; problem = null },
                    )
                }
                if (groups.isNotEmpty()) NukeDivider()
                NukeSettingRow(
                    title = stringResource(R.string.host_share_groups_create),
                    enabled = groups.size < MAX_GROUPS,
                    onClick = { editing = ShareGroupDraft(UUID.randomUUID().toString(), "", emptyList(), null); problem = null },
                )
            }
        }
    }
}

// A custom picture from the feature's directory, or the first friend's avatar, or the name's first letter.
@Composable
private fun GroupPicture(name: String, file: File?, url: String?, avatars: AvatarMemory, size: Dp) {
    if (file == null) return ContactAvatar(name, url, avatars, size)
    val image by produceState<ImageBitmap?>(null, file) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }
    }
    Box(Modifier.size(size).clip(CircleShape)) {
        val shown = image
        if (shown != null) Image(shown, null, Modifier.size(size), contentScale = ContentScale.Crop)
        else ContactAvatar(name, url, avatars, size)
    }
}

// Copies the picked image into the feature's directory as a centre-cropped PNG of PICTURE_SIZE pixels under a random
// name, the way NukeApp stored group pictures, and returns the name; null when it cannot be read or written. The
// source is read once, at most MAX_PICTURE_BYTES.
private fun importPicture(resolver: ContentResolver, uri: Uri, directory: File): String? {
    val bytes = try {
        resolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (out.size() + read > MAX_PICTURE_BYTES) return null
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    } ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= PICTURE_SIZE && bounds.outHeight / (sample * 2) >= PICTURE_SIZE) sample *= 2
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val side = minOf(decoded.width, decoded.height)
    val square = Bitmap.createBitmap(decoded, (decoded.width - side) / 2, (decoded.height - side) / 2, side, side)
    val scaled = Bitmap.createScaledBitmap(square, PICTURE_SIZE, PICTURE_SIZE, true)
    if (!directory.isDirectory && !directory.mkdirs()) return null
    val name = "${UUID.randomUUID()}.png"
    val target = File(directory, name)
    val temporary = File(directory, "$name.tmp")
    return try {
        FileOutputStream(temporary).use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) { "PNG encoding failed" } }
        check(temporary.renameTo(target)) { "Cannot save the picture" }
        name
    } catch (_: IOException) {
        null
    } catch (_: IllegalStateException) {
        null
    } finally {
        temporary.delete()
    }
}

// Removes the saved pictures no group uses any more; other files in the directory are left alone.
private fun deleteUnused(directory: File, used: Set<String>) {
    directory.listFiles()?.forEach { file -> if (SHARE_PICTURE.matches(file.name) && file.name !in used) file.delete() }
}

private fun lines(value: Any?): List<String> = (value as? List<*>)?.filterIsInstance<String>().orEmpty()

// The feature's option keys; keep in step with ShareFriendGroups in the TikTok host.
private const val GROUPS = "groups"
private const val AVATARS = "avatars"
// Keep in step with the feature's own check of avatar lines.
private val SHARE_PICTURE = Regex("[a-f0-9-]{36}\\.png")
private const val MAX_GROUPS = 20
private const val MAX_NAME = 30
private const val MAX_MEMBERS = 100
private const val PREVIEW_AVATARS = 5
private const val PICTURE_SIZE = 256
private const val MAX_PICTURE_BYTES = 10 * 1024 * 1024
