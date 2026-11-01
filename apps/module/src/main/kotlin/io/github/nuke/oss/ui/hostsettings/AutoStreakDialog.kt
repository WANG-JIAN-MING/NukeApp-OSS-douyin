package io.github.nuke.oss.ui.hostsettings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.ActivityRecord
import io.github.nuke.oss.app.shared.settings.ActivityStatus
import io.github.nuke.oss.app.shared.settings.HostAccount
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostContactsUnavailable
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.HostFriend
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import io.github.nuke.oss.designsystem.NukeNavRow
import io.github.nuke.oss.designsystem.NukeSelectRow
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTextField
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val AUTO_STREAK = "AutoStreak"

// Auto streak's settings, laid out like NukeApp's auto streak dialog: the account, the friends with their avatars, the
// daily time, the message kind with the fields that kind uses, and the send history. It edits the feature's options
// on a draft and saves them together; the running feature reads them when the host starts. The option keys are the
// feature's own (AutoStreak in the TikTok host), named here as strings so this screen links no host class.
@Composable
internal fun AutoStreakDialog(
    feature: HostFeature,
    title: String,
    account: HostAccount,
    onSave: (Map<String, Any>) -> Unit,
    onDismiss: () -> Unit,
    loadContacts: suspend () -> HostContactList,
    loadHistory: suspend () -> List<ActivityRecord>,
) {
    val initial = remember(feature.id) { feature.options.associate { it.option.key to it.saved } }
    var draft by remember(feature.id) { mutableStateOf(initial) }
    var page by remember(feature.id) { mutableStateOf(StreakPage.MAIN) }
    var friends by remember { mutableStateOf<List<HostFriend>?>(null) }
    val avatars = remember { AvatarMemory() }
    LaunchedEffect(Unit) {
        // Names and avatars only decorate the dialog; without them it shows user IDs.
        friends = try {
            loadContacts().friends
        } catch (_: HostContactsUnavailable) {
            emptyList()
        }
    }
    fun set(key: String, value: Any) { draft = draft + (key to value) }
    val selected = (draft.getValue(FRIENDS) as List<*>).filterIsInstance<String>()
    val mode = draft.getValue(MODE) as String
    val text = draft.getValue(TEXT) as String
    val url = draft.getValue(URL) as String
    val hour = (draft.getValue(HOUR) as Long).toInt()
    val minute = (draft.getValue(MINUTE) as Long).toInt()

    when (page) {
        StreakPage.FRIENDS -> ConversationPickerDialog(
            title = stringResource(R.string.host_streak_friends_title),
            initial = selected.toSet(),
            load = loadContacts,
            onDismiss = { page = StreakPage.MAIN },
            friendsOnly = true,
        ) {
            set(FRIENDS, it.sorted())
            page = StreakPage.MAIN
        }
        StreakPage.TIME -> TimeDialog(hour, minute, onDismiss = { page = StreakPage.MAIN }) { h, m ->
            set(HOUR, h.toLong())
            set(MINUTE, m.toLong())
            page = StreakPage.MAIN
        }
        StreakPage.HISTORY -> HistoryDialog(friends.orEmpty(), avatars, loadHistory) { page = StreakPage.MAIN }
        StreakPage.MAIN -> {
            val problem = when {
                selected.isEmpty() -> R.string.host_streak_need_friends
                selected.size > MAX_FRIENDS -> R.string.host_streak_limit
                mode != INTERACTIVE && text.isBlank() -> R.string.host_streak_need_text
                mode == HITOKOTO && !url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true) -> R.string.host_streak_bad_url
                else -> null
            }
            NukeDialog(
                visible = true,
                onDismissRequest = onDismiss,
                title = title,
                modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
                properties = DialogProperties(usePlatformDefaultWidth = false),
                actions = {
                    NukeDialogTextButton(stringResource(R.string.host_streak_close), onDismiss, Modifier.weight(1f))
                    NukeDialogAccentButton(stringResource(R.string.home_save), {
                        onSave(draft.filter { (key, value) -> initial[key] != value })
                        onDismiss()
                    }, Modifier.weight(1f), enabled = draft != initial && problem == null)
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OptionsBodyText(stringResource(R.string.host_streak_notice))
                    if (!feature.configured) OptionsBodyText(stringResource(R.string.host_streak_disabled), warning = true)
                    OptionsBodyText(
                        if (account is HostAccount.SignedIn) stringResource(R.string.host_streak_account, account.userId)
                        else stringResource(R.string.host_streak_no_account),
                        warning = account !is HostAccount.SignedIn,
                    )
                    NukeSettingGroup {
                        NukeNavRow(title = stringResource(R.string.host_streak_selected, selected.size), onClick = { page = StreakPage.FRIENDS })
                        if (selected.isNotEmpty()) {
                            val byUid = friends.orEmpty().associateBy { it.uid }
                            Row(
                                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                selected.take(PREVIEW_AVATARS).forEach { uid ->
                                    val friend = byUid[uid]
                                    ContactAvatar(friend?.name ?: uid, friend?.avatarUrl, avatars, size = 32.dp)
                                }
                                if (selected.size > PREVIEW_AVATARS) OptionsBodyText("+${selected.size - PREVIEW_AVATARS}")
                            }
                        }
                        NukeNavRow(
                            title = stringResource(R.string.host_streak_time, clock(hour, minute), ZoneId.systemDefault().id),
                            onClick = { page = StreakPage.TIME },
                        )
                    }
                    NukeSettingGroup {
                        val modeLabels = MODES.associateWith { modeLabel(it) }
                        NukeSelectRow(
                            title = stringResource(R.string.host_option_auto_streak_mode),
                            options = MODES,
                            selectedOption = mode,
                            onOptionSelected = { set(MODE, it) },
                            optionToString = { modeLabels.getValue(it) },
                        )
                    }
                    if (mode == HITOKOTO) {
                        NukeSettingGroup(title = stringResource(R.string.host_option_auto_streak_url)) {
                            NukeTextField(
                                value = url,
                                onValueChange = { set(URL, it.take(MAX_URL)) },
                                modifier = Modifier.padding(12.dp),
                                placeholder = DEFAULT_URL,
                            )
                        }
                    }
                    if (mode == INTERACTIVE) {
                        OptionsBodyText(stringResource(R.string.host_streak_emoji_hint))
                    } else {
                        val messageTitle = stringResource(if (mode == HITOKOTO) R.string.host_streak_fallback_message else R.string.host_streak_message)
                        NukeSettingGroup(title = messageTitle) {
                            NukeTextField(
                                value = text,
                                onValueChange = { set(TEXT, it.take(MAX_TEXT)) },
                                modifier = Modifier.padding(12.dp),
                                placeholder = messageTitle,
                                singleLine = false,
                                minLines = 2,
                                maxLines = 4,
                            )
                        }
                        if (mode == HITOKOTO) OptionsBodyText(stringResource(R.string.host_streak_hitokoto_hint))
                    }
                    NukeSettingGroup {
                        NukeNavRow(title = stringResource(R.string.host_streak_history), onClick = { page = StreakPage.HISTORY })
                    }
                    problem?.let { OptionsBodyText(stringResource(it), warning = true) }
                    OptionsBodyText(stringResource(R.string.host_options_save_hint))
                }
            }
        }
    }
}

