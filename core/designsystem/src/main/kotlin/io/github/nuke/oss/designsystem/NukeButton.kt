package io.github.nuke.oss.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NukeDialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.VirtualKey
) {
    NukeButtonSurface(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        hapticEnabled = hapticEnabled,
        hapticFeedbackType = hapticFeedbackType
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = if (enabled) {
                    NukeTheme.colors.textSecondary
                } else {
                    NukeTheme.colors.textSecondary.copy(alpha = 0.45f)
                },
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        )
    }
}

@Composable
fun NukeDialogAccentButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.VirtualKey
) {
    NukeButtonSurface(
        modifier = modifier,
        enabled = enabled,
        onClick = onClick,
        accent = true,
        hapticEnabled = hapticEnabled,
        hapticFeedbackType = hapticFeedbackType
    ) {
        BasicText(
            text = text,
            style = TextStyle(
                color = NukeTheme.colors.accent.copy(alpha = if (enabled) 1f else 0.45f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@Composable
private fun NukeButtonSurface(
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
    accent: Boolean = false,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.VirtualKey,
    content: @Composable () -> Unit
) {
    val shape = NukeSquircleShape(11.dp)

    val backgroundColor = if (accent) {
        NukeTheme.colors.accent.copy(alpha = if (enabled) 0.30f else 0.08f)
    } else {
        if (NukeTheme.colors.isLight) {
            Color.Black.copy(alpha = if (enabled) 0.06f else 0.02f)
        } else {
            Color.White.copy(alpha = if (enabled) 0.06f else 0.02f)
        }
    }

    Box(
        modifier = modifier
            .nukeClick(
                enabled = enabled,
                onClick = onClick,
                hapticEnabled = hapticEnabled,
                hapticFeedbackType = hapticFeedbackType
            )
            .clip(shape)
            .background(backgroundColor)
            .padding(horizontal = 17.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
