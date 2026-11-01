package io.github.nuke.oss.app.tiktok.feature.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Tapping a video's description opens a list of related videos in the feed adapter. That adapter reports no items,
// so the list never fills. The home feed's own recommendations and search suggestions use other adapters.
@HookEntry
internal class HideRelatedRecommendations(private val hostClassLoader: ClassLoader) : RuntimeFeature {
    override val id = "HideRelatedRecommendations"

    override fun install(scope: FeatureScope) {
        val itemCount = FEED_ADAPTER.toClass(hostClassLoader).resolve().firstMethod { name = "getItemCount"; emptyParameters() }.self
        check(itemCount.returnType == Int::class.javaPrimitiveType) { "$FEED_ADAPTER.getItemCount returns ${itemCount.returnType.name}" }
        itemCount.isAccessible = true
        scope.hooks.install(itemCount, HookSpec(
            id = "EmptyRelated",
            requiredCapabilities = setOf(HookCapability.OUTCOME_REWRITE),
            after = { call -> if (call.outcome is CallOutcome.Returned) call.returnValue(0) },
        ))
    }

    companion object : TikTokFeature {
        private const val FEED_ADAPTER = "com.ss.android.ugc.aweme.o.c2feed.core.C2Adapter"

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideRelatedRecommendations(hostClassLoader))
        }
    }
}
