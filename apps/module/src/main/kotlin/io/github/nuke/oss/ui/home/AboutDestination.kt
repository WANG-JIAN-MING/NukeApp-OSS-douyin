package io.github.nuke.oss.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.R
import io.github.nuke.oss.designsystem.CodeText
import io.github.nuke.oss.designsystem.Glyph
import io.github.nuke.oss.designsystem.NukeDivider
import io.github.nuke.oss.designsystem.NukeIconBadge
import io.github.nuke.oss.designsystem.NukeSettingGroup
import io.github.nuke.oss.designsystem.NukeSettingRow
import io.github.nuke.oss.designsystem.NukeTheme

// The module's identity and a short description; shared by the module screen and the host switch screen.
@Composable
internal fun AboutDestination(version: String, onBack: (Offset) -> Unit) {
    HomePage(stringResource(R.string.manager_about_title), onBack) {
        item(key = "logo") {
            Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(94.dp).clip(CircleShape).background(NukeTheme.colors.accent.copy(alpha = 0.13f))
                        .border(1.dp, NukeTheme.colors.accent.copy(alpha = 0.28f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(painterResource(R.drawable.ic_settings_entry), stringResource(R.string.manager_app_name),
                        Modifier.size(54.dp))
                }
            }
        }
        item(key = "identity") {
            NukeSettingGroup {
                NukeSettingRow(
                    title = stringResource(R.string.manager_about_version),
                    supportingContent = { CodeText(version) },
                )
                NukeDivider()
                NukeSettingRow(
                    title = stringResource(R.string.manager_about_package),
                    supportingContent = { CodeText(stringResource(R.string.manager_app_package)) },
                )
            }
        }
        item(key = "community") {
            val context = LocalContext.current
            val failed = stringResource(R.string.home_about_telegram_failed)
            NukeSettingGroup(title = stringResource(R.string.home_about_community)) {
                NukeSettingRow(
                    title = stringResource(R.string.home_about_telegram),
                    subtitle = stringResource(R.string.home_about_telegram_description),
                    leadingIcon = { NukeIconBadge(Glyph.Link) },
                    onClick = {
                        if (!openInBrowser(context, TELEGRAM_CHANNEL_URL)) Toast.makeText(context, failed, Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
        item(key = "description") {
            NukeSettingGroup(title = stringResource(R.string.home_about_project)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.manager_about_architecture, R.string.manager_modes_explanation).forEach { line ->
                        BasicText(stringResource(line), style = TextStyle(color = NukeTheme.colors.textSecondary,
                            fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Normal))
                    }
                }
            }
        }
    }
}
