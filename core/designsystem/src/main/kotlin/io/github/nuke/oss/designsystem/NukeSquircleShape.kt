package io.github.nuke.oss.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

// Fluid rounded corners: the straight edge enters the corner earlier, which removes the visible start point of a
// plain rounded rectangle. Higher smoothness gives a more continuous curve; keep it between 1.2 and 1.45.
@Immutable
data class NukeSquircleShape(
    private val radius: Dp,
    private val smoothness: Float = 1.35f
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline {
        val r = with(density) { radius.toPx() }
        val w = size.width
        val h = size.height

        val safeSmoothness = smoothness.coerceIn(MinSmoothness, MaxSmoothness)
        val maxR = minOf(w, h) / 2f
        val finalR = r.coerceIn(0f, maxR)

        val path = Path().apply {
            val maxOffset = minOf(w, h) / 2f
            val offset = (finalR * safeSmoothness).coerceAtMost(maxOffset)
            val controlRatio = BaseControlRatio + (safeSmoothness - MinSmoothness) * ControlSmoothnessWeight
            val control = (finalR * controlRatio).coerceAtMost(offset)

            // start at the top edge, clockwise
            moveTo(offset, 0f)
            lineTo(w - offset, 0f)

            // top-right arc
            cubicTo(w - control, 0f, w, control, w, offset)
            lineTo(w, h - offset)

            // bottom-right arc
            cubicTo(w, h - control, w - control, h, w - offset, h)
            lineTo(offset, h)

            // bottom-left arc
            cubicTo(control, h, 0f, h - control, 0f, h - offset)
            lineTo(0f, offset)

            // top-left arc
            cubicTo(0f, control, control, 0f, offset, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    private companion object {
        private const val MinSmoothness = 1f
        private const val MaxSmoothness = 1.6f
        private const val BaseControlRatio = 0.52f
        private const val ControlSmoothnessWeight = 0.18f
    }
}
