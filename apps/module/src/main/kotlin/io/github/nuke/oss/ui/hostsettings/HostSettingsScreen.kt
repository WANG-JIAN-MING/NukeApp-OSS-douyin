package io.github.nuke.oss.ui.hostsettings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import io.github.nuke.oss.R
import io.github.nuke.oss.BuildConfig
import io.github.nuke.oss.app.shared.settings.ActivityRecord
import io.github.nuke.oss.app.shared.integrity.DeviceTrust
import io.github.nuke.oss.app.shared.settings.HostAccount
import io.github.nuke.oss.app.shared.settings.PetConsole
import io.github.nuke.oss.app.shared.settings.ProtocolConsole
import io.github.nuke.oss.app.shared.settings.HostContactList
import io.github.nuke.oss.app.shared.settings.HostFeature
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeEmptyState
import io.github.nuke.oss.designsystem.NukeIconBadge
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeCardTheme
import io.github.nuke.oss.designsystem.NukeHomeHero
import io.github.nuke.oss.designsystem.NukeNavChevron
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.NukeUserProfileCard
import io.github.nuke.oss.designsystem.NukeVersionText
import io.github.nuke.oss.designsystem.StatusDot
import io.github.nuke.oss.ui.home.AboutDestination
import io.github.nuke.oss.ui.hostsettings.script.ScriptsDestination
import io.github.nuke.oss.ui.hostsettings.script.ScriptsModel
import io.github.nuke.oss.ui.home.AppearanceDestination
import io.github.nuke.oss.ui.home.HomePage
import io.github.nuke.oss.ui.home.HomeShell
import io.github.nuke.oss.ui.home.HomeTile
import io.github.nuke.oss.ui.home.HomeTileGroup
import io.github.nuke.oss.ui.home.NukeThemed
import io.github.nuke.oss.ui.home.SearchBackHandler
import io.github.nuke.oss.ui.home.inGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogBodyText
import io.github.nuke.oss.designsystem.NukeDialogTextButton
import androidx.activity.compose.BackHandler

private const val DEBUG = "debug"
private const val APPEARANCE = "appearance"
private const val ABOUT = "about"
private const val SCRIPTS = "scripts"
// Not a page: this tile opens the import and export dialog.
private const val CONFIG = "config"
// Features without options whose row still opens a dialog of their own.
private val DIALOG_FEATURES = setOf(PROTOCOL_SENDER)

