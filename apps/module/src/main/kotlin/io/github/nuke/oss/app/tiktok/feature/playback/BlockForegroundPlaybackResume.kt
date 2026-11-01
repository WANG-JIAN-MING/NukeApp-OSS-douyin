package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.KavaRef.Companion.resolve
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

// Drops the feed video event of type 0, which resumes playback when the player surface is rebuilt.
@HookEntry
internal class BlockForegroundPlaybackResume(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "BlockForegroundPlaybackResume"
    override val dexQueries = listOf(TikTokQueries.VIDEO_EVENT)

    override fun install(scope: FeatureScope) {
        val (videoEvent) = resolver.methods(hostClassLoader, TikTokQueries.VIDEO_EVENT)
        val type = videoEvent.parameterTypes.single().resolve().firstField { name = "type"; superclass() }.self.apply { isAccessible = true }
        scope.hooks.install(videoEvent, HookSpec(
            id = "DropResumeEvent",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val event = call.argument(0)
                if (event != null && type.get(event) == RESUME_EVENT) call.returnEarly(null)
            },
        ))
    }

    companion object : TikTokFeature {
        private const val RESUME_EVENT = 0

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(BlockForegroundPlaybackResume(resolver, hostClassLoader))
        }
    }
}
