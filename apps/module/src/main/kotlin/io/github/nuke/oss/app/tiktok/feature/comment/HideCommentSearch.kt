package io.github.nuke.oss.app.tiktok.feature.comment

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Hides the related-search strip in the comment panel by skipping the method that builds it.
@HookEntry
internal class HideCommentSearch(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "HideCommentSearch"
    override val dexQueries = listOf(CommentQueries.RELATED_SEARCH)

    override fun install(scope: FeatureScope) {
        val strip = resolver.methods(hostClassLoader, CommentQueries.RELATED_SEARCH).single()
        check(strip.returnType == Void.TYPE) { "${strip.declaringClass.name}.${strip.name} returns ${strip.returnType.name}" }
        strip.isAccessible = true
        scope.hooks.install(strip, HookSpec(
            id = "SkipRelatedSearch",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { it.returnEarly(null) },
        ))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideCommentSearch(resolver, hostClassLoader))
        }
    }
}
