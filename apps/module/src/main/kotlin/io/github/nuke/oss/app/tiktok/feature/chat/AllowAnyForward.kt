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

// Offers "forward" on every chat message. The forward action's class has one boolean check, under one name, deciding
// whether the action applies to a message; every overload of it answers yes.
@HookEntry
internal class AllowAnyForward(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "AllowAnyForward"
    override val dexQueries = listOf(ChatQueries.FORWARD_ACTION)

    override fun install(scope: FeatureScope) {
        val action = resolver.methods(hostClassLoader, ChatQueries.FORWARD_ACTION).single().declaringClass
        val boolean = Boolean::class.javaPrimitiveType
        val checks = action.resolve().optional(silent = true).method {
            returnType = boolean
            isSynthetic = false
            isBridge = false
        }.map { it.self }
        val names = checks.map { it.name }.toSet()
        check(names.size == 1) { "Expected one boolean check in ${action.name}, found ${names.size}" }
        checks.forEachIndexed { index, method ->
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(
                id = "AllowForward$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { it.returnEarly(true) },
            ))
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(AllowAnyForward(resolver, hostClassLoader))
        }
    }
}