private enum class StreakPage { MAIN, FRIENDS, TIME, HISTORY }

@Composable
private fun TimeDialog(hour: Int, minute: Int, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var draftHour by remember { mutableStateOf(hour) }
    var draftMinute by remember { mutableStateOf(minute) }
    OptionsDialog(
        title = stringResource(R.string.host_streak_time_title),
        onDismiss = onDismiss,
        onSave = { onSave(draftHour, draftMinute) },
        saveEnabled = draftHour != hour || draftMinute != minute,
        hint = ZoneId.systemDefault().id,
    ) {
        NukeSettingGroup {
            NukeSelectRow(
                title = stringResource(R.string.host_option_auto_streak_hour),
                options = (0..23).toList(),
                selectedOption = draftHour,
                onOptionSelected = { draftHour = it },
                optionToString = { String.format(Locale.ROOT, "%02d", it) },
            )
            NukeSelectRow(
                title = stringResource(R.string.host_option_auto_streak_minute),
                options = (0..59).toList(),
                selectedOption = draftMinute,
                onOptionSelected = { draftMinute = it },
                optionToString = { String.format(Locale.ROOT, "%02d", it) },
            )
        }
    }
}

// The journal is read when the page opens; the newest sends come first.
@Composable
private fun HistoryDialog(friends: List<HostFriend>, avatars: AvatarMemory, load: suspend () -> List<ActivityRecord>, onBack: () -> Unit) {
    var history by remember { mutableStateOf<HistoryState>(HistoryState.Loading) }
    LaunchedEffect(Unit) {
        history = try {
            HistoryState.Ready(load().take(MAX_HISTORY))
        } catch (error: IllegalStateException) {
            HistoryState.Unavailable(error.message.orEmpty())
        }
    }
    val byUid = friends.associateBy { it.uid }
    NukeDialog(
        visible = true,
        onDismissRequest = onBack,
        title = stringResource(R.string.host_streak_history),
        modifier = Modifier.padding(horizontal = 20.dp).widthIn(max = 560.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        actions = { NukeDialogTextButton(stringResource(R.string.host_streak_back), onBack, Modifier.weight(1f)) },
    ) {
        when (val current = history) {
            HistoryState.Loading -> OptionsBodyText(stringResource(R.string.host_contacts_loading))
            is HistoryState.Unavailable -> OptionsBodyText(stringResource(R.string.host_streak_history_unavailable, current.reason), warning = true)
            is HistoryState.Ready -> if (current.records.isEmpty()) {
                OptionsBodyText(stringResource(R.string.host_streak_no_history))
            } else {
                val zone = ZoneId.systemDefault()
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(current.records) { record ->
                        val friend = byUid[record.target]
                        val time = Instant.ofEpochMilli(record.at).atZone(zone).format(TIME_FORMAT)
                        NukeSettingGroup {
                            NukeSettingRow(
                                title = friend?.name?.takeIf(String::isNotBlank) ?: record.target,
                                subtitle = "$time · ${stringResource(statusLabel(record.status))} · ${modeLabel(record.choice)}",
                                leadingIcon = { ContactAvatar(friend?.name ?: record.target, friend?.avatarUrl, avatars) },
                                supportingContent = if (record.usedFallback || record.detail.isNotBlank()) ({
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (record.usedFallback) OptionsBodyText(stringResource(R.string.host_streak_fallback_used))
                                        if (record.detail.isNotBlank()) OptionsBodyText(record.detail)
                                    }
                                }) else null,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

private sealed interface HistoryState {
    data object Loading : HistoryState
    data class Ready(val records: List<ActivityRecord>) : HistoryState
    data class Unavailable(val reason: String) : HistoryState
}

@Composable
private fun modeLabel(mode: String): String = when (mode) {
    TEXT_MODE -> stringResource(R.string.host_choice_auto_streak_text)
    HITOKOTO -> stringResource(R.string.host_choice_auto_streak_hitokoto)
    INTERACTIVE -> stringResource(R.string.host_choice_auto_streak_interactive)
    else -> mode
}

private fun statusLabel(status: ActivityStatus): Int = when (status) {
    ActivityStatus.PENDING -> R.string.host_streak_sending
    ActivityStatus.DONE -> R.string.host_streak_acknowledged
    ActivityStatus.FAILED -> R.string.host_streak_failed
    ActivityStatus.UNKNOWN -> R.string.host_streak_unknown
}

private fun clock(hour: Int, minute: Int) = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

// The feature's option keys and choices; keep in step with AutoStreak in the TikTok host.
private const val FRIENDS = "friends"
private const val MODE = "mode"
private const val TEXT = "text"
private const val URL = "hitokoto-url"
private const val HOUR = "hour"
private const val MINUTE = "minute"
private const val TEXT_MODE = "text"
private const val HITOKOTO = "hitokoto"
private const val INTERACTIVE = "interactive"
// NukeApp's order: fixed text, the interactive emoji, then the daily sentence.
private val MODES = listOf(TEXT_MODE, INTERACTIVE, HITOKOTO)
private const val DEFAULT_URL = "https://v1.hitokoto.cn/?encode=text"
private const val MAX_FRIENDS = 100
private const val MAX_TEXT = 500
private const val MAX_URL = 300
private const val PREVIEW_AVATARS = 5
private const val MAX_HISTORY = 50
private val TIME_FORMAT = DateTimeFormatter.ofPattern("MM-dd HH:mm")
