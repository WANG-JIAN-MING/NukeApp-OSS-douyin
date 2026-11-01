package io.github.nuke.oss.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Visual language follows the Nuke design system: one sakura-pink accent, a grey page behind white (or near-black)
// grouped cards, squircle corners, no borders. The design system library is not a dependency of this APK, so the
// tokens, shape and components are carried here. NukePatcher keeps its own copy of NukeTheme.kt.
@Immutable
data class NukeColors(
    val isLight: Boolean,
    val background: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val accent: Color,
    // The accent is too light for small text on white, so text and links use this darker step in light mode.
    val accentText: Color,
    val danger: Color,
    val success: Color,
    // Something that is not running but is not broken either, such as a feature held back until a restart.
    val warning: Color,
    val sliderThumb: Color,
)

// The accent presets the appearance screen offers. The first is the default.
object NukeAccents {
    val SakuraPink = Color(0xFFEC4899)
    val Rose = Color(0xFFF43F5E)
    val Orange = Color(0xFFF97316)
    val Amber = Color(0xFFF59E0B)
    val Green = Color(0xFF22C55E)
    val Teal = Color(0xFF14B8A6)
    val Sky = Color(0xFF0EA5E9)
    val Blue = Color(0xFF3B82F6)
    val Indigo = Color(0xFF6366F1)
    val Violet = Color(0xFF8B5CF6)
    val Purple = Color(0xFFA855F7)
    val Slate = Color(0xFF64748B)

    val Presets = listOf(SakuraPink, Rose, Orange, Amber, Green, Teal, Sky, Blue, Indigo, Violet, Purple, Slate)
}

enum class NukeThemeMode { System, Light, Dark }

// The default accent keeps its hand-tuned text steps; any other accent derives them, darker on white and lighter on
// near-black, so small accent text stays readable whichever preset is chosen.
private fun accentTextFor(accent: Color, isLight: Boolean): Color = when {
    accent == NukeAccents.SakuraPink -> if (isLight) Color(0xFFBE185D) else Color(0xFFF472B6)
    isLight -> lerp(accent, Color.Black, 0.35f)
    else -> lerp(accent, Color.White, 0.3f)
}

private fun lightNukeColors(accent: Color) = NukeColors(
    isLight = true,
    background = Color(0xFFF4F4F6), surface = Color.White, border = Color(0xFFEFEFEF),
    textPrimary = Color(0xFF1A1A1A), textSecondary = Color(0xFF757575),
    accent = accent, accentText = accentTextFor(accent, true),
    danger = Color(0xFFB42318), success = Color(0xFF15803D), warning = Color(0xFFB45309),
    sliderThumb = Color(0xFFE7E7EC),
)

private fun darkNukeColors(accent: Color) = NukeColors(
    isLight = false,
    background = Color(0xFF0A0A0A), surface = Color(0xFF161616), border = Color(0xFF242424),
    textPrimary = Color.White, textSecondary = Color(0xFF888888),
    accent = accent, accentText = accentTextFor(accent, false),
    danger = Color(0xFFFF8A80), success = Color(0xFF4ADE80), warning = Color(0xFFFBBF24),
    sliderThumb = Color(0xFFF4F4F7),
)

private val LightNukeColors = lightNukeColors(NukeAccents.SakuraPink)

private val LocalNukeColors = staticCompositionLocalOf { LightNukeColors }

private val LocalClickHaptic = staticCompositionLocalOf { false }

object NukeTheme {
    val colors: NukeColors
        @Composable @ReadOnlyComposable get() = LocalNukeColors.current

    // Haptics on clicks are opt-in, switched by the appearance screen.
    val clickHapticEnabled: Boolean
        @Composable @ReadOnlyComposable get() = LocalClickHaptic.current
}

// Material components that remain (dialogs, progress) read the same tokens through this mapping.
private fun NukeColors.toScheme() = (if (isLight) lightColorScheme() else darkColorScheme()).copy(
    primary = accent, onPrimary = Color.White,
    primaryContainer = accent.copy(alpha = 0.16f), onPrimaryContainer = accentText,
    background = background, onBackground = textPrimary,
    surface = surface, onSurface = textPrimary,
    surfaceVariant = background, onSurfaceVariant = textSecondary,
    surfaceContainer = surface, surfaceContainerHigh = surface, surfaceContainerHighest = background,
    outline = textSecondary, outlineVariant = border,
    error = danger, onError = Color.White,
    errorContainer = danger.copy(alpha = 0.12f), onErrorContainer = danger,
)

private val Sans = FontFamily.SansSerif

private val NukeTypography = Typography(
    titleLarge = TextStyle(fontFamily = Sans, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 17.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Bold, letterSpacing = 0.7.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
)

// Package names, versions and digests read as machine values, so they share one monospace style.
val CodeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)

@Composable
fun NukeTheme(
    mode: NukeThemeMode = NukeThemeMode.System,
    accent: Color = NukeAccents.SakuraPink,
    clickHaptic: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        NukeThemeMode.System -> isSystemInDarkTheme()
        NukeThemeMode.Light -> false
        NukeThemeMode.Dark -> true
    }
    val colors = remember(dark, accent) { if (dark) darkNukeColors(accent) else lightNukeColors(accent) }
    CompositionLocalProvider(LocalNukeColors provides colors, LocalClickHaptic provides clickHaptic) {
        MaterialTheme(
            colorScheme = colors.toScheme(),
            typography = NukeTypography,
            shapes = Shapes(
                small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(16.dp), extraLarge = RoundedCornerShape(20.dp),
            ),
            content = content,
        )
    }
}
