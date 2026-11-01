package io.github.nuke.oss.app.tiktok.feature.ads

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Drops the ad entries from a short drama episode list before the host uses it. The entries carry an is_ads flag,
// so the list the host keeps no longer contains the ad slots that gate the next episode.
@HookEntry
internal class SkipShortDramaAds(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "RemoveShortDramaAds"
    override val dexQueries = listOf(FeedQueries.SHORT_DRAMA_FEED)
    private val adFlag = NamedField("is_ads")

    override fun install(scope: FeatureScope) {
        val loader = resolver.methods(hostClassLoader, FeedQueries.SHORT_DRAMA_FEED).single()
        val listReceivers = loader.declaringClass.resolve().optional(silent = true).method {
            isSynthetic = false
            returnType = Void.TYPE
            parameters(List::class, Boolean::class)
        }.map { it.self }
        check(listReceivers.isNotEmpty()) { "No (List, boolean) receiver in ${loader.declaringClass.name}" }
        listReceivers.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "DropAds$index", before = { call -> dropAds(call.argument(0)) }))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun dropAds(argument: Any?) {
        val items = argument as? MutableList<Any?> ?: return
        items.removeAll { item ->
            val flag = item?.let(adFlag::of)?.takeIf { it.type == Boolean::class.javaPrimitiveType }
            flag != null && flag.getBoolean(item)
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(SkipShortDramaAds(resolver, hostClassLoader))
        }
    }
}
