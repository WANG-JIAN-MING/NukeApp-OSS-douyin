package io.github.nuke.oss.ui.manager

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.settings.UiConfig
import io.github.nuke.oss.assembly.HostScope
import io.github.nuke.oss.designsystem.CodeText
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.NukeDialog
import io.github.nuke.oss.designsystem.NukeDialogAccentButton
import io.github.nuke.oss.designsystem.NukeDialogBodyText
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeIconBadge
import io.github.nuke.oss.designsystem.NukeLinearProgressIndicator
import io.github.nuke.oss.designsystem.NukeMenuItem
import io.github.nuke.oss.designsystem.NukeNavChevron
import io.github.nuke.oss.designsystem.NukeOverflowMenu
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeStatusCard
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.NukeVersionText
import io.github.nuke.oss.designsystem.StatusDot
import io.github.nuke.oss.status.Activation
import io.github.nuke.oss.status.ActivationLevel
import io.github.nuke.oss.status.HostActivation
import io.github.nuke.oss.ui.home.AboutDestination
import io.github.nuke.oss.ui.home.AppearanceDestination
import io.github.nuke.oss.ui.home.HomePage
import io.github.nuke.oss.ui.home.HomeShell
import io.github.nuke.oss.ui.home.NukeThemed
import java.io.File

data class ManagerUiState(
    val appVersion: String,
    val isExporting: Boolean = false,
    val error: String? = null,
    val ui: UiConfig = UiConfig(),
    val backgroundFile: File? = null,
    // Null until the first read finishes; never shown as active before then.
    val activation: Activation? = null,
    val launcherVisible: Boolean = true,
)

private const val APPEARANCE = "appearance"
private const val ABOUT = "about"

