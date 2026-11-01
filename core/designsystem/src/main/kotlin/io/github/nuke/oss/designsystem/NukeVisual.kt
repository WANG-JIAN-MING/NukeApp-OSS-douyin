package io.github.nuke.oss.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

// How the cards on top of a custom home background are drawn.
@Immutable
data class NukeContentSurfaceConfig(
    val enabled: Boolean = true,
    val alpha: Float = 1f,
    val blurEnabled: Boolean = false,
)

// The renderer-agnostic settings of the home visual layer. The background image itself is supplied by the caller.
@Immutable
data class NukeVisualConfig(
    val hasBackground: Boolean = false,
    val backgroundBlurRadius: Float = 0f,
    val scrimAlpha: Float = 0f,
    val content: NukeContentSurfaceConfig = NukeContentSurfaceConfig(),
)

// Theme-only compensation for custom backgrounds. Nothing is persisted and no pixels are inspected: the same user
// values just keep dark surfaces from looking too bright over a bright image.
object NukeVisualTonePolicy {
    private const val DarkScrimMultiplier = 1.25f
    private const val DarkCardOpacityCompensation = 0.12f

    fun scrimAlpha(alpha: Float, isLight: Boolean): Float {
        val base = alpha.coerceIn(0f, 1f)
        if (isLight || base == 0f) return base
        return (base * DarkScrimMultiplier).coerceAtMost(1f)
    }

    fun cardAlpha(alpha: Float, enabled: Boolean, isLight: Boolean): Float {
        if (!enabled) return 1f
        val base = alpha.coerceIn(0f, 1f)
        if (isLight) return base
        // Move part of the remaining transparency toward an opaque card.
        return base + (1f - base) * DarkCardOpacityCompensation
    }
}

internal data class NukeCardContext(
    val config: NukeContentSurfaceConfig = NukeContentSurfaceConfig(),
    val hazeState: HazeState? = null,
)

private val LocalCardContext = compositionLocalOf { NukeCardContext() }
private val LocalBackdropHaze = compositionLocalOf<HazeState?> { null }

object NukeCardTheme {
    internal val context: NukeCardContext
        @Composable @ReadOnlyComposable get() = LocalCardContext.current

    // Effective alpha of the card surface itself.
    val cardAlpha: Float
        @Composable @ReadOnlyComposable get() = context.config.let {
            NukeVisualTonePolicy.cardAlpha(it.alpha, it.enabled, NukeTheme.colors.isLight)
        }

    // Separators and small control surfaces stay more legible than the card.
    val controlAlpha: Float
        @Composable @ReadOnlyComposable get() = (cardAlpha + 0.3f).coerceAtMost(1f)
}

// Draws the page base colour, then the background image with its blur and scrim, then the content on top. Cards
// inside read the content settings and blur what is behind them through the shared haze state.
@Composable
fun NukeVisualRoot(
    config: NukeVisualConfig,
    modifier: Modifier = Modifier,
    background: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit,
) {
    val haze = rememberHazeState()
    val cards = if (config.hasBackground) config.content else NukeContentSurfaceConfig()
    NukeVisualScene(config, modifier, haze, background) {
        CompositionLocalProvider(LocalBackdropHaze provides haze) {
            CompositionLocalProvider(LocalCardContext provides NukeCardContext(cards, haze)) {
                Box(Modifier.matchParentSize(), content = content)
            }
        }
    }
}

// One scene snapshot; the circular reveal also draws it under the page that grows over the home list.
@Composable
fun NukeVisualScene(
    config: NukeVisualConfig,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    background: @Composable BoxScope.() -> Unit = {},
    content: @Composable BoxScope.() -> Unit = {},
) {
    val scrim = NukeVisualTonePolicy.scrimAlpha(config.scrimAlpha, NukeTheme.colors.isLight)
    val blur = config.backgroundBlurRadius.coerceAtLeast(0f)
    Box(modifier.fillMaxSize().background(NukeTheme.colors.background)) {
        if (config.hasBackground) {
            Box(
                Modifier.matchParentSize()
                    .then(if (hazeState != null) Modifier.hazeSource(hazeState) else Modifier)
                    .then(if (blur > 0f) {
                        Modifier.hazeBlur(
                            input = HazeInput.Content,
                            style = HazeBlurStyle { blurRadius(blur.dp); noiseFactor(0f) },
                        )
                    } else Modifier),
            ) {
                Box(Modifier.matchParentSize(), content = background)
                if (scrim > 0f) Box(Modifier.matchParentSize().background(NukeTheme.colors.background.copy(alpha = scrim)))
            }
        }
        Box(Modifier.matchParentSize(), content = content)
    }
}
