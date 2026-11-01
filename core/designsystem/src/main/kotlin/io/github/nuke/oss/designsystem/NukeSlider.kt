package io.github.nuke.oss.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// Nuke-styled Material 3 slider.
// The pointer observer only observes the gesture in the Initial pass. It deliberately does not
// consume the event, so Material 3 remains the owner of value calculation and the first tap keeps
// its actual position instead of being interpreted as the range start.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NukeSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    activeTrackColor: Color = NukeTheme.colors.accent,
    inactiveTrackColor: Color = NukeTheme.colors.border,
    thumbColor: Color = NukeTheme.colors.sliderThumb,
    thumbSize: Dp = 20.dp,
    trackHeight: Dp = 6.dp,
    hapticEnabled: Boolean? = null,
    hapticFeedbackType: HapticFeedbackType = HapticFeedbackType.ContextClick,
) {
    val interactionSource = remember { MutableInteractionSource() }
    var isPressed by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val resolvedHapticEnabled = hapticEnabled ?: NukeTheme.clickHapticEnabled
    val thumbScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(),
        label = "NukeSliderThumbScale",
    )
    val safeRange = valueRange.start..valueRange.endInclusive
    val safeValue = value.coerceIn(safeRange.start, safeRange.endInclusive)

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Slider(
            value = safeValue,
            onValueChange = onValueChange,
            modifier = modifier.pointerInput(enabled, hapticFeedbackType, resolvedHapticEnabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    isPressed = true
                    if (resolvedHapticEnabled) {
                        haptic.performHapticFeedback(hapticFeedbackType)
                    }
                    try {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                        } while (event.changes.any { it.pressed })
                    } finally {
                        isPressed = false
                    }
                }
            },
            enabled = enabled,
            valueRange = safeRange,
            steps = steps,
            onValueChangeFinished = onValueChangeFinished,
            interactionSource = interactionSource,
            colors = SliderDefaults.colors(
                activeTrackColor = activeTrackColor,
                inactiveTrackColor = inactiveTrackColor,
                thumbColor = thumbColor,
                disabledActiveTrackColor = activeTrackColor.copy(alpha = 0.38f),
                disabledInactiveTrackColor = inactiveTrackColor.copy(alpha = 0.38f),
                disabledThumbColor = thumbColor.copy(alpha = 0.38f),
            ),
            thumb = {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(thumbSize)
                        .graphicsLayer {
                            scaleX = thumbScale
                            scaleY = thumbScale
                        },
                ) {
                    Box(
                        modifier = Modifier
                            .size(thumbSize)
                            .clip(CircleShape)
                            .background(
                                if (enabled) thumbColor else thumbColor.copy(alpha = 0.38f),
                            ),
                    )
                }
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    enabled = enabled,
                    colors = SliderDefaults.colors(
                        activeTrackColor = activeTrackColor,
                        inactiveTrackColor = inactiveTrackColor,
                        disabledActiveTrackColor = activeTrackColor.copy(alpha = 0.38f),
                        disabledInactiveTrackColor = inactiveTrackColor.copy(alpha = 0.38f),
                    ),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                    modifier = Modifier.height(trackHeight),
                )
            },
        )
    }
}
