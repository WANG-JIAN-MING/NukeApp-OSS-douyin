package io.github.nuke.oss.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

private val PROGRESS_COLORS = listOf(
    Color(0xFF12B7F5),
    Color(0xFF7C8CFF),
    Color(0xFFFF7EB3),
    Color(0xFFFFC371),
)

// Compose version of the flowing gradient, sweep, luminous tip and sparks in Luoxianlv's splash.
// A null [progress] is indeterminate; determinate values always come from the actual task.
// [animated] can freeze decoration on failure. Infinite effects leave composition on completion
// or disposal; animation state is read while drawing rather than recomposing on every frame.
@Composable
fun NukeLinearProgressIndicator(
    progress: (() -> Float)? = null,
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val targetProgress = progress?.invoke()?.let { value ->
        if (value.isFinite()) value.coerceIn(0f, 1f) else 0f
    }
    val displayedProgress = animateFloatAsState(
        targetValue = targetProgress ?: 0f,
        animationSpec = if (animated) tween(350, easing = FastOutSlowInEasing) else snap(),
        label = "NukeProgressValue",
    )
    val animateEffects = animated && (targetProgress == null || targetProgress > 0f && targetProgress < 1f)
    val phase = if (animateEffects && !LocalInspectionMode.current) {
        val transition = rememberInfiniteTransition(label = "NukeProgressFlow")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(5_000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "NukeProgressPhase",
        )
    } else {
        rememberUpdatedState(0.35f)
    }
    val colors = NukeTheme.colors
    val trackColor = colors.textSecondary.copy(alpha = if (colors.isLight) 0.18f else 0.24f)

    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .semantics {
                progressBarRangeInfo = targetProgress?.let { ProgressBarRangeInfo(it, 0f..1f) }
                    ?: ProgressBarRangeInfo.Indeterminate
            }
            .drawWithCache {
                val barHeight = 6.dp.toPx()
                val radius = barHeight / 2f
                val barTop = (size.height - barHeight) / 2f
                val corner = CornerRadius(radius, radius)
                val fillPath = Path()
                val fillBrush = Brush.linearGradient(
                    colors = PROGRESS_COLORS,
                    start = Offset.Zero,
                    end = Offset((size.width * 0.6f).coerceAtLeast(1f), 0f),
                    tileMode = TileMode.Mirror,
                )
                val bandWidth = 90.dp.toPx()
                val shineBrush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.6f), Color.Transparent),
                    startX = 0f,
                    endX = bandWidth,
                )
                val sparkTravel = 28.dp.toPx()
                val sparkRise = 10.dp.toPx()
                val glowRadius = 10.dp.toPx()

                onDrawBehind {
                    if (size.width <= 0f || size.height <= 0f) return@onDrawBehind
                    val time = phase.value
                    // Keep an indeterminate segment visible even when system animations are off.
                    val cycle = (time * 4f + 0.15f) % 1f
                    val segmentWidth = size.width * 0.28f
                    val movingEnd = cycle * (size.width + segmentWidth)
                    val startX = if (targetProgress == null) {
                        (movingEnd - segmentWidth).coerceIn(0f, size.width)
                    } else 0f
                    val endX = if (targetProgress == null) {
                        movingEnd.coerceIn(0f, size.width)
                    } else {
                        val value = displayedProgress.value
                        if (value <= 0f) {
                            0f
                        } else {
                            (size.width * value).coerceIn(barHeight.coerceAtMost(size.width), size.width)
                        }
                    }

                    scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
                        drawRoundRect(
                            color = trackColor,
                            topLeft = Offset(0f, barTop),
                            size = Size(size.width, barHeight),
                            cornerRadius = corner,
                        )
                        if (endX <= startX) return@scale
                        val width = endX - startX
                        for (layer in 2 downTo 1) {
                            val grow = 3.dp.toPx() * layer
                            drawRoundRect(
                                brush = fillBrush,
                                topLeft = Offset(startX - grow, barTop - grow),
                                size = Size(width + grow * 2f, barHeight + grow * 2f),
                                cornerRadius = CornerRadius(radius + grow, radius + grow),
                                alpha = if (layer == 2) 0.08f else 0.18f,
                            )
                        }

                        fillPath.reset()
                        fillPath.addRoundRect(RoundRect(startX, barTop, endX, barTop + barHeight, corner))
                        clipPath(fillPath) {
                            val flow = time * size.width * 1.2f
                            translate(left = flow) {
                                drawRect(fillBrush, topLeft = Offset(-flow, barTop), size = Size(size.width, barHeight))
                            }
                            if (animateEffects) {
                                val sweep = startX + cycle * (width + bandWidth) - bandWidth
                                translate(left = sweep) {
                                    drawRect(shineBrush, topLeft = Offset(-sweep, barTop), size = Size(size.width, barHeight))
                                }
                            }
                        }

                        val headX = (endX - radius).coerceAtLeast(startX)
                        val headY = size.height / 2f
                        val pulse = if (animateEffects) 1f + 0.18f * sin(time * PI.toFloat() * 10f) else 1f
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0.9f),
                                    PROGRESS_COLORS.first().copy(alpha = 0.35f),
                                    Color.Transparent,
                                ),
                                center = Offset(headX, headY),
                                radius = glowRadius * pulse,
                            ),
                            radius = glowRadius * pulse,
                            center = Offset(headX, headY),
                        )

                        if (animateEffects) {
                            // Deterministic, bounded trail: no particle objects or random allocation per frame.
                            for (index in 0 until 18) {
                                val age = (time * 5f + index / 18f) % 1f
                                val travel = sparkTravel * (0.5f + (index % 5) / 5f)
                                val x = headX - age * travel
                                if (x < startX || x > endX) continue
                                val direction = if (index % 3 == 0) 1f else -1f
                                val y = headY + direction * age * sparkRise * (0.4f + (index % 4) / 4f)
                                drawCircle(
                                    color = PROGRESS_COLORS[index % PROGRESS_COLORS.size],
                                    radius = (0.8f + (index % 3) * 0.4f).dp.toPx() * (1f - age * 0.4f),
                                    center = Offset(x, y),
                                    alpha = (1f - age) * 0.7f,
                                )
                            }
                        }
                    }
                }
            },
    )
}
