package io.github.nuke.oss.app.tiktok.sdk.feed

import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.condition.type.Modifiers
import com.highcapable.kavaref.extension.makeAccessible
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.app.shared.dex.DexQuery
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.reflect.NamedFieldAlias
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.hook.api.CallOutcome
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicReference

// The feed's playback events: the current video changing, the player playing and
// pausing, and a video playing to its end. Each feature subscribes with its own hooks in its own scope, so turning a
// feature off removes only its subscriptions.
internal class FeedEvents(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val awemeName: String = AWEME,
) {
    // The id is aid in the host model and aweme_id in its serialized form; it tells a repeated report of one video apart.
    private val awemeId = NamedFieldAlias("aid", "aweme_id")
    private val getAdapter = NamedCall("getAdapter")
    private val getItem = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? = type.resolve().optional(silent = true).firstMethodOrNull {
            name = "getItem"
            parameters(Int::class)
            modifiers(Modifiers.PUBLIC)
            superclass(interfaces = true)
        }?.self?.apply { makeAccessible() }
    }

    // The listener gets the video's Aweme once per change. The player reports the video it switches to; the pager
    // reports the page it settles on. Both may report the same video, which is passed on once.
    fun onVideoChanged(scope: FeatureScope, featureId: String, listener: (aweme: Any) -> Unit) {
        val aweme = awemeName.toClass(hostClassLoader)
        val (changed, page) = resolver.methods(hostClassLoader, VIDEO_CHANGED)
        val last = AtomicReference<String?>()
        val deliver = { video: Any? ->
            if (video != null && aweme.isInstance(video)) {
                val id = awemeId.of(video)?.get(video) as? String
                if (id == null || last.getAndSet(id) != id) listener(video)
            }
        }
        changed.isAccessible = true
        page.isAccessible = true
        scope.hooks.install(changed, HookSpec(id = "$featureId:VideoChanged", after = { call ->
            if (call.outcome is CallOutcome.Returned) deliver(call.argument(0))
        }))
        scope.hooks.install(page, HookSpec(id = "$featureId:PageSelected", after = { call ->
            val pager = call.receiver
            val index = call.argument(0) as? Int
            if (call.outcome is CallOutcome.Returned && pager != null && index != null) deliver(itemAt(pager, index))
        }))
    }

    // The feed pager the user is on, for features that move to the next video.
    fun pager(scope: FeatureScope, featureId: String): FeedPager {
        val page = resolver.methods(hostClassLoader, PAGER).single().apply { isAccessible = true }
        val tracked = FeedPager()
        scope.hooks.install(page, HookSpec(id = "$featureId:TrackPager", after = { call -> call.receiver?.let(tracked::seen) }))
        return tracked
    }

    fun onPlayback(scope: FeatureScope, featureId: String, onPlay: () -> Unit, onPause: () -> Unit) {
        val engine = resolver.methods(hostClassLoader, PLAYBACK).single().declaringClass
        val play = engine.noArgument("play")
        val pause = engine.noArgument("pause")
        scope.hooks.install(play, HookSpec(id = "$featureId:Play", before = { onPlay() }))
        scope.hooks.install(pause, HookSpec(id = "$featureId:Pause", before = { onPause() }))
    }

    fun onCompletion(scope: FeatureScope, featureId: String, listener: () -> Unit) {
        val completion = resolver.methods(hostClassLoader, COMPLETION).single().apply { isAccessible = true }
        scope.hooks.install(completion, HookSpec(id = "$featureId:Completed", after = { call ->
            if (call.outcome is CallOutcome.Returned) listener()
        }))
    }

    // The player's progress listener reports the video ID, the position and the duration in milliseconds; hosts differ
    // in argument order, (String, long, long) or (long, long, String).
    fun onProgress(scope: FeatureScope, featureId: String, listener: (videoId: String?, position: Long, duration: Long) -> Unit) {
        val progress = resolver.methods(hostClassLoader, PROGRESS).single().apply { isAccessible = true }
        val idFirst = progress.parameterTypes[0] == String::class.java
        scope.hooks.install(progress, HookSpec(id = "$featureId:Progress", before = { call ->
            val id = call.argument(if (idFirst) 0 else 2) as? String
            val position = call.argument(if (idFirst) 1 else 0) as? Long
            val duration = call.argument(if (idFirst) 2 else 1) as? Long
            if (position != null && duration != null) listener(id, position, duration)
        }))
    }

    private fun itemAt(pager: Any, index: Int): Any? {
        val adapter = getAdapter.of(pager)?.invoke(pager) ?: return null
        return getItem.get(adapter.javaClass)?.invoke(adapter, index)
    }

    private fun Class<*>.noArgument(name: String): Method =
        resolve().optional(silent = true).method {
            this.name = name
            emptyParameters()
            isSynthetic = false
        }.singleOrNull()?.self?.apply { makeAccessible() } ?: error("${this.name} declares no single $name()")

    companion object {
        const val AWEME = "com.ss.android.ugc.aweme.feed.model.Aweme"

        // The queries each subscription resolves, which a feature using it declares as its own (see UsesDexQueries).
        val VIDEO_CHANGED: List<DexQuery> = listOf(FeedQueries.AWEME_CHANGED, FeedQueries.PAGER_SELECT)
        val PAGER: List<DexQuery> = listOf(FeedQueries.PAGER_SELECT)
        val PLAYBACK: List<DexQuery> = listOf(FeedQueries.VIDEO_ENGINE)
        val COMPLETION: List<DexQuery> = listOf(FeedQueries.PLAYBACK_COMPLETION)
        val PROGRESS: List<DexQuery> = listOf(FeedQueries.PLAY_PROGRESS)
    }
}

// The last feed pager seen settling on a page. Only a weak reference is kept.
internal class FeedPager {
    @Volatile private var pager: WeakReference<Any>? = null
    private val currentItem = NamedCall("getCurrentItem")
    private val moveTo = object : ClassValue<Method?>() {
        override fun computeValue(type: Class<*>): Method? = type.resolve().optional(silent = true).firstMethodOrNull {
            name = "setCurrentItemWithDefaultVelocityOpt"
            parameters(Int::class)
            superclass()
        }?.self?.apply { makeAccessible() }
    }

    internal fun seen(view: Any) {
        if (pager?.get() !== view) pager = WeakReference(view)
    }

    // Moves the pager on by one page the way the host's own smooth scroll does. False when no pager is known.
    fun next(): Boolean {
        val view = pager?.get() ?: return false
        val index = currentItem.of(view)?.invoke(view) as? Int ?: return false
        val move = moveTo.get(view.javaClass) ?: return false
        move.invoke(view, index + 1)
        return true
    }
}
