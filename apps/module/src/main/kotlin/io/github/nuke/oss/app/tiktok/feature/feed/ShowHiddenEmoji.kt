package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The emoji response marks some entries hidden. After the host reads the list, every entry is marked visible.
@HookEntry
internal class ShowHiddenEmoji(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "ShowHiddenEmoji"
    override val dexQueries = listOf(FeedQueries.EMOJI_ENTRY, FeedQueries.EMOJI_RESPONSE)

    override fun install(scope: FeatureScope) {
        val (entryAnchor, responseAnchor) = resolver.methods(hostClassLoader, FeedQueries.EMOJI_ENTRY, FeedQueries.EMOJI_RESPONSE)
        val entryType = entryAnchor.declaringClass
        val hide = entryType.resolve().firstField { name = "hide"; superclass() }.self.apply { isAccessible = true }
        check(hide.type == Int::class.javaPrimitiveType) { "${entryType.name}.hide is ${hide.type.name}, expected int" }
        val getters = responseAnchor.declaringClass.resolve().optional(silent = true).method {
            isSynthetic = false
            emptyParameters()
            returnType = List::class
        }.map { it.self }
        check(getters.isNotEmpty()) { "No list getter in ${responseAnchor.declaringClass.name}" }
        getters.forEachIndexed { index, getter ->
            getter.isAccessible = true
            scope.hooks.install(getter, HookSpec(
                id = "ShowHidden$index",
                after = { call ->
                    val items = (call.outcome as? CallOutcome.Returned)?.value as? List<*>
                    items?.forEach { if (entryType.isInstance(it)) hide.setInt(it, 0) }
                },
            ))
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(ShowHiddenEmoji(resolver, hostClassLoader))
        }
    }
}
