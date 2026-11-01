package io.github.nuke.oss.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.BlurredEdgeTreatment
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The grouped surface every block of content sits on. Colour only, no border: depth comes from the page behind it.
// Over a custom home background it takes the configured card opacity and, if enabled, blurs what is behind it.
@Composable
fun NukeCardSurface(
    modifier: Modifier = Modifier,
    shape: Shape = NukeSquircleShape(16.dp),
    color: Color = NukeTheme.colors.surface,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val card = NukeCardTheme.context
    val haze = card.hazeState
    val blur = if (card.config.enabled && card.config.blurEnabled && haze != null) {
        Modifier.clip(shape)
            .hazeBlur(
                input = HazeInput.Sources(haze),
                style = HazeBlurStyle {
                    blurRadius(18.dp)
                    noiseFactor(0f)
                    blurredEdgeTreatment(BlurredEdgeTreatment(shape))
                },
                expandLayerBounds = false,
            )
            .clip(shape)
    } else Modifier
    NukeFluidSurface(
        modifier = modifier.then(blur),
        shape = shape,
        color = color.copy(alpha = color.alpha * NukeCardTheme.cardAlpha),
        contentPadding = contentPadding,
        content = content,
    )
}

// A settings row that opens something: optional leading icon, status text, then a chevron. onClickAt also reports
// the touch point, which the circular reveal grows from.
@Composable
fun NukeNavRow(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickAt: ((Offset) -> Unit)? = null,
    subtitle: String? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    statusText: String? = null,
    statusColor: Color? = null,
    enabled: Boolean = true,
) {
    NukeSettingRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        leadingIcon = leadingIcon,
        enabled = enabled,
        onClick = onClick,
        onClickAt = onClickAt,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (statusText != null) {
                    BasicText(statusText, style = TextStyle(color = statusColor ?: NukeTheme.colors.textSecondary,
                        fontSize = 14.sp, fontWeight = FontWeight.Normal))
                    Spacer(Modifier.width(8.dp))
                }
                NukeNavChevron(enabled)
            }
        },
    )
}

@Composable
fun NukeNavChevron(enabled: Boolean = true) {
    val alpha = if (enabled) 0.62f else 0.36f
    CompositionLocalProvider(LocalContentColor provides NukeTheme.colors.textSecondary.copy(alpha = alpha)) {
        GlyphIcon(Glyph.ChevronRight, null, iconSize = 18.dp)
    }
}

// Rows with a leading icon badge start their separator after it.
@Composable
fun NukeDivider(startPadding: Dp = 14.dp) {
    val border = NukeTheme.colors.border
    Box(Modifier.fillMaxWidth().padding(start = startPadding).height(1.dp)
        .background(border.copy(alpha = border.alpha * NukeCardTheme.controlAlpha)))
}

// 48dp touch target around a 24dp glyph, for actions in a title bar.
@Composable
fun NukeBarButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.size(48.dp).clip(CircleShape).nukeClick(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        CompositionLocalProvider(LocalContentColor provides NukeTheme.colors.textPrimary, content = content)
    }
}

@Composable
fun StatusDot(color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        if (filled) drawCircle(color, center = center)
        else drawCircle(color, radius = size.minDimension / 2 - 0.75.dp.toPx(), center = center,
            style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = NukeTheme.colors.textSecondary)
}

@Composable
fun CodeText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = CodeStyle, color = NukeTheme.colors.textSecondary)
}
