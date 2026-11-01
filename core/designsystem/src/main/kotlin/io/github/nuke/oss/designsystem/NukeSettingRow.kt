package io.github.nuke.oss.designsystem

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NukeSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    independentTrailingContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    supportingContent: (@Composable () -> Unit)? = null,
    onClickAt: ((Offset) -> Unit)? = null,
    role: Role = Role.Button,
) {
    val clickModifier = when {
        !enabled -> Modifier
        onClickAt != null -> Modifier.nukeClickAt(role = role, onClick = onClickAt)
        onClick != null -> Modifier.nukeClick(role = role, onClick = onClick)
        else -> Modifier
    }

    if (independentTrailingContent == null) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .then(clickModifier)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NukeSettingRowContent(
                title = title,
                subtitle = subtitle,
                leadingIcon = leadingIcon,
                trailingContent = trailingContent,
                supportingContent = supportingContent,
                enabled = enabled
            )
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(clickModifier)
                    .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NukeSettingRowContent(
                    title = title,
                    subtitle = subtitle,
                    leadingIcon = leadingIcon,
                    trailingContent = trailingContent,
                    supportingContent = supportingContent,
                    enabled = enabled
                )
            }
            independentTrailingContent()
        }
    }
}

@Composable
private fun RowScope.NukeSettingRowContent(
    title: String,
    subtitle: String?,
    leadingIcon: (@Composable () -> Unit)?,
    trailingContent: (@Composable () -> Unit)?,
    supportingContent: (@Composable () -> Unit)?,
    enabled: Boolean
) {
    if (leadingIcon != null) {
        leadingIcon()
        Spacer(modifier = Modifier.width(12.dp))
    }

    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            color = if (enabled) NukeTheme.colors.textPrimary else NukeTheme.colors.textSecondary,
            fontSize = 15.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold
        )
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = subtitle,
                color = NukeTheme.colors.textSecondary,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Normal
            )
        }
        if (supportingContent != null) {
            Spacer(modifier = Modifier.height(6.dp))
            supportingContent()
        }
    }

    if (trailingContent != null) {
        Spacer(modifier = Modifier.width(8.dp))
        trailingContent()
    }
}
