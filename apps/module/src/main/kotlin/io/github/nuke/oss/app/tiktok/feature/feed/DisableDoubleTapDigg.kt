package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.TikTokQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Skips the feed double-tap handler, so a double tap no longer likes the video.
@HookEntry
internal class DisableDoubleTapDigg(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(TikTokQueries.DOUBLE_DIGG)

    override fun install(scope: FeatureScope) {
        val (doubleDigg) = resolver.methods(hostClassLoader, TikTokQueries.DOUBLE_DIGG)
        scope.hooks.install(doubleDigg, HookSpec(
            id = "BlockDoubleTapDigg",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { it.returnEarly(null) },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "DisableDoubleTapDigg"

        override val enabledByDefault = true
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DisableDoubleTapDigg(resolver, hostClassLoader))
        }
    }
}
