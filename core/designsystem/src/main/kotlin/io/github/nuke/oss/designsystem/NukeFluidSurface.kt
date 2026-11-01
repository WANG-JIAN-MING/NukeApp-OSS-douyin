package io.github.nuke.oss.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun NukeFluidSurface(
    modifier: Modifier = Modifier,
    shape: Shape = NukeSquircleShape(16.dp),
    color: Color = NukeTheme.colors.surface,
    edgeColor: Color = Color.Transparent,
    edgeThickness: Dp = 0.dp,
    borderColor: Color = Color.Transparent,
    borderThickness: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val safeEdgeThickness = edgeThickness.coerceAtLeast(0.dp)
    val safeBorderThickness = borderThickness.coerceAtLeast(0.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(edgeColor)
            .padding(safeEdgeThickness)
            .clip(shape)
            .background(color)
            .border(safeBorderThickness, borderColor, shape)
            .padding(contentPadding),
        contentAlignment = contentAlignment,
        content = content
    )
}
