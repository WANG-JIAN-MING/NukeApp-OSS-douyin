package io.github.nuke.oss.ui.hostsettings.script

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.script.ScriptHold
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.GlyphIcon
import io.github.nuke.oss.designsystem.NukeCardSurface
import io.github.nuke.oss.designsystem.NukeSquircleShape
import io.github.nuke.oss.designsystem.NukeSwitch
import io.github.nuke.oss.designsystem.NukeTheme
import io.github.nuke.oss.designsystem.nukeClick

// The switch shows what the user chose; whether the script actually runs is told apart below it, so a script that is
// switched on but held or stopped never reads as running.
@Composable
internal fun InstalledScriptCard(
    script: ScriptItem,
    onRunningChange: (Boolean) -> Unit,
    onReadmeClick: () -> Unit,
    onLogsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = NukeTheme.colors
    val failed = script.failure != null
    val notice = when {
        failed -> stringResource(R.string.script_failed_label)
        !script.enabled -> null
        script.hold == ScriptHold.NEEDS_GRANT -> stringResource(R.string.script_hold_grant)
        script.hold == ScriptHold.SAFE_MODE -> stringResource(R.string.script_hold_safe_mode)
        script.hold == ScriptHold.LOCKED -> stringResource(R.string.script_hold_locked)
        else -> null
    }
    val container by animateColorAsState(
        targetValue = if (failed) lerp(colors.surface, colors.danger, if (colors.isLight) 0.10f else 0.18f) else colors.surface,
        animationSpec = tween(180),
        label = "ScriptCardColor",
    )
    val metadata = remember(script.version, script.author) {
        listOfNotNull(script.version.trim().takeIf(String::isNotEmpty), script.author?.trim()?.takeIf(String::isNotEmpty))
            .joinToString(" • ")
    }
    NukeCardSurface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (script.hasReadme) Modifier.nukeClick(onClick = onReadmeClick) else Modifier),
        shape = NukeSquircleShape(16.dp),
        color = container,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = script.name,
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (metadata.isNotEmpty()) {
                        Spacer(Modifier.height(3.dp))
                        Text(metadata, color = colors.textSecondary, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    script.description?.trim()?.takeIf(String::isNotEmpty)?.let { description ->
                        Spacer(Modifier.height(8.dp))
                        Text(description, color = colors.textSecondary, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    script.failure?.let { failure ->
                        Spacer(Modifier.height(8.dp))
                        Text(failure, color = colors.danger, fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.width(16.dp))
                NukeSwitch(checked = script.enabled, onCheckChanged = onRunningChange)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (notice != null) {
                    val tint = if (failed) colors.danger else colors.warning
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CompositionLocalProvider(LocalContentColor provides tint) { GlyphIcon(Glyph.Warning, null, iconSize = 18.dp) }
                        Text(notice, color = tint, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (script.hasReadme) CardIconButton(Glyph.Document, stringResource(R.string.script_card_readme), onReadmeClick)
                    CardIconButton(Glyph.Terminal, stringResource(R.string.script_card_logs), onLogsClick)
                    CardIconButton(Glyph.Sliders, stringResource(R.string.script_card_settings), onSettingsClick)
                }
            }
        }
    }
}

@Composable
private fun CardIconButton(glyph: Glyph, description: String, onClick: () -> Unit, enabled: Boolean = true) {
    val colors = NukeTheme.colors
    val background: Color = lerp(colors.surface, colors.textSecondary, if (colors.isLight) 0.075f else 0.13f)
    Column(
        modifier = Modifier
            .size(36.dp)
            .semantics {
                role = Role.Button
                contentDescription = description
            }
            .nukeClick(enabled = enabled, onClick = onClick)
            .clip(CircleShape)
            .background(background)
            .padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) colors.accent else colors.textSecondary) {
            GlyphIcon(glyph, null, iconSize = 18.dp)
        }
    }
}
