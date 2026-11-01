package io.github.nuke.oss.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

// A theme-aware bottom sheet with Nuke's fluid shape and restrained motion.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NukeBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    sheetGesturesEnabled: Boolean = true,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = NukeTheme.colors
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    val cornerRadius by animateDpAsState(
        targetValue = if (entered) 30.dp else 40.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "NukeBottomSheetCornerRadius",
    )
    val contentAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "NukeBottomSheetContentAlpha",
    )
    val contentScaleX by animateFloatAsState(
        targetValue = if (entered) 1f else 0.985f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "NukeBottomSheetContentScaleX",
    )
    val contentScaleY by animateFloatAsState(
        targetValue = if (entered) 1f else 0.96f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "NukeBottomSheetContentScaleY",
    )
    val contentOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 10.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "NukeBottomSheetContentOffset",
    )
    val containerColor by animateColorAsState(
        targetValue = colors.background,
        animationSpec = tween(durationMillis = 220),
        label = "NukeBottomSheetContainerColor",
    )

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        sheetGesturesEnabled = sheetGesturesEnabled,
        shape = NukeTopSquircleShape(radius = cornerRadius),
        containerColor = containerColor,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = Color.Black.copy(alpha = if (colors.isLight) 0.34f else 0.62f),
        dragHandle = if (sheetGesturesEnabled) { { NukeDragHandle() } } else null,
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // Keep size constraints inside the surface, after the sheet's anchor layout.
                    .then(contentModifier)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .graphicsLayer {
                        alpha = contentAlpha
                        scaleX = contentScaleX
                        scaleY = contentScaleY
                        translationY = contentOffset.toPx()
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
            ) {
                content()
            }
        }
    )
}

@Composable
fun NukeDragHandle(
    modifier: Modifier = Modifier
) {
    val colors = NukeTheme.colors
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val handleWidth by animateDpAsState(
        targetValue = if (entered) 38.dp else 18.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "NukeDragHandleWidth",
    )
    val handleHeight by animateDpAsState(
        targetValue = if (entered) 4.dp else 3.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "NukeDragHandleHeight",
    )
    val handleColor by animateColorAsState(
        targetValue = lerp(
            colors.textSecondary,
            colors.accent,
            if (colors.isLight) 0.08f else 0.14f,
        ).copy(alpha = if (colors.isLight) 0.42f else 0.50f),
        animationSpec = tween(durationMillis = 240),
        label = "NukeDragHandleColor",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 11.dp, bottom = 13.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(handleWidth)
                .height(handleHeight)
                .clip(CircleShape)
                .background(handleColor)
        )
    }
}

@Immutable
private data class NukeTopSquircleShape(
    val radius: Dp,
    val smoothness: Float = 1.38f,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val width = size.width
        val height = size.height
        val requestedRadius = with(density) { radius.toPx() }
        val finalRadius = requestedRadius.coerceIn(0f, minOf(width / 2f, height))
        val safeSmoothness = smoothness.coerceIn(1f, 1.6f)
        val offset = (finalRadius * safeSmoothness).coerceAtMost(minOf(width / 2f, height))
        val control = (finalRadius * (0.52f + (safeSmoothness - 1f) * 0.18f))
            .coerceAtMost(offset)

        val path = Path().apply {
            moveTo(offset, 0f)
            lineTo(width - offset, 0f)
            cubicTo(width - control, 0f, width, control, width, offset)
            lineTo(width, height)
            lineTo(0f, height)
            lineTo(0f, offset)
            cubicTo(0f, control, control, 0f, offset, 0f)
            close()
        }
        return Outline.Generic(path)
    }
}
