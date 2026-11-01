package io.github.nuke.oss.app.tiktok.feature.feed

import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.TikTokQueries
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Reports follow state 0 (not followed) to the feed avatar as 1, so the follow button under it is not drawn.
// Views already drawn keep their button until the host rebuilds them.
@HookEntry
internal class HideFeedAvatarFollow(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "HideFeedAvatarFollow"
    override val dexQueries = listOf(TikTokQueries.AVATAR_FOLLOW)

    override fun install(scope: FeatureScope) {
        val (avatarFollow) = resolver.methods(hostClassLoader, TikTokQueries.AVATAR_FOLLOW)
        scope.hooks.install(avatarFollow, HookSpec(
            id = "HideFollowButton",
            requiredCapabilities = setOf(HookCapability.ARGUMENT_REWRITE),
            before = { call -> if (call.argument(0) == NOT_FOLLOWED) call.setArgument(0, FOLLOWED) },
        ))
    }

    companion object : TikTokFeature {
        private const val NOT_FOLLOWED = 0
        private const val FOLLOWED = 1

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(HideFeedAvatarFollow(resolver, hostClassLoader))
        }
    }
}