// The module's main page: the activation card, the target apps, the module's own actions
// and links, with appearance, about and the launcher icon behind the top bar menu.
@Composable
fun ManagerScreen(
    state: ManagerUiState,
    onExportModule: () -> Unit,
    onOpenSource: () -> Unit,
    onLauncherVisibleChange: (Boolean) -> Unit,
    onUiChange: (UiConfig) -> Unit,
    onPickBackground: suspend (Uri) -> Boolean,
    onRemoveBackground: () -> Unit,
) {
    NukeThemed(state.ui) {
        // Long explanations open on demand so the page itself stays short.
        var detail by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
        detail?.let { (title, body) ->
            NukeDialog(
                visible = true,
                onDismissRequest = { detail = null },
                title = title,
                actions = {
                    NukeDialogAccentButton(stringResource(R.string.manager_close), { detail = null }, Modifier.weight(1f))
                },
            ) {
                Box(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { NukeDialogBodyText(body) }
            }
        }
        HomeShell(
            ui = state.ui,
            backgroundFile = state.backgroundFile,
            home = { open ->
                Home(state, open, onExportModule, onOpenSource, onLauncherVisibleChange) { title, body -> detail = title to body }
            },
            destination = { key, back ->
                when (key) {
                    APPEARANCE -> AppearanceDestination(state.ui, onUiChange, onPickBackground, onRemoveBackground, back)
                    ABOUT -> AboutDestination(state.appVersion, back)
                }
            },
        )
    }
}

@Composable
private fun Home(
    state: ManagerUiState,
    open: (String, Offset) -> Unit,
    onExportModule: () -> Unit,
    onOpenSource: () -> Unit,
    onLauncherVisibleChange: (Boolean) -> Unit,
    onDetail: (String, String) -> Unit,
) {
    // Pages opened from the menu grow from the menu button, as rows grow from the touch point.
    var menuOrigin by remember { mutableStateOf(Offset.Zero) }
    val menu = listOf(
        NukeMenuItem(stringResource(R.string.home_appearance)) { open(APPEARANCE, menuOrigin) },
        NukeMenuItem(stringResource(R.string.manager_about_title)) { open(ABOUT, menuOrigin) },
        NukeMenuItem(stringResource(if (state.launcherVisible) R.string.manager_launcher_hide else R.string.manager_launcher_show)) {
            onLauncherVisibleChange(!state.launcherVisible)
        },
    )
    HomePage(
        title = stringResource(R.string.manager_app_name),
        onBack = null,
        actions = {
            NukeOverflowMenu(
                menu,
                stringResource(R.string.manager_more),
                Modifier.onGloballyPositioned { menuOrigin = it.boundsInRoot().center },
            )
        },
    ) {
        item(key = "status") { StatusCard(state.activation, onDetail) }
        state.error?.let { message -> item(key = "error") { ErrorPanel(message) } }
        state.activation?.let { activation -> item(key = "targets") { TargetGroup(activation) } }
        item(key = "module") { ModuleGroup(state, onExportModule, onDetail) }
        item(key = "links") { LinksGroup(onOpenSource) }
        item(key = "version") { NukeVersionText(stringResource(R.string.home_version, state.appVersion)) }
    }
}

@Composable
private fun StatusCard(activation: Activation?, onDetail: (String, String) -> Unit) {
    if (activation == null) {
        NukeStatusCard(stringResource(R.string.manager_status_reading), null, Glyph.Info, NukeTheme.colors.textSecondary)
        return
    }
    val (title, glyph, color) = when (activation.level) {
        ActivationLevel.ACTIVE -> Triple(R.string.manager_status_active, Glyph.Check, NukeTheme.colors.success)
        ActivationLevel.PARTIAL -> Triple(R.string.manager_status_partial, Glyph.Info, NukeTheme.colors.warning)
        ActivationLevel.INACTIVE -> Triple(R.string.manager_status_inactive, Glyph.Close, NukeTheme.colors.danger)
    }
    val detailsTitle = stringResource(R.string.manager_details_title)
    val details = details(activation)
    NukeStatusCard(stringResource(title), summary(activation), glyph, color, onClick = { onDetail(detailsTitle, details) })
}

// The framework line names the API 102 service when one is bound, else the XP82 framework that loaded the module.
@Composable
private fun framework(activation: Activation): String? =
    activation.service?.let { stringResource(R.string.manager_status_service, it.name, it.version, it.versionCode, it.api) }
        ?: activation.legacyFramework?.let { stringResource(R.string.manager_status_legacy, it) }

@Composable
private fun summary(activation: Activation): String {
    val framework = framework(activation)
    return when (activation.level) {
        ActivationLevel.ACTIVE -> framework.orEmpty()
        ActivationLevel.PARTIAL -> stringResource(
            R.string.manager_status_abi,
            activation.hosts.first { it.installed && !it.abiSupported }.abi.orEmpty(),
        )
        ActivationLevel.INACTIVE -> framework?.let { stringResource(R.string.manager_status_no_scope, it) }
            ?: stringResource(R.string.manager_status_none)
    }
}

@Composable
private fun details(activation: Activation): String {
    val legacy = activation.legacyFramework?.let { stringResource(R.string.manager_details_legacy, it) }
        ?: stringResource(R.string.manager_details_legacy_none)
    val service = activation.service?.let { service ->
        val scope = service.scope.sorted().joinToString().ifEmpty { stringResource(R.string.manager_details_scope_empty) }
        stringResource(R.string.manager_details_service, framework(activation).orEmpty(), scope)
    } ?: stringResource(R.string.manager_details_service_none)
    return listOf(legacy, service, stringResource(R.string.manager_details_undetectable)).joinToString("\n\n")
}

// Scope is what the framework manager is configured to do; loaded processes are what it actually did.
@Composable
private fun TargetGroup(activation: Activation) {
    NukeSettingGroup(title = stringResource(R.string.manager_section_targets)) {
        activation.hosts.forEachIndexed { index, host ->
            if (index > 0) NukeDivider()
            val colors = NukeTheme.colors
            val (dot, filled) = when {
                !host.installed -> colors.textSecondary to false
                !host.abiSupported -> colors.warning to true
                host.inScope == true -> colors.success to true
                host.inScope == false -> colors.danger to true
                else -> colors.textSecondary to false
            }
            NukeSettingRow(
                title = hostLabel(host.packageName),
                subtitle = hostSummary(host),
                supportingContent = { CodeText(host.packageName) },
                trailingContent = { StatusDot(dot, filled) },
            )
        }
    }
}

@Composable
private fun hostLabel(packageName: String): String = when (packageName) {
    HostScope.FIXTURE -> stringResource(R.string.manager_scope_fixture)
    HostScope.TIKTOK -> stringResource(R.string.manager_scope_tiktok)
    else -> packageName
}

@Composable
private fun hostSummary(host: HostActivation): String {
    if (!host.installed) return stringResource(R.string.manager_host_not_installed)
    val parts = buildList {
        add(when (host.inScope) {
            true -> stringResource(R.string.manager_host_in_scope)
            false -> stringResource(R.string.manager_host_not_in_scope)
            null -> stringResource(R.string.manager_host_scope_unknown)
        })
        host.running?.takeIf { it > 0 }?.let { add(pluralStringResource(R.plurals.manager_host_running, it, it)) }
        host.restartNeeded?.takeIf { it > 0 }?.let { add(pluralStringResource(R.plurals.manager_host_restart, it, it)) }
        if (!host.abiSupported) add(stringResource(R.string.manager_host_abi_unsupported, host.abi.orEmpty()))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun ModuleGroup(state: ManagerUiState, onExportModule: () -> Unit, onDetail: (String, String) -> Unit) {
    val busy = stringResource(R.string.manager_exporting)
    NukeSettingGroup(title = stringResource(R.string.home_section_module)) {
        NukeSettingRow(
            title = stringResource(R.string.manager_export_action),
            subtitle = if (state.isExporting) busy else null,
            enabled = !state.isExporting,
            onClick = onExportModule,
            modifier = if (state.isExporting) Modifier.semantics {
                stateDescription = busy
                liveRegion = LiveRegionMode.Polite
            } else Modifier,
            supportingContent = if (state.isExporting) ({ NukeLinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }) else null,
        )
        NukeDivider()
        NukeSettingRow(
            title = stringResource(R.string.manager_features_where_title),
            subtitle = stringResource(R.string.manager_scope_settings),
        )
        listOf(
            R.string.manager_deploy_xposed_title to R.string.manager_deploy_xposed_description,
            R.string.manager_deploy_root_title to R.string.manager_deploy_root_description,
            R.string.manager_deploy_patch_title to R.string.manager_deploy_patch_description,
        ).forEach { (titleId, bodyId) ->
            val title = stringResource(titleId)
            val body = stringResource(bodyId)
            NukeDivider()
            NukeSettingRow(title = title, onClick = { onDetail(title, body) }, trailingContent = { NukeNavChevron() })
        }
    }
}

@Composable
private fun LinksGroup(onOpenSource: () -> Unit) {
    NukeSettingGroup(title = stringResource(R.string.manager_section_links)) {
        NukeSettingRow(
            title = stringResource(R.string.manager_source_code),
            leadingIcon = { NukeIconBadge(Glyph.Link) },
            supportingContent = { CodeText(stringResource(R.string.manager_source_url)) },
            onClick = onOpenSource,
            trailingContent = { NukeNavChevron() },
        )
    }
}
