package io.github.nuke.oss.app.tiktok.feature.playback

import android.content.res.Resources
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.ChoiceOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

internal data class ScreenSize(val width: Int, val height: Int)

// The current screen size, or null while it is not known.
internal fun interface ScreenMetrics {
    fun current(): ScreenSize?
}

// No Application Context exists when features install, so the system display metrics stand in for the window's.
private val systemScreen = ScreenMetrics {
    Resources.getSystem().displayMetrics.let { if (it.widthPixels > 0 && it.heightPixels > 0) ScreenSize(it.widthPixels, it.heightPixels) else null }
}

// A video size in the host's argument order: height first, then width.
internal data class VideoSize(val height: Int, val width: Int)

// A video wider than the screen is scaled down to the screen width, so its sides are not cut off.
internal fun fitToWidth(video: VideoSize, screen: ScreenSize): VideoSize? =
    if (video.width <= screen.width) null
    else VideoSize((video.height * (screen.width.toDouble() / video.width)).toInt(), screen.width)

// A portrait video that already takes most of the screen height is stretched to the whole screen. A video clearly
// wider than tall is landscape and stays as it is.
internal fun fillScreen(video: VideoSize, screen: ScreenSize): VideoSize? {
    val band = screen.height / 5
    if (video.width > video.height + band) return null
    return if (video.height > screen.height - band) VideoSize(screen.height, screen.width) else null
}

// Changes how the feed sizes portrait videos. The two report features that do this are mutually exclusive there, so
// here they are the two choices of one option.
@HookEntry
internal class PortraitVideoSize(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val screen: ScreenMetrics = systemScreen,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.VIDEO_RESIZE)
    private val resize: (VideoSize, ScreenSize) -> VideoSize? = when (options.choice(MODE)) {
        FILL -> ::fillScreen
        else -> ::fitToWidth
    }

    override fun install(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, OptionQueries.VIDEO_RESIZE).single()
        val overloads = anchor.declaringClass.declaredMethodsNamed(anchor.name)
        val int = Int::class.javaPrimitiveType!!
        for (method in overloads) {
            val types = method.parameterTypes
            check(types.size >= 2 && types[0] == int && types[1] == int) {
                "${method.declaringClass.name}.${method.name} does not take the height and width first"
            }
        }
        overloads.forEachIndexed { index, method ->
            scope.hooks.install(method, HookSpec(
                id = "ResizeVideo$index",
                requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE),
                before = { call ->
                    val current = screen.current()
                    val video = VideoSize(call.argument(0) as Int, call.argument(1) as Int)
                    val resized = current?.let { resize(video, it) }
                    if (resized != null) {
                        call.setArgument(0, resized.height)
                        call.setArgument(1, resized.width)
                    }
                },
            ))
        }
    }

    companion object : TikTokFeature {
        const val ID = "PortraitVideoSize"
        const val MODE = "mode"
        const val FIT = "fit"
        const val FILL = "fill"
        val OPTIONS = listOf(ChoiceOption(MODE, listOf(FIT, FILL)))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(PortraitVideoSize(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