// The feature switch screen that runs inside the host process. The query is local: it only filters this list.
@Composable
internal fun HostSettingsScreen(
    state: HostSettingsUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOptions: (String, Map<String, Any>) -> Unit,
    loadContacts: suspend () -> HostContactList,
    loadActivity: suspend (String) -> List<ActivityRecord>,
    protocol: ProtocolConsole?,
    pets: PetConsole?,
    featureFiles: java.io.File?,
    onUiChange: (UiConfig) -> Unit,
    onBugReports: (Boolean) -> Unit,
    onPickBackground: suspend (Uri) -> Boolean,
    onRemoveBackground: () -> Unit,
    onUnlock: (String) -> Unit,
    onCleanConfig: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onExportFailures: () -> Unit,
    onDismissConfigNotice: () -> Unit,
    onSafeMode: (Boolean) -> Unit,
    onRestartHost: () -> Unit,
    onClose: () -> Unit,
    scripts: ScriptsModel,
) {
    NukeThemed(state.ui) {
        SearchBackHandler(query) { onQueryChange("") }
        val live = state.session == HostSession.LIVE
        // Leaving with changes that only a restart applies asks first, as the old module's home screen did. A page that is
        // open takes the back gesture before this does.
        var confirmRestart by rememberSaveable { mutableStateOf(false) }
        val restartNeeded = live && state.restartNeeded
        val close: () -> Unit = { if (restartNeeded) confirmRestart = true else onClose() }
        BackHandler(enabled = restartNeeded) { confirmRestart = true }
        var optionsFor by rememberSaveable { mutableStateOf<String?>(null) }
        val openOptions: (String) -> Unit = { optionsFor = it }
        HomeShell(
            ui = state.ui,
            backgroundFile = state.backgroundFile,
            home = { open ->
                Home(state, query, onQueryChange, onToggle, openOptions, onSafeMode, onExportConfig, onImportConfig, onRestartHost, close, open)
            },
            destination = { key, back ->
                val category = FeatureCategory.entries.firstOrNull { it.key == key }
                when {
                    key == APPEARANCE && live -> AppearanceDestination(state.ui, onUiChange, onPickBackground, onRemoveBackground, back)
                    key == ABOUT -> AboutDestination(BuildConfig.VERSION_NAME, back)
                    key == SCRIPTS && live -> ScriptsDestination(scripts, back)
                    key == DEBUG && live -> ModuleDebugPage(state, onBugReports, onUnlock, onCleanConfig, onExportFailures, onRestartHost, back)
                    category != null -> HomePage(stringResource(category.title), back) {
                        item(key = category.key) {
                            NukeSettingGroup(title = stringResource(category.title)) {
                                val features = state.features.filter { categoryOf(it.id) == category }
                                features.forEachIndexed { index, feature ->
                                    if (index > 0) NukeDivider()
                                    FeatureRow(feature, enabled = live, locked = state.isLocked(feature), onToggle = { onToggle(feature.id, it) }) {
                                        openOptions(feature.id)
                                    }
                                }
                            }
                        }
                    }
                }
            },
        )
        if (confirmRestart) {
            NukeDialog(
                onDismissRequest = {
                    confirmRestart = false
                    onClose()
                },
                title = stringResource(R.string.host_restart_prompt_title),
                contentText = stringResource(R.string.host_restart_prompt_message),
                confirmButtonText = stringResource(R.string.host_restart_prompt_confirm),
                dismissButtonText = stringResource(R.string.host_restart_prompt_leave),
                onConfirm = {
                    confirmRestart = false
                    onRestartHost()
                },
            )
        }
        // Both exports and the import answer here, whichever page asked, so the notice cannot be lost behind a page change.
        state.configNotice?.let { notice -> ConfigNoticeDialog(notice, onDismissConfigNotice, onRestartHost) }
        val editing = optionsFor?.let { id -> state.features.firstOrNull { it.id == id } }
        if (editing != null && live) {
            val title = featureLabels[editing.id]?.let { stringResource(it.title) } ?: editing.id
            // Auto streak, auto pet feeding, custom streak days, share groups and the protocol sender keep NukeApp's own dialogs; every other feature uses the
            // shared options dialog.
            if (editing.id == SHARE_GROUPS) {
                ShareGroupsDialog(
                    feature = editing,
                    title = title,
                    directory = featureFiles?.let { java.io.File(it, SHARE_GROUPS) },
                    onSave = { onOptions(editing.id, it) },
                    onDismiss = { optionsFor = null },
                    loadContacts = loadContacts,
                )
            } else if (editing.id == AUTO_PET_FEED) {
                AutoPetFeedDialog(
                    feature = editing,
                    title = title,
                    pets = pets,
                    onSave = { onOptions(editing.id, it) },
                    onDismiss = { optionsFor = null },
                    loadContacts = loadContacts,
                )
            } else if (editing.id == PROTOCOL_SENDER) {
                if (protocol != null) ProtocolSenderDialog(protocol) { optionsFor = null }
            } else if (editing.id == STREAK_DAYS) {
                StreakDaysDialog(
                    feature = editing,
                    title = title,
                    onSave = { onOptions(editing.id, it) },
                    onDismiss = { optionsFor = null },
                    loadContacts = loadContacts,
                )
            } else if (editing.id == AUTO_STREAK) {
                AutoStreakDialog(
                    feature = editing,
                    title = title,
                    account = state.account,
                    onSave = { onOptions(editing.id, it) },
                    onDismiss = { optionsFor = null },
                    loadContacts = loadContacts,
                    loadHistory = { loadActivity(editing.id) },
                )
            } else {
                FeatureOptionsDialog(
                    feature = editing,
                    title = title,
                    onSave = { onOptions(editing.id, it) },
                    onDismiss = { optionsFor = null },
                    loadContacts = loadContacts,
                )
            }
        }
    }
}

