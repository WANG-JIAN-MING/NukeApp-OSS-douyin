package io.github.nuke.oss.app.tiktok.feature.comment

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.core.Logger
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier

// Pauses the video while the comment panel is open and plays it again when the panel closes, ("打开/关闭
// 暂停/播放视频"). The player is the video engine that last started playing. Only a video this feature paused is
// played again, so a video you paused yourself stays paused. The panel's state comes from the feed comment presenter's
// dialog event: its one final int field is 1 when the panel opens.
@HookEntry
internal class PauseOnCommentOpen(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val logger: Logger,
    private val presenterName: String = PRESENTER,
) : RuntimeFeature, UsesDexQueries {
    override val id = "PauseOnCommentOpen"
    override val dexQueries = listOf(FeedQueries.VIDEO_ENGINE)

    // The engine the user last saw play, whether it is playing, and the one this feature paused. Engines are held weakly.
    private class PlayerState {
        var current = WeakReference<Any>(null)
        var playing = false
        var pausedByUs = WeakReference<Any>(null)
    }

    override fun install(scope: FeatureScope) {
        val engine = resolver.methods(hostClassLoader, FeedQueries.VIDEO_ENGINE).single().declaringClass
        val play = noArgument(engine, "play")
        val pause = noArgument(engine, "pause")
        val presenter = presenterName.toClass(hostClassLoader)
        val events = presenter.resolve().optional(silent = true).method { name = "onCommentDialogEvent"; parameterCount = 1; isSynthetic = false }.map { it.self }
        check(events.size == 1) { "Expected one onCommentDialogEvent in ${presenter.name}, found ${events.size}" }
        val dialogEvent = events.single().apply { isAccessible = true }
        val states = dialogEvent.parameterTypes[0].declaredFields.filter {
            !Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.type == Int::class.javaPrimitiveType
        }
        check(states.size == 1) { "Expected one final int state in ${dialogEvent.parameterTypes[0].name}, found ${states.size}" }
        val stateField = states.single().apply { isAccessible = true }
        val state = PlayerState()
        scope.hooks.install(play, HookSpec(id = "Play", after = { call ->
            if (call.outcome is CallOutcome.Returned) synchronized(state) {
                state.current = WeakReference(call.receiver)
                state.playing = true
            }
        }))
        scope.hooks.install(pause, HookSpec(id = "Pause", after = { call ->
            if (call.outcome is CallOutcome.Returned) synchronized(state) {
                if (state.current.get() === call.receiver) state.playing = false
            }
        }))
        scope.hooks.install(dialogEvent, HookSpec(id = "CommentDialog", after = { call ->
            val event = call.argument(0) ?: return@HookSpec
            val opened = stateField.getInt(event) == OPENED
            val (target, method) = synchronized(state) {
                val current = state.current.get()
                when {
                    opened && current != null && state.playing -> {
                        state.pausedByUs = WeakReference(current)
                        current to pause
                    }
                    !opened && current != null && !state.playing && state.pausedByUs.get() === current -> {
                        state.pausedByUs = WeakReference(null)
                        current to play
                    }
                    else -> {
                        if (!opened) state.pausedByUs = WeakReference(null)
                        return@HookSpec
                    }
                }
            }
            try {
                method.invoke(target)
            } catch (failure: InvocationTargetException) {
                logger.log("feature=$id ${method.name}_failed ${failure.targetException.javaClass.simpleName}")
            }
        }))
    }

    private fun noArgument(type: Class<*>, name: String): Method =
        type.resolve().optional(silent = true).method { this.name = name; emptyParameters(); isSynthetic = false }
            .singleOrNull()?.self?.apply { isAccessible = true } ?: error("${type.name} declares no single $name()")

    companion object : TikTokFeature {
        const val PRESENTER = "com.ss.android.ugc.aweme.feed.quick.presenter.FeedCommentPresenter"
        private const val OPENED = 1

        override fun create(services: TikTokFeatureServices) = with(services) { listOf(PauseOnCommentOpen(resolver, hostClassLoader, logger)) }
    }
}
