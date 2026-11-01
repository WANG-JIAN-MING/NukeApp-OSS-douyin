package io.github.nuke.oss.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NukeSwitch(
    checked: Boolean,
    // Null makes the switch purely visual: a row that already carries the toggle semantics owns the click.
    onCheckChanged: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.ToggleOn
) {
    val colors = NukeTheme.colors
    val containerAlpha = 1f
    val haptic = LocalHapticFeedback.current
    val resolvedHapticEnabled = hapticEnabled ?: NukeTheme.clickHapticEnabled
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val trackWidth = 48.dp
    val trackHeight = 28.dp
    val trackPadding = 3.dp
    val disabledTrackColor = if (checked) {
        colors.accent.copy(alpha = (if (colors.isLight) 0.24f else 0.32f) * containerAlpha)
    } else {
        colors.textSecondary.copy(alpha = (if (colors.isLight) 0.18f else 0.26f) * containerAlpha)
    }

    // track colour fades between states
    val trackColor by animateColorAsState(
        targetValue = when {
            !enabled -> disabledTrackColor
            checked -> colors.accent.copy(alpha = colors.accent.alpha * containerAlpha)
            else -> colors.border.copy(alpha = colors.border.alpha * containerAlpha)
        },
        animationSpec = tween(durationMillis = 180),
        label = "TrackColorAnimation"
    )

    val thumbWidthTarget = when {
        isPressed && enabled -> 27.dp
        checked -> 24.dp
        else -> 22.dp
    }
    val thumbHeightTarget = when {
        isPressed && enabled -> 20.dp
        else -> 22.dp
    }

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - trackPadding * 2f - thumbWidthTarget else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "ThumbOffsetAnimation"
    )
    val thumbWidth by animateDpAsState(
        targetValue = thumbWidthTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ThumbWidthAnimation"
    )
    val thumbHeight by animateDpAsState(
        targetValue = thumbHeightTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "ThumbHeightAnimation"
    )
    val thumbElevation by animateDpAsState(
        targetValue = when {
            !enabled -> 0.dp
            isPressed -> 1.dp
            checked -> 5.dp
            else -> 3.dp
        },
        animationSpec = tween(durationMillis = 180),
        label = "ThumbElevationAnimation"
    )
    val trackScaleX by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.98f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "TrackScaleXAnimation"
    )
    val trackScaleY by animateFloatAsState(
        targetValue = if (isPressed && enabled) 1.04f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "TrackScaleYAnimation"
    )
    val highlightOffset by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "HighlightOffsetAnimation"
    )
    val highlightAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0f
            checked -> 0.28f * containerAlpha
            else -> 0.12f * containerAlpha
        },
        animationSpec = tween(durationMillis = 180),
        label = "HighlightAlphaAnimation"
    )

    val thumbColor = when {
        enabled -> Color.White
        checked -> colors.accent.copy(alpha = if (colors.isLight) 0.74f else 0.86f)
        else -> colors.textSecondary.copy(alpha = if (colors.isLight) 0.58f else 0.76f)
    }

    Box(
        modifier = modifier
            .size(width = trackWidth, height = trackHeight)
            .graphicsLayer {
                scaleX = trackScaleX
                scaleY = trackScaleY
            }
            .clip(CircleShape)
            .background(trackColor)
            .then(
                if (onCheckChanged == null) Modifier else Modifier.clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Switch,
                ) {
                    if (resolvedHapticEnabled) {
                        haptic.performHapticFeedback(hapticFeedbackType)
                    }
                    onCheckChanged(!checked)
                }
            )
            .padding(trackPadding),
        contentAlignment = Alignment.CenterStart
    ) {
        SwitchHighlight(
            offset = highlightOffset,
            alpha = highlightAlpha
        )

        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(width = thumbWidth, height = thumbHeight)
                .shadow(
                    elevation = thumbElevation,
                    shape = CircleShape,
                    clip = false
                )
                .background(color = thumbColor, shape = CircleShape)
        )
    }
}

@Composable
private fun SwitchHighlight(
    offset: Dp,
    alpha: Float
) {
    Box(
        modifier = Modifier
            .offset(x = offset)
            .size(width = 20.dp, height = 14.dp)
            .graphicsLayer { this.alpha = alpha }
            .background(
                color = Color.White,
                shape = CircleShape
            )
    )
}