@Composable
private fun Home(
    state: HostSettingsUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onOpenOptions: (String) -> Unit,
    onSafeMode: (Boolean) -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onRestartHost: () -> Unit,
    onClose: () -> Unit,
    open: (String, Offset) -> Unit,
) {
    var confirmSafeMode by rememberSaveable { mutableStateOf(false) }
    var configDialog by rememberSaveable { mutableStateOf(false) }
    val live = state.session == HostSession.LIVE
    val needle = query.trim()
    val searching = needle.isNotEmpty()
    // The list builder below is not composable, so every string it needs is resolved here.
    val matches = if (!searching) emptyList() else state.features.filter { feature ->
        val label = featureLabels[feature.id]
        val texts = listOf(feature.id) + label?.let { listOf(stringResource(it.title), stringResource(it.description)) }.orEmpty()
        texts.any { it.contains(needle, ignoreCase = true) }
    }
    val sections = CategorySection.entries.mapNotNull { section ->
        val tiles = FeatureCategory.entries.filter { it.section == section }.mapNotNull { category ->
            val count = state.features.count { categoryOf(it.id) == category }
            if (count == 0) null else HomeTile(stringResource(category.title), category.glyph, category.key, count.toString())
        }
        if (tiles.isEmpty()) null else Triple(section.name, stringResource(section.title), tiles)
    }
    // Like NukeApp's module debug entry: the number of locked features as a warning, otherwise how many there are.
    val locked = state.features.count(state::isLocked)
    val debugTile = HomeTile(stringResource(R.string.host_debug_title), Glyph.Bug, DEBUG,
        if (locked > 0) "!$locked" else state.features.size.toString(), warning = locked > 0)
    val appearanceTile = HomeTile(stringResource(R.string.home_appearance), Glyph.Palette, APPEARANCE)
    val aboutTile = HomeTile(stringResource(R.string.manager_about_title), Glyph.Info, ABOUT)
    val configTile = HomeTile(stringResource(R.string.host_config_entry), Glyph.Share, CONFIG)
    val scriptsTile = HomeTile(stringResource(R.string.script_home_entry), Glyph.Code, SCRIPTS)
    val general = if (live) listOf(debugTile, scriptsTile, appearanceTile, configTile, aboutTile) else listOf(aboutTile)
    val appName = stringResource(R.string.manager_app_name)
    HomePage(
        title = appName,
        onBack = null,
        onBackPlain = onClose,
        backDescription = stringResource(R.string.manager_close),
    ) {
        item(key = "hero") {
            NukeHomeHero(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = stringResource(R.string.manager_search_placeholder),
                profile = if (searching) null else ({ ProfileCard(state) }),
            )
        }
        if (searching) {
            item(key = "results") {
                if (matches.isEmpty()) {
                    NukeEmptyState(stringResource(R.string.home_search_empty_title), stringResource(R.string.manager_search_empty, needle))
                } else {
                    NukeSettingGroup(title = stringResource(R.string.home_search_results)) {
                        matches.forEachIndexed { index, feature ->
                            if (index > 0) NukeDivider()
                            FeatureRow(feature, enabled = live, locked = state.isLocked(feature), onToggle = { onToggle(feature.id, it) }) {
                                onOpenOptions(feature.id)
                            }
                        }
                    }
                }
            }
        } else {
            if (!live) item(key = "session") { UnavailableGroup() }
            if (live) item(key = "safety") {
                // Turning safe mode on restarts the host after a confirmation; turning it off applies at the next start.
                NukeSettingGroup(title = stringResource(R.string.host_safety_section)) {
                    NukeSettingRow(
                        title = stringResource(R.string.host_safe_mode),
                        subtitle = stringResource(R.string.host_safe_mode_description),
                        leadingIcon = { NukeIconBadge(Glyph.Shield) },
                        onClick = { if (state.safeMode) onSafeMode(false) else confirmSafeMode = true },
                        trailingContent = {
                            NukeSwitch(checked = state.safeMode, onCheckChanged = { on -> if (on) confirmSafeMode = true else onSafeMode(false) })
                        },
                    )
                }
            }
            sections.forEach { (key, title, tiles) ->
                tiles.inGroups().forEachIndexed { index, group ->
                    item(key = "tiles:$key:$index") { HomeTileGroup(if (index == 0) title else null, group, open) }
                }
            }
            item(key = "general") {
                HomeTileGroup(stringResource(R.string.home_section_general), general) { key, origin ->
                    if (key == CONFIG) configDialog = true else open(key, origin)
                }
            }
        }
        item(key = "version") { NukeVersionText(stringResource(R.string.home_version, BuildConfig.VERSION_NAME)) }
    }
    if (configDialog && live) {
        NukeDialog(
            visible = true,
            onDismissRequest = { configDialog = false },
            title = stringResource(R.string.host_config_title),
            actions = {
                NukeDialogTextButton(stringResource(R.string.host_config_import), {
                    configDialog = false
                    onImportConfig()
                }, Modifier.weight(1f))
                NukeDialogAccentButton(stringResource(R.string.host_config_export), {
                    configDialog = false
                    onExportConfig()
                }, Modifier.weight(1f))
            },
        ) {
            NukeDialogBodyText(stringResource(R.string.host_config_message))
        }
    }
    if (confirmSafeMode) {
        NukeDialog(
            onDismissRequest = { confirmSafeMode = false },
            title = stringResource(R.string.host_safe_mode_enable_title),
            contentText = stringResource(R.string.host_safe_mode_enable_message),
            confirmButtonText = stringResource(R.string.host_safe_mode_enable),
            dismissButtonText = stringResource(R.string.home_cancel),
            onConfirm = {
                confirmSafeMode = false
                onSafeMode(true)
                onRestartHost()
            },
        )
    }
}

private fun HostSettingsUiState.isLocked(feature: HostFeature): Boolean =
    debugStatus(feature, failures[feature.id]) == DebugStatus.LOCKED

