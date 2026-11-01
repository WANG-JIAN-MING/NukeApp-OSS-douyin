package io.github.nuke.oss.ui.hostsettings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.app.shared.settings.HostGroup
import io.github.nuke.oss.designsystem.NukeCheckbox
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeSelectRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTextField
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.nukeClick
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class FriendSort { DAYS, SELECTED }

private enum class ContactTab { FRIENDS, GROUPS }

private sealed interface ContactsState {
    data object Loading : ContactsState
    data class Ready(val contacts: HostContactList) : ContactsState
    data class Unavailable(val reason: String) : ContactsState
}

// Friends in the order NukeApp's friend selector shows them. A selection is conversation IDs, or user IDs when only
// friends are picked. A selected one in neither list, such as someone no longer a friend, stays listed under its ID
// so it can still be cleared.
internal fun friendChoices(
    contacts: HostContactList,
    selected: Set<String>,
    sort: FriendSort,
    query: String,
    byUid: Boolean = false,
): List<HostFriend> {
    val key: (HostFriend) -> String = if (byUid) HostFriend::uid else HostFriend::conversationId
    val known = if (byUid) contacts.friends.map { it.uid }.toSet()
        else contacts.friends.map { it.conversationId }.toSet() + contacts.groups.map { it.conversationId }
    val orphans = selected.filter { it !in known }.sorted().map { HostFriend(it, it, null, null, if (byUid) "" else it) }
    val byDays = compareByDescending<HostFriend> { it.streakDays ?: -1 }.thenBy { it.name }.thenBy { it.uid }
    val comparator = when (sort) {
        FriendSort.DAYS -> byDays
        FriendSort.SELECTED -> compareByDescending<HostFriend> { key(it) in selected }.then(byDays)
    }
    val term = query.trim()
    return (contacts.friends + orphans)
        .filter { term.isEmpty() || listOf(it.uid, it.name, it.conversationId).any { text -> text.contains(term, ignoreCase = true) } }
        .sortedWith(comparator)
}

internal fun groupChoices(groups: List<HostGroup>, query: String): List<HostGroup> {
    val term = query.trim()
    return groups.filter { term.isEmpty() || it.name.contains(term, ignoreCase = true) || it.conversationId.contains(term, ignoreCase = true) }
}

