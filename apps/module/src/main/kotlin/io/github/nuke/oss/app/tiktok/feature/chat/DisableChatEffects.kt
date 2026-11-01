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

// The effects component sets itself up when its view is created. Skipping that leaves it inert, so a trigger word
// plays nothing. Hosts that name the setup initHandlers instead of onViewCreated get that method skipped.
@HookEntry
internal class DisableChatEffects(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "DisableChatEffects"
    override val dexQueries = listOf(ChatQueries.CHAT_EFFECTS)

    override fun install(scope: FeatureScope) {
        val anchor = resolver.methods(hostClassLoader, ChatQueries.CHAT_EFFECTS).single()
        val owner = anchor.declaringClass
        val members = owner.resolve().optional(silent = true)
        val setup = members.method { name = "onViewCreated"; parameterCount = 1; isSynthetic = false }.singleOrNull()?.self
            ?: members.method { name = "initHandlers"; emptyParameters(); isSynthetic = false }.singleOrNull()?.self
        checkNotNull(setup) { "No onViewCreated(view) or initHandlers() in ${owner.name}" }
        check(setup.returnType == Void.TYPE) { "${setup.name} returns ${setup.returnType.name}" }
        setup.isAccessible = true
        scope.hooks.install(setup, HookSpec(
            id = "SkipEffectsSetup",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { it.returnEarly(null) },
        ))
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DisableChatEffects(resolver, hostClassLoader))
        }
    }
}
