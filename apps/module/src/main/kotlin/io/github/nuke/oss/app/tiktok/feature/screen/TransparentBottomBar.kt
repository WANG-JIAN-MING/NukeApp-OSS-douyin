package io.github.nuke.oss.app.tiktok.feature.screen

import android.graphics.drawable.Drawable
import android.view.View
import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Hides a host View. It runs inside the synchronous Hook callback on the thread the host called from.
internal fun interface ViewHider {
    fun hide(view: Any)
}

private val goneHider = ViewHider { (it as View).visibility = View.GONE }

// Lets a full-screen video show through the bottom tab bar: the bar keeps no background, and the spacer the main
// page puts under it stays hidden whatever visibility the host asks for.
@HookEntry
internal class TransparentBottomBar(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val hider: ViewHider = goneHider,
    private val viewType: Class<*> = View::class.java,
    private val drawableType: Class<*> = Drawable::class.java,
) : RuntimeFeature, UsesDexQueries {
    override val id = "TransparentBottomBar"
    override val dexQueries = listOf(FeedQueries.BOTTOM_TAB_BAR, FeedQueries.BOTTOM_SPACE)

    override fun install(scope: FeatureScope) {
        val (bar, space) = resolver.methods(hostClassLoader, FeedQueries.BOTTOM_TAB_BAR, FeedQueries.BOTTOM_SPACE)
        // Only a setBackground the bar declares itself is hooked, never View's own.
        val background = bar.declaringClass.resolve().optional(silent = true).method {
            name = "setBackground"
            parameters(drawableType)
        }.singleOrNull()?.self ?: error("${bar.declaringClass.name} declares no setBackground(${drawableType.simpleName})")
        check(viewType.isAssignableFrom(space.declaringClass)) { "${space.declaringClass.name} is not a ${viewType.simpleName}" }
        // A spacer that does not override setLayoutParams has nothing to hook there.
        val layouts = space.declaringClass.resolve().optional(silent = true).method {
            name = "setLayoutParams"
            isSynthetic = false
            isBridge = false
        }.map { it.self }
        for (method in listOf(background, space) + layouts) method.isAccessible = true
        scope.hooks.install(background, HookSpec(
            id = "SkipBarBackground",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { it.returnEarly(null) },
        ))
        scope.hooks.install(space, HookSpec(
            id = "KeepSpaceHidden",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val receiver = call.receiver
                if (receiver != null && call.argument(0) != View.GONE) {
                    hider.hide(receiver)
                    call.returnEarly(null)
                }
            },
        ))
        layouts.forEachIndexed { index, method ->
            scope.hooks.install(method, HookSpec(id = "HideSpaceOnLayout$index", before = { call -> call.receiver?.let(hider::hide) }))
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(TransparentBottomBar(resolver, hostClassLoader))
        }
    }
}
