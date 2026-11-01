package io.github.nuke.oss.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@Composable
fun NukeCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.LongPress
) {
    val haptic = LocalHapticFeedback.current
    val resolvedHapticEnabled = hapticEnabled ?: NukeTheme.clickHapticEnabled
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val innerScale by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (checked) {
            spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium
            )
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh
            )
        },
        label = "NukeCheckboxGeometryAnim"
    )
    val boxScale by animateFloatAsState(
        targetValue = when {
            isPressed && enabled -> 0.9f
            checked -> 1.04f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "NukeCheckboxPressScale"
    )

    val containerColor by animateColorAsState(
        targetValue = when {
            !enabled -> NukeTheme.colors.textSecondary.copy(alpha = 0.05f)
            checked -> NukeTheme.colors.accent.copy(alpha = 0.14f)
            else -> NukeTheme.colors.textSecondary.copy(alpha = 0.10f)
        },
        animationSpec = tween(durationMillis = 150),
        label = "NukeCheckboxContainerColor"
    )

    val boxShape = NukeSquircleShape(6.dp)
    val innerShape = NukeSquircleShape(3.dp)

    Box(
        modifier = modifier
            .size(20.dp)
            .scale(boxScale)
            .clip(boxShape)
            .background(containerColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled
            ) {
                if (resolvedHapticEnabled) {
                    haptic.performHapticFeedback(hapticFeedbackType)
                }
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .scale(innerScale)
                .background(
                    color = if (enabled) NukeTheme.colors.accent else NukeTheme.colors.textSecondary,
                    shape = innerShape
                )
        )
    }
}
