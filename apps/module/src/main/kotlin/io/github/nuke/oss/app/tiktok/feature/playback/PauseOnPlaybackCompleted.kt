package io.github.nuke.oss.app.tiktok.feature.playback

import com.highcapable.kavaref.KavaRef.Companion.resolve
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.TikTokQueries
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature

// Pauses the current video when the player reports completion instead of letting it loop.
@HookEntry
internal class PauseOnPlaybackCompleted(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "PauseOnPlaybackCompleted"
    override val dexQueries = listOf(TikTokQueries.PLAYER_EVENT, TikTokQueries.PAUSE)

    override fun install(scope: FeatureScope) {
        val (playerEvent, pause) = resolver.methods(hostClassLoader, TikTokQueries.PLAYER_EVENT, TikTokQueries.PAUSE)
        val status = playerEvent.parameterTypes.single().resolve().firstMethod {
            name = "getStatus"
            emptyParameters()
            superclass()
        }.self.apply { isAccessible = true }
        pause.isAccessible = true
        scope.hooks.install(playerEvent, HookSpec(
            id = "PauseOnCompleted",
            after = { call ->
                val event = call.argument(0)
                // A regular call of the panel's pause handler, made on the player event thread.
                if (event != null && status.invoke(event) == COMPLETED) pause.invoke(call.receiver, true)
            },
        ))
    }

    companion object : TikTokFeature {
        private const val COMPLETED = 7

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(PauseOnPlaybackCompleted(resolver, hostClassLoader))
        }
    }
}