// The host account, as the host's own account service reports it. Without a session, or before the host answers,
// the card names the module or the host instead of guessing an account. The avatar opens the device trust checks.
@Composable
private fun ProfileCard(state: HostSettingsUiState) {
    val context = LocalContext.current
    val hostLabel = remember(context) { context.applicationInfo.loadLabel(context.packageManager).toString() }
    var showTrust by remember { mutableStateOf(false) }
    val openTrust = { showTrust = true }
    val trustDescription = stringResource(R.string.device_trust_avatar_description)
    when (val account = state.account) {
        is HostAccount.SignedIn -> NukeUserProfileCard(
            name = account.nickname.ifBlank { hostLabel },
            identifier = account.userId,
            onIdentifierClick = { copyUserId(context, account.userId) },
            onAvatarClick = openTrust,
            avatarDescription = trustDescription,
            avatar = { AccountAvatar(account.avatarUrls) },
        )
        HostAccount.SignedOut -> NukeUserProfileCard(
            hostLabel, stringResource(R.string.host_profile_signed_out),
            onAvatarClick = openTrust, avatarDescription = trustDescription,
        )
        HostAccount.Unavailable -> if (state.host != null) {
            NukeUserProfileCard(hostLabel, state.host, onAvatarClick = openTrust, avatarDescription = trustDescription)
        } else {
            NukeUserProfileCard(
                stringResource(R.string.manager_app_name), stringResource(R.string.manager_app_package),
                onAvatarClick = openTrust, avatarDescription = trustDescription,
            )
        }
    }
    if (showTrust) {
        DeviceTrustDialog(load = { withContext(Dispatchers.IO) { DeviceTrust.check() } }, onDismiss = { showTrust = false })
    }
}

@Composable
private fun AccountAvatar(urls: List<String>) {
    val image by produceState<ImageBitmap?>(null, urls) {
        value = withContext(Dispatchers.IO) { fetchAvatar(urls) }
    }
    image?.let { Image(it, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop) }
}

private fun copyUserId(context: Context, userId: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("user ID", userId))
    Toast.makeText(context, context.getString(R.string.host_author_copied), Toast.LENGTH_SHORT).show()
}

// Shown only when the launch carried no session; a live screen needs no banner.
@Composable
private fun UnavailableGroup() {
    NukeSettingGroup(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        NukeSettingRow(
            title = stringResource(R.string.host_settings_invalid),
            subtitle = stringResource(R.string.host_settings_invalid_description),
            trailingContent = { StatusDot(NukeTheme.colors.danger, true) },
        )
    }
}

// A feature row as NukeApp draws it: the switch alone, or for a feature with options the row opens its options
// dialog and the switch sits apart behind a divider. Whether a change is running yet is on the debug page; only a
// locked feature is marked here, and its row stays disabled until it is unlocked there.
@Composable
private fun FeatureRow(feature: HostFeature, enabled: Boolean, locked: Boolean, onToggle: (Boolean) -> Unit, onOpenOptions: () -> Unit) {
    val label = featureLabels[feature.id]
    val title = label?.let { stringResource(it.title) } ?: feature.id
    val subtitle = label?.let { stringResource(it.description) }
    val leading: (@Composable () -> Unit)? = if (locked) ({ DebugStatusMark(DebugStatus.LOCKED) }) else null
    val supporting: (@Composable () -> Unit)? = if (locked) ({ DebugStatusPill(DebugStatus.LOCKED) }) else null
    @Suppress("NAME_SHADOWING") val enabled = enabled && !locked
    if (feature.options.isEmpty() && feature.id !in DIALOG_FEATURES) {
        NukeSettingRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = leading,
            supportingContent = supporting,
            enabled = enabled,
            onClick = { onToggle(!feature.configured) },
            role = Role.Switch,
            modifier = Modifier.semantics { toggleableState = ToggleableState(feature.configured) },
            // The row carries the toggle semantics; the switch only mirrors the stored value.
            trailingContent = { NukeSwitch(checked = feature.configured, onCheckChanged = null, enabled = enabled) },
        )
    } else {
        NukeSettingRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = leading,
            supportingContent = supporting,
            enabled = enabled,
            onClick = onOpenOptions,
            trailingContent = { NukeNavChevron(enabled) },
            independentTrailingContent = {
                Row(Modifier.padding(end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    VerticalDivider(
                        Modifier.height(28.dp),
                        thickness = 1.dp,
                        color = NukeTheme.colors.textSecondary.copy(alpha = (if (enabled) 0.18f else 0.10f) * NukeCardTheme.controlAlpha),
                    )
                    Spacer(Modifier.width(12.dp))
                    NukeSwitch(checked = feature.configured, onCheckChanged = onToggle, enabled = enabled)
                }
            },
        )
    }
}
