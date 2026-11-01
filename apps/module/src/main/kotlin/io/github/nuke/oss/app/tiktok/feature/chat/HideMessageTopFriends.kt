package io.github.nuke.oss.app.tiktok.feature.chat

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The strip of friends above the conversation list is an adapter of its own. Reporting zero items removes it.
@HookEntry
internal class HideMessageTopFriends(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "HideMessageTopFriends"
    override val dexQueries = listOf(ChatQueries.MESSAGE_HEADER)

    override fun install(scope: FeatureScope) {
        val header = resolver.methods(hostClassLoader, ChatQueries.MESSAGE_HEADER).single()
        val itemCount = header.declaringClass.resolve().firstMethod { name = "getItemCount"; emptyParameters(); superclass() }.self
        check(itemCount.returnType == Int::class.javaPrimitiveType) { "getItemCount returns ${itemCount.returnType.name}" }
        scope.hooks.install(itemCount, HookSpec(
            id = "EmptyHeader",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { it.returnEarly(0) },
        ))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideMessageTopFriends(resolver, hostClassLoader))
        }
    }
}
