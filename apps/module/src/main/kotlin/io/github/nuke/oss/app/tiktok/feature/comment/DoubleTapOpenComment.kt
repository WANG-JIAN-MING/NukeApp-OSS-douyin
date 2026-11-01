package io.github.nuke.oss.app.tiktok.feature.comment

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

// After a feed double tap, posts the video event that opens the comment panel for the current video.
@HookEntry
internal class DoubleTapOpenComment(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
) : RuntimeFeature, UsesDexQueries {
    override val id = "DoubleTapOpenComment"
    override val dexQueries = listOf(TikTokQueries.DOUBLE_TAP, TikTokQueries.CURRENT_AWEME, TikTokQueries.VIDEO_EVENT)

    override fun install(scope: FeatureScope) {
        val (doubleTap, currentAweme, videoEvent) = resolver.methods(
            hostClassLoader, TikTokQueries.DOUBLE_TAP, TikTokQueries.CURRENT_AWEME, TikTokQueries.VIDEO_EVENT,
        )
        val eventType = videoEvent.parameterTypes.single()
        val constructors = eventType.resolve().optional(silent = true).constructor {
            parameters { it.size == 2 && it[0] == Int::class.javaPrimitiveType && it[1].isAssignableFrom(currentAweme.returnType) }
        }.map { it.self }
        check(constructors.size == 1) { "Expected one (int, Aweme) constructor in ${eventType.name}, found ${constructors.size}" }
        val newEvent = constructors.single().apply { isAccessible = true }
        currentAweme.isAccessible = true
        videoEvent.isAccessible = true
        scope.hooks.install(doubleTap, HookSpec(
            id = "OpenComment",
            after = { call ->
                val panel = call.receiver
                val aweme = currentAweme.invoke(panel)
                if (aweme != null) videoEvent.invoke(panel, newEvent.newInstance(OPEN_COMMENT, aweme))
            },
        ))
    }

    companion object : TikTokFeature {
        private const val OPEN_COMMENT = 7

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(DoubleTapOpenComment(resolver, hostClassLoader))
        }
    }
}
