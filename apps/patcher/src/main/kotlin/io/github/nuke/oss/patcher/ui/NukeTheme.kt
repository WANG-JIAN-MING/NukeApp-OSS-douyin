package io.github.nuke.oss.patcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nuke.oss.patcher.R

// NukeApp-OSS keeps a mirrored copy of these tokens: the two APKs share no project dependency.
// One blue accent, white/parchment/near-black surfaces, hairline borders, 400/600 weights, capsule actions.
// Secondary text uses #6E6E73 rather than the spec's #7A7A7A so small text keeps a 4.5:1 contrast on white.
// inverseSurface is the dark tile that alternates with light ones; inversePrimary is the link blue on that tile.
private val LightColors = lightColorScheme(
    primary = Color(0xFF0066CC), onPrimary = Color.White,
    primaryContainer = Color(0xFFE5EFFA), onPrimaryContainer = Color(0xFF003A75),
    secondary = Color(0xFF333333), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8E8ED), onSecondaryContainer = Color(0xFF1D1D1F),
    tertiary = Color(0xFF248A3D), onTertiary = Color.White,
    background = Color(0xFFF5F5F7), onBackground = Color(0xFF1D1D1F),
    surface = Color.White, onSurface = Color(0xFF1D1D1F),
    surfaceContainerLow = Color(0xFFFAFAFC), surfaceContainer = Color(0xFFF5F5F7), surfaceContainerHigh = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFFF5F5F7), onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFF7A7A7A), outlineVariant = Color(0xFFE0E0E0),
    inverseSurface = Color(0xFF272729), inverseOnSurface = Color.White, inversePrimary = Color(0xFF2997FF),
    error = Color(0xFFB42318), onError = Color.White,
    errorContainer = Color(0xFFFDECEA), onErrorContainer = Color(0xFF7A1A12),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF0071E3), onPrimary = Color.White,
    primaryContainer = Color(0xFF0A2A4D), onPrimaryContainer = Color(0xFFCFE3FA),
    secondary = Color(0xFFD2D2D7), onSecondary = Color(0xFF1D1D1F),
    secondaryContainer = Color(0xFF333336), onSecondaryContainer = Color(0xFFF5F5F7),
    tertiary = Color(0xFF30D158), onTertiary = Color(0xFF00210B),
    background = Color.Black, onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF1C1C1E), onSurface = Color(0xFFF5F5F7),
    surfaceContainerLow = Color(0xFF252527), surfaceContainer = Color(0xFF272729), surfaceContainerHigh = Color(0xFF333336),
    surfaceVariant = Color(0xFF272729), onSurfaceVariant = Color(0xFFA1A1A6),
    outline = Color(0xFF86868B), outlineVariant = Color(0xFF38383A),
    inverseSurface = Color(0xFF2A2A2C), inverseOnSurface = Color(0xFFF5F5F7), inversePrimary = Color(0xFF2997FF),
    error = Color(0xFFFF8A80), onError = Color(0xFF3B0A06),
    errorContainer = Color(0xFF3B1714), onErrorContainer = Color(0xFFFFD7D2),
)

private val Sans = FontFamily.SansSerif

private val NukeTypography = Typography(
    displaySmall = TextStyle(fontFamily = Sans, fontSize = 40.sp, lineHeight = 44.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.28).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = 0.2.sp),
    titleLarge = TextStyle(fontFamily = Sans, fontSize = 21.sp, lineHeight = 25.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.23.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 21.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.37).sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 25.sp, letterSpacing = (-0.37).sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.22).sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = (-0.12).sp),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.37).sp),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.22).sp),
)

// Package names, versions and digests read as machine values, so they share one monospace style.
internal val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)

// The capsule is reserved for actions and action-like chips.
internal val PillShape = RoundedCornerShape(percent = 50)

@Composable
internal fun NukeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = NukeTypography,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(11.dp),
            large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(18.dp),
        ),
        content = content,
    )
}

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun BrandMark(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(22.dp)) {
        val radius = size.minDimension / 2
        drawCircle(color, radius = radius - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        drawCircle(color, radius = radius * 0.42f)
    }
}

@Composable
internal fun StatusDot(color: Color, filled: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier.size(10.dp)) {
        val center = Offset(size.width / 2, size.height / 2)
        if (filled) drawCircle(color, center = center)
        else drawCircle(color, radius = size.minDimension / 2 - 0.75.dp.toPx(), center = center,
            style = Stroke(1.5.dp.toPx()))
    }
}

@Composable
internal fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SectionTitle(title: String, modifier: Modifier = Modifier) {
    Text(title, modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

@Composable
internal fun SecondaryText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun Disclosure(title: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val description = stringResource(if (expanded) R.string.manager_expanded else R.string.manager_collapsed)
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }
}

@Composable
internal fun ErrorPanel(message: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(MaterialTheme.colorScheme.error, filled = true)
                Text(stringResource(R.string.manager_failure_title), Modifier.padding(start = 10.dp),
                    style = MaterialTheme.typography.titleMedium)
            }
            SelectionContainer { Text(message, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
