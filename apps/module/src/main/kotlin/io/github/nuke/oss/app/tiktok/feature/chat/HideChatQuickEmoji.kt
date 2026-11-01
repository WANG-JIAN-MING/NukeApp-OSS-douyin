package io.github.nuke.oss.app.tiktok.feature.chat

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.declaredMethodsNamed
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// The quick-send emoji row is built from a list its adapter feed receives. Emptying that list and skipping the
// feed leaves the row with nothing to show.
@HookEntry
internal class HideChatQuickEmoji(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "HideChatQuickEmoji"
    override val dexQueries = listOf(ChatQueries.QUICK_EMOJI)

    override fun install(scope: FeatureScope) {
        val feed = resolver.methods(hostClassLoader, ChatQueries.QUICK_EMOJI).single()
        // Only overloads that take the list first and can return nothing; others are not the feed.
        val receivers = feed.declaringClass.declaredMethodsNamed(feed.name).filter { method ->
            val first = method.parameterTypes.firstOrNull()
            first != null && (first == Any::class.java || List::class.java.isAssignableFrom(first)) &&
                (method.returnType == Void.TYPE || !method.returnType.isPrimitive)
        }
        check(receivers.isNotEmpty()) { "No list-taking ${feed.name} in ${feed.declaringClass.name}" }
        receivers.forEachIndexed { index, method ->
            scope.hooks.install(method, HookSpec(
                id = "EmptyEmoji$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { call ->
                    val list = call.argument(0) as? MutableList<*>
                    if (list != null) {
                        list.clear()
                        call.returnEarly(null)
                    }
                },
            ))
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideChatQuickEmoji(resolver, hostClassLoader))
        }
    }
}
