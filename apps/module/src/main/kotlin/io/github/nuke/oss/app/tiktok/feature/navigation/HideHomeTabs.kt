package io.github.nuke.oss.app.tiktok.feature.navigation

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.MultiOption
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Removes the chosen tabs from the id list the home screen builds. The same builders produce the bottom bar (up to five
// tabs) and the top strip (six or more), so each feature acts on the lists whose size belongs to it. The list the host
// built is left untouched and a new one is returned. If every tab is hidden the last one stays, so the screen never
// ends up empty.
@HookEntry
internal class HideHomeTabs(
    override val id: String,
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    options: OptionValues,
    private val sizes: IntRange,
) : RuntimeFeature, UsesDexQueries {
    override val dexQueries = listOf(OptionQueries.TAB_LISTS)
    private val hidden = options.multi(HIDDEN)

    override fun install(scope: FeatureScope) {
        if (hidden.isEmpty()) return
        val builders = resolver.methodLists(hostClassLoader, OptionQueries.TAB_LISTS).single()
        for (builder in builders) {
            check(builder.parameterCount == 0 && builder.returnType.isAssignableFrom(ArrayList::class.java)) {
                "${builder.declaringClass.name}.${builder.name} is not a list builder"
            }
        }
        builders.forEachIndexed { index, builder ->
            builder.isAccessible = true
            scope.hooks.install(builder, HookSpec(
                id = "HideTabs$index",
                requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
                after = { call ->
                    val tabs = (call.outcome as? CallOutcome.Returned)?.value as? List<*>
                    if (tabs != null && tabs.size in sizes) {
                        val kept = withoutHidden(tabs, hidden)
                        if (kept.size != tabs.size) call.returnValue(kept)
                    }
                },
            ))
        }
    }

    companion object : TikTokFeature {
        const val BOTTOM_ID = "HideHomeBottomTabs"
        const val TOP_ID = "HideHomeTopTabs"
        const val HIDDEN = "hidden"

        // The bottom bar never has more than five tabs; the top strip has six or more.
        val BOTTOM_SIZES = 0..5
        val TOP_SIZES = 6..Int.MAX_VALUE

        // Tab ids as the host names them.
        val BOTTOM_TABS = listOf(
            "homepage_home", "homepage_familiar", "homepage_publish", "homepage_notification", "homepage_profile",
            "homepage_lucky_bag", "homepage_ai_search", "homepage_duoshan_discovery", "homepage_mall", "homepage_theater",
            "homepage_follow", "homepage_nearby",
        )
        val TOP_TABS = listOf(
            "homepage_hot_container", "homepage_mediumvideo", "homepage_hangout", "homepage_tablive", "homepage_nearby",
            "homepage_follow", "homepage_mall", "homepage_groupon", "homepage_pad_hot", "homepage_learn", "homepage_pad_game",
            "homepage_pad_campaign", "homepage_pad_web", "homepage_xtab", "homepage_shopping_lite", "homepage_pad_cinema",
            "homepage_pad_kids_v2", "homepage_novel_lite", "homepage_multiple_content", "homepage_video_hotsoon",
            "homepage_playlet_stream",
        )
        val BOTTOM_OPTIONS = listOf(MultiOption(HIDDEN, BOTTOM_TABS))
        val TOP_OPTIONS = listOf(MultiOption(HIDDEN, TOP_TABS))

        internal fun withoutHidden(tabs: List<*>, hidden: Set<String>): List<Any?> {
            val kept = tabs.filter { it !in hidden }
            return if (kept.isEmpty() && tabs.isNotEmpty()) arrayListOf(tabs.last()) else ArrayList(kept)
        }

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(
                HideHomeTabs(BOTTOM_ID, resolver, hostClassLoader, options(BOTTOM_ID, BOTTOM_OPTIONS), BOTTOM_SIZES),
                HideHomeTabs(TOP_ID, resolver, hostClassLoader, options(TOP_ID, TOP_OPTIONS), TOP_SIZES),
            )
        }
    }
}