// Picks chats from the host account's friends and group chats, NukeApp's friend selector and group list in one
// dialog. The selection is conversation IDs, or with friendsOnly the friends' user IDs and no group list; the lists
// are read once when the dialog opens.
@Composable
internal fun ConversationPickerDialog(
    title: String,
    initial: Set<String>,
    load: suspend () -> HostContactList,
    onDismiss: () -> Unit,
    friendsOnly: Boolean = false,
    onSave: (Set<String>) -> Unit,
) {
    var state by remember { mutableStateOf<ContactsState>(ContactsState.Loading) }
    LaunchedEffect(Unit) {
        state = try {
            ContactsState.Ready(load())
        } catch (error: HostContactsUnavailable) {
            ContactsState.Unavailable(error.message.orEmpty())
        }
    }
    var selected by remember { mutableStateOf(initial) }
    var tab by remember { mutableStateOf(ContactTab.FRIENDS) }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(FriendSort.DAYS) }
    val avatars = remember { AvatarMemory() }
    val contacts = (state as? ContactsState.Ready)?.contacts ?: HostContactList(emptyList(), emptyList())
    fun select(id: String, checked: Boolean) { selected = if (checked) selected + id else selected - id }
    val tabLabels = mapOf(
        ContactTab.FRIENDS to stringResource(R.string.host_contacts_friends),
        ContactTab.GROUPS to stringResource(R.string.host_contacts_groups),
    )
    val sortLabels = mapOf(
        FriendSort.DAYS to stringResource(R.string.host_contacts_sort_days),
        FriendSort.SELECTED to stringResource(R.string.host_contacts_sort_selected),
    )

    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = title,
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f))
            NukeDialogAccentButton(stringResource(R.string.home_save), { onSave(selected) }, Modifier.weight(1f),
                enabled = selected != initial)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionsBodyText(stringResource(R.string.host_option_selected_count, selected.size))
            NukeSettingGroup {
                if (!friendsOnly) NukeSelectRow(
                    title = stringResource(R.string.host_contacts_list),
                    options = ContactTab.entries,
                    selectedOption = tab,
                    onOptionSelected = { tab = it },
                    optionToString = { tabLabels.getValue(it) },
                )
                if (tab == ContactTab.FRIENDS) {
                    NukeSelectRow(
                        title = stringResource(R.string.host_contacts_sort),
                        options = FriendSort.entries,
                        selectedOption = sort,
                        onOptionSelected = { sort = it },
                        optionToString = { sortLabels.getValue(it) },
                    )
                }
            }
            NukeTextField(query, { query = it.take(200) }, placeholder = stringResource(R.string.host_contacts_search))
            when (val current = state) {
                ContactsState.Loading -> OptionsBodyText(stringResource(R.string.host_contacts_loading))
                is ContactsState.Unavailable -> OptionsBodyText(stringResource(R.string.host_contacts_unavailable, current.reason), warning = true)
                is ContactsState.Ready -> Unit
            }
            val friends = remember(contacts, selected, sort, query) { friendChoices(contacts, selected, sort, query, byUid = friendsOnly) }
            val groups = remember(contacts, query) { groupChoices(contacts.groups, query) }
            val empty = if (tab == ContactTab.FRIENDS) friends.isEmpty() else groups.isEmpty()
            if (empty && state is ContactsState.Ready) {
                OptionsBodyText(stringResource(when {
                    query.isNotBlank() -> R.string.host_contacts_no_match
                    tab == ContactTab.FRIENDS -> R.string.host_contacts_friends_empty
                    else -> R.string.host_contacts_groups_empty
                }))
            }
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 400.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tab == ContactTab.FRIENDS) {
                    items(friends, key = { "friend:" + it.uid + ":" + it.conversationId }) { friend ->
                        val id = if (friendsOnly) friend.uid else friend.conversationId
                        NukeSettingGroup {
                            FriendRow(friend, id in selected, avatars) { select(id, it) }
                        }
                    }
                } else {
                    items(groups, key = { "group:" + it.conversationId }) { group ->
                        val checked = group.conversationId in selected
                        NukeSettingGroup {
                            NukeSettingRow(
                                title = group.name,
                                leadingIcon = { ContactAvatar(group.name, group.avatarUrl, avatars) },
                                onClick = { select(group.conversationId, !checked) },
                                trailingContent = { NukeCheckbox(checked, { select(group.conversationId, it) }) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FriendRow(friend: HostFriend, checked: Boolean, avatars: AvatarMemory, onCheckedChange: (Boolean) -> Unit) {
    val days = friend.streakDays
    Row(
        Modifier.fillMaxWidth().nukeClick { onCheckedChange(!checked) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(friend.name, friend.avatarUrl, avatars)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(friend.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = TextStyle(
                color = NukeTheme.colors.textPrimary, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold))
            SelectorText(friend.uid)
            BasicText(
                if (days == null) stringResource(R.string.host_contacts_days_unknown) else stringResource(R.string.host_contacts_days, days),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(
                    color = if (days != null && days > 0) NukeTheme.colors.accent else NukeTheme.colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                ),
            )
        }
        Spacer(Modifier.width(10.dp))
        NukeCheckbox(checked, onCheckedChange)
    }
}

@Composable
private fun SelectorText(text: String) = BasicText(text, maxLines = 2, overflow = TextOverflow.Ellipsis,
    style = TextStyle(color = NukeTheme.colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp))

// The first letter of the name on an accent disc, covered by the avatar once it has loaded.
@Composable
internal fun ContactAvatar(name: String, url: String?, avatars: AvatarMemory, size: Dp = 38.dp) {
    Box(
        Modifier.size(size).clip(CircleShape).background(NukeTheme.colors.accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty(),
            style = TextStyle(color = NukeTheme.colors.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold),
        )
        if (url != null) {
            val image by produceState(avatars[url], url) {
                if (value == null) {
                    withContext(Dispatchers.IO) { fetchAvatar(listOf(url)) }?.let {
                        avatars[url] = it
                        value = it
                    }
                }
            }
            image?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
        }
    }
}

// Decoded avatars of one open picker, so scrolling back does not download them again. Main thread only; the oldest
// entries go first once it holds MAX_AVATARS.
internal class AvatarMemory {
    private val images = object : LinkedHashMap<String, ImageBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > MAX_AVATARS
    }

    operator fun get(url: String): ImageBitmap? = images[url]
    operator fun set(url: String, image: ImageBitmap) { images[url] = image }
}

private const val MAX_AVATARS = 128
