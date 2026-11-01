package io.github.nuke.oss.app.tiktok.feature.navigation

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Your own profile page asks for its tab types before it builds them. The chosen types are taken out of that list, in
// a copy, so the page simply has fewer tabs.
@HookEntry
internal class TrimProfileTabs(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(OptionQueries.PROFILE_TABS)
    private val hidden = options.multi(HIDDEN)

    override fun install(scope: FeatureScope) {
        if (hidden.isEmpty()) return
        val tabs = resolver.methods(hostClassLoader, OptionQueries.PROFILE_TABS).single()
        check(tabs.parameterCount == 2 && tabs.parameterTypes[1] == List::class.java) {
            "${tabs.declaringClass.name}.${tabs.name} does not take (user, List)"
        }
        tabs.isAccessible = true
        scope.hooks.install(tabs, HookSpec(
            id = "TrimTabs",
            requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE),
            before = { call ->
                val types = call.argument(1) as? List<*>
                if (types != null) {
                    val kept = types.filter { it.toString() !in hidden }
                    if (kept.size != types.size) call.setArgument(1, ArrayList(kept))
                }
            },
        ))
    }

    companion object : TikTokFeature {
        const val ID = "TrimProfileTabs"
        const val HIDDEN = "hidden"
        // The tab type numbers the host uses.
        val TABS = listOf(
            0, 2, 3, 4, 5, 7, 8, 9, 10, 13, 14, 15, 16, 17, 18, 19, 20, 22, 23, 24, 25, 29, 30, 32, 33, 38, 39, 45, 100, 101, 9999, -1,
        ).map { it.toString() }
        val OPTIONS = listOf(MultiOption(HIDDEN, TABS))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(TrimProfileTabs(resolver, hostClassLoader, options(ID, OPTIONS)))
        }
    }
}
