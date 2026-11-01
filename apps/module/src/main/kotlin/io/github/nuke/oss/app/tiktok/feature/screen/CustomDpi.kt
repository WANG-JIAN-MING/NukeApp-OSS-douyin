package io.github.nuke.oss.app.tiktok.feature.screen

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.util.DisplayMetrics
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.NumberOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The display values a density change produces.
internal data class DisplayDensity(
    val densityDpi: Int,
    val density: Float,
    val scaledDensity: Float,
    val screenWidthDp: Int,
    val screenHeightDp: Int,
    val smallestScreenWidthDp: Int,
)

// Recomputes the dp sizes of a screen for a new density. Sizes that are not set (zero or less) stay as they are.
internal fun densityFor(
    dpi: Int, density: Float, densityDpi: Int, fontScale: Float,
    screenWidthDp: Int, screenHeightDp: Int, smallestScreenWidthDp: Int,
): DisplayDensity {
    val current = if (density > 0f) density else if (densityDpi > 0) densityDpi / 160f else 1f
    val target = dpi / 160f
    val ratio = current / target
    fun scale(size: Int) = if (size <= 0) size else Math.round(size * ratio)
    return DisplayDensity(
        densityDpi = dpi,
        density = target,
        scaledDensity = target * (if (fontScale > 0f) fontScale else 1f),
        screenWidthDp = scale(screenWidthDp),
        screenHeightDp = scale(screenHeightDp),
        smallestScreenWidthDp = scale(smallestScreenWidthDp),
    )
}

// Applies a density to a host Resources object in place.
internal fun interface ResourcesAdjuster {
    fun adjust(resources: Any, dpi: Int)
}

private val androidAdjuster = ResourcesAdjuster { resources, dpi ->
    val target = resources as Resources
    val configuration = Configuration(target.configuration)
    val metrics = DisplayMetrics().apply { setTo(target.displayMetrics) }
    val applied = densityFor(
        dpi, metrics.density, configuration.densityDpi, configuration.fontScale,
        configuration.screenWidthDp, configuration.screenHeightDp, configuration.smallestScreenWidthDp,
    )
    configuration.densityDpi = applied.densityDpi
    configuration.screenWidthDp = applied.screenWidthDp
    configuration.screenHeightDp = applied.screenHeightDp
    configuration.smallestScreenWidthDp = applied.smallestScreenWidthDp
    metrics.densityDpi = applied.densityDpi
    metrics.density = applied.density
    metrics.scaledDensity = applied.scaledDensity
    target.updateConfiguration(configuration, metrics)
}

// Forces the screen density Douyin runs at. The host re-applies its display configuration with a method taking the
// Resources and a Context; the density is written into that Resources before it runs. Zero keeps the host density.
@HookEntry
internal class CustomDpi(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val adjuster: ResourcesAdjuster = androidAdjuster,
    private val resourcesType: Class<*> = Resources::class.java,
    private val contextType: Class<*> = Context::class.java,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.DISPLAY_CONFIG)
    private val dpi = options.number(DPI).toInt()

    override fun install(scope: FeatureScope) {
        if (dpi == 0) return
        // The screen breaks well before this; the option's own range cannot say "zero or at least 170".
        check(dpi >= MIN_DPI) { "DPI must be 0 or at least $MIN_DPI, not $dpi" }
        val anchor = resolver.methods(hostClassLoader, OptionQueries.DISPLAY_CONFIG).single()
        val owner = anchor.declaringClass
        val configurators = owner.resolve().optional(silent = true).method {
            isSynthetic = false
            returnType = Void.TYPE
            parameters { types ->
                types.size == 2 &&
                    ((types[0] == resourcesType && types[1] == contextType) || (types[0] == contextType && types[1] == resourcesType))
            }
        }.map { it.self }
        check(configurators.size == 1) { "Expected one (Resources, Context) method in ${owner.name}, found ${configurators.size}" }
        val configurator = configurators.single()
        val resourcesAt = configurator.parameterTypes.indexOf(resourcesType)
        configurator.isAccessible = true
        scope.hooks.install(configurator, HookSpec(
            id = "ForceDensity",
            before = { call -> call.argument(resourcesAt)?.let { adjuster.adjust(it, dpi) } },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "CustomDpi"
        const val DPI = "dpi"
        const val MIN_DPI = 170
        val OPTIONS = listOf(NumberOption(DPI, default = 0, min = 0, max = 1000))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(CustomDpi(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
