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

// The host gates a view-once message with a boolean check and a step that records the message as consumed. The check is
// answered false and the record step is skipped, so the message is not retired.
@HookEntry
internal class KeepViewOnceMessages(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "KeepViewOnceMessages"
    override val dexQueries = listOf(ChatQueries.VIEW_ONCE_TEXT, ChatQueries.VIEW_ONCE_MARK)

    override fun install(scope: FeatureScope) {
        val (text, mark) = resolver.methods(hostClassLoader, ChatQueries.VIEW_ONCE_TEXT, ChatQueries.VIEW_ONCE_MARK)
        // Overloads of the same name are hooked as a set, but only those whose return type the answer fits.
        val checks = text.declaringClass.declaredMethodsNamed(text.name).filter { it.returnType == Boolean::class.javaPrimitiveType }
        val marks = mark.declaringClass.declaredMethodsNamed(mark.name).filter { it.returnType == Void.TYPE }
        check(checks.isNotEmpty()) { "No boolean ${text.name} in ${text.declaringClass.name}" }
        check(marks.isNotEmpty()) { "No void ${mark.name} in ${mark.declaringClass.name}" }
        checks.forEachIndexed { index, method ->
            scope.hooks.install(method, HookSpec(
                id = "KeepCheck$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { it.returnEarly(false) },
            ))
        }
        marks.forEachIndexed { index, method ->
            scope.hooks.install(method, HookSpec(
                id = "SkipMark$index",
                requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
                before = { it.returnEarly(null) },
            ))
        }
    }

    companion object : TikTokFeature {
        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(KeepViewOnceMessages(resolver, hostClassLoader))
        }
    }
}
