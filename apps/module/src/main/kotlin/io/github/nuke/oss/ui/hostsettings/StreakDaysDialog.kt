package io.github.nuke.oss.ui.hostsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTextField

// The feature's ID and option key, named here as strings so this screen links no host class; keep in step with
// CustomStreakDays in the TikTok host.
internal const val STREAK_DAYS = "CustomStreakDays"
private const val DAYS = "days"
private const val EVERY_CHAT = "*"
private const val MAX_DIGITS = 5

// The numbers as the dialog edits them, kept as the text in the boxes: one for every chat, and one per conversation ID.
// A box that is empty means the real count is shown.
internal data class StreakDaysDraft(val all: String, val chats: Map<String, String>)

// The stored lines (conversationId=days, and *=days for every chat) as a draft. A line that does not read is left out,
// the way the settings screen shows only what the feature could use.
internal fun streakDaysOf(lines: List<String>): StreakDaysDraft {
    var all = ""
    val chats = LinkedHashMap<String, String>()
    for (line in lines) {
        val separator = line.indexOf('=').takeIf { it > 0 } ?: continue
        val id = line.substring(0, separator).trim()
        val days = line.substring(separator + 1).trim().toIntOrNull()?.takeIf { it in 0..99_999 }?.toString() ?: continue
        if (id == EVERY_CHAT) all = days else chats[id] = days
    }
    return StreakDaysDraft(all, chats)
}

// The lines to store: the every-chat number first, then one per chat that has a number, with leading zeros dropped.
internal fun streakDayLines(draft: StreakDaysDraft): List<String> =
    listOfNotNull(draft.all.toIntOrNull()?.let { "$EVERY_CHAT=$it" }) +
        draft.chats.mapNotNull { (id, days) -> days.toIntOrNull()?.let { "$id=$it" } }

internal fun streakDigits(text: String): String = text.filter { it in '0'..'9' }.take(MAX_DIGITS)

// Friends with a number of their own first, then by the real streak, as NukeApp's friend list does, narrowed by a search.
internal fun streakFriends(friends: List<HostFriend>, draft: StreakDaysDraft, query: String): List<HostFriend> {
    val term = query.trim()
    return friends
        .filter { term.isEmpty() || listOf(it.name, it.uid, it.conversationId).any { text -> text.contains(term, ignoreCase = true) } }
        .sortedWith(
            compareByDescending<HostFriend> { draft.chats[it.conversationId]?.isNotEmpty() == true }
                .thenByDescending { it.streakDays ?: -1 }.thenBy { it.name }.thenBy { it.uid },
        )
}

// Custom streak days, laid out like NukeApp's: one box for every chat, then the friends with their avatars and real
// streak, each with a box for the number to show. Nothing is typed by hand as an ID. Edits stay on a draft until Save,
// and the badge changes after Douyin restarts.
@Composable
internal fun StreakDaysDialog(
    feature: HostFeature,
    title: String,
    onSave: (Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
    loadContacts: suspend () -> HostContactList,
) {
    val stored = remember(feature.id) {
        (feature.options.firstOrNull { it.option.key == DAYS }?.saved as? List<*>)?.filterIsInstance<String>().orEmpty()
    }
    val initial = remember(feature.id) { streakDaysOf(stored) }
    var draft by remember(feature.id) { mutableStateOf(initial) }
    var contacts by remember { mutableStateOf<HostContactList?>(null) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    val avatars = remember { AvatarMemory() }
    LaunchedEffect(Unit) {
        try {
            contacts = loadContacts()
        } catch (error: HostContactsUnavailable) {
            unavailable = error.message.orEmpty()
        }
    }
    val friends = contacts?.friends.orEmpty()
    val shown = remember(friends, draft, query) { streakFriends(friends, draft, query) }
    // Numbers for chats that are not in the friend list, such as someone who is no longer a friend, stay listed under
    // their ID so they can still be cleared.
    val known = remember(friends) { friends.map { it.conversationId }.toSet() }
    val others = remember(draft, known, query) {
        draft.chats.keys.filter { it !in known && (query.isBlank() || it.contains(query.trim(), ignoreCase = true)) }
    }
    val lines = streakDayLines(draft)

    NukeDialog(
        visible = true,
        onDismissRequest = onDismiss,
        title = title,
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = {
            NukeDialogTextButton(stringResource(R.string.home_cancel), onDismiss, Modifier.weight(1f))
            NukeDialogAccentButton(
                stringResource(R.string.home_save), { onSave(mapOf(DAYS to lines)) }, Modifier.weight(1f),
                enabled = lines != stored,
            )
        },
    ) {
        // The body takes only the height the buttons leave, so the list shrinks on a short screen instead of pushing
        // the buttons out of the window.
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionsBodyText(stringResource(R.string.host_streak_days_hint))
            NukeSettingGroup {
                NukeSettingRow(
                    title = stringResource(R.string.host_streak_days_all),
                    subtitle = stringResource(R.string.host_streak_days_all_hint),
                    trailingContent = { DaysBox(draft.all) { draft = draft.copy(all = it) } },
                )
            }
            NukeTextField(query, { query = it.take(200) }, placeholder = stringResource(R.string.host_contacts_search))
            OptionsBodyText(stringResource(R.string.host_streak_days_set_count, draft.chats.size))
            when {
                unavailable != null -> OptionsBodyText(stringResource(R.string.host_contacts_unavailable, unavailable.orEmpty()), warning = true)
                contacts == null -> OptionsBodyText(stringResource(R.string.host_contacts_loading))
                shown.isEmpty() && others.isEmpty() ->
                    OptionsBodyText(stringResource(if (query.isBlank()) R.string.host_contacts_friends_empty else R.string.host_contacts_no_match))
            }
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 400.dp),
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(shown, key = { "friend:" + it.uid + ":" + it.conversationId }) { friend ->
                    NukeSettingGroup {
                        NukeSettingRow(
                            title = friend.name,
                            subtitle = friend.streakDays?.let { stringResource(R.string.host_streak_days_real, it) }
                                ?: stringResource(R.string.host_streak_days_none),
                            leadingIcon = { ContactAvatar(friend.name, friend.avatarUrl, avatars) },
                            trailingContent = {
                                DaysBox(draft.chats[friend.conversationId].orEmpty()) { draft = draft.withChat(friend.conversationId, it) }
                            },
                        )
                    }
                }
                items(others, key = { "other:$it" }) { id ->
                    NukeSettingGroup {
                        NukeSettingRow(
                            title = id,
                            subtitle = stringResource(R.string.host_streak_days_other),
                            trailingContent = { DaysBox(draft.chats[id].orEmpty()) { draft = draft.withChat(id, it) } },
                        )
                    }
                }
            }
        }
    }
}

private fun StreakDaysDraft.withChat(id: String, text: String): StreakDaysDraft =
    copy(chats = LinkedHashMap(chats).apply { if (text.isEmpty()) remove(id) else put(id, text) })

// The number box of a row: digits only, five at most, empty for "show the real count".
@Composable
private fun DaysBox(value: String, onChange: (String) -> Unit) = NukeTextField(
    value = value,
    onValueChange = { onChange(streakDigits(it)) },
    modifier = Modifier.width(96.dp),
    placeholder = stringResource(R.string.host_streak_days_placeholder),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
)
