package io.github.nuke.oss.app.tiktok.feature.playback

import android.view.MotionEvent
import com.highcapable.kavaref.KavaRef.Companion.resolve
import com.highcapable.kavaref.extension.toClass
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.HostPrompts
import io.github.nuke.oss.app.shared.activity.TaskScheduler
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.shared.dex.DexResolver
import io.github.nuke.oss.app.shared.dex.UsesDexQueries
import io.github.nuke.oss.app.shared.dex.methodLists
import io.github.nuke.oss.app.shared.dex.methods
import io.github.nuke.oss.app.shared.reflect.NamedCall
import io.github.nuke.oss.app.shared.reflect.NamedField
import io.github.nuke.oss.app.shared.registry.HookEntry
import io.github.nuke.oss.app.shared.settings.OptionValues
import io.github.nuke.oss.app.shared.settings.ToggleOption
import io.github.nuke.oss.app.tiktok.feature.TikTokFeature
import io.github.nuke.oss.app.tiktok.feature.TikTokFeatureServices
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.hook.api.HookCapability
import io.github.nuke.oss.hook.api.HookSpec
import io.github.nuke.oss.runtime.FeatureScope
import io.github.nuke.oss.runtime.RuntimeFeature
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

// The edge long-press gesture as a state machine over touch positions: press at the left or right edge and hold, then
// slide up or down to pick one of twelve speeds, and rest on a speed for a second to keep it. It knows nothing of the
// host; the speed it wants goes out through callbacks. Touch and timer callbacks all arrive on the main thread.
internal class SpeedGesture(
    private val timers: Timers,
    private val topFast: Boolean,
    private val onSpeed: (Float) -> Unit,
    private val onLocked: (Float) -> Unit,
    private val onKept: (Float) -> Unit,
) {
    private enum class Phase { IDLE, ARMED, ACTIVE }

    private var phase = Phase.IDLE
    private var startX = 0f
    private var startY = 0f
    private var height = 1
    private var step = -1
    private var locked = false

    // True while the gesture owns the touch, so the host should not see it.
    val active get() = phase == Phase.ACTIVE

    fun down(x: Float, y: Float, width: Int, height: Int) {
        reset()
        if (width <= 0 || height <= 0 || (x >= width * EDGE && x <= width * (1 - EDGE))) return
        phase = Phase.ARMED
        startX = x
        startY = y
        this.height = height
        timers.replace(ARM, LONG_PRESS_MS) { if (phase == Phase.ARMED) activate() }
    }

    // True when the gesture consumed the move.
    fun move(x: Float, y: Float): Boolean {
        if (phase == Phase.ACTIVE) {
            select(y)
            return true
        }
        // A drag before the hold completes is an ordinary swipe.
        if (phase == Phase.ARMED && (abs(y - startY) > height * SLOP || abs(x - startX) > height * SLOP)) reset()
        return false
    }

    // The finger lifted or the touch was cancelled. True when the gesture consumed it.
    fun finish(): Boolean {
        val wasActive = phase == Phase.ACTIVE
        val kept = if (wasActive && locked) speedAt(step) else null
        reset()
        if (!wasActive) return false
        if (kept != null) onKept(kept) else onSpeed(NORMAL)
        return true
    }

    private fun activate() {
        phase = Phase.ACTIVE
        select(startY)
    }

    private fun select(y: Float) {
        val next = ((y / height) * STEPS).toInt().coerceIn(0, STEPS - 1)
        if (next == step) return
        step = next
        locked = false
        onSpeed(speedAt(next))
        timers.replace(LOCK, LOCK_MS) {
            if (phase == Phase.ACTIVE && step == next) {
                locked = true
                onLocked(speedAt(next))
            }
        }
    }

    private fun speedAt(index: Int): Float = (if (topFast) STEPS - index else index + 1) * STEP_SIZE

    private fun reset() {
        timers.cancel(ARM)
        timers.cancel(LOCK)
        phase = Phase.IDLE
        step = -1
        locked = false
    }

    companion object {
        const val EDGE = 0.09f
        const val STEPS = 12
        const val STEP_SIZE = 0.25f
        const val NORMAL = 1f
        const val LONG_PRESS_MS = 500L
        const val LOCK_MS = 1000L
        private const val SLOP = 0.02f
        private const val ARM = "arm"
        private const val LOCK = "lock"
    }
}

// Lets the user set the playback speed of the current video by pressing and holding at the left or right edge of the
// feed. The speed is set on the video's own player, found by the video ID that the player's prepare data carries.
@HookEntry
internal class LongPressSpeed(
    private val resolver: DexResolver,
    private val hostClassLoader: ClassLoader,
    private val events: FeedEvents,
    private val prompts: HostPrompts,
    private val scheduler: TaskScheduler,
    options: OptionValues,
    private val rootViewName: String = ROOT_VIEW,
) : RuntimeFeature, UsesDexQueries {
    override val id = ID
    override val dexQueries = listOf(FeedQueries.PLAYER_SPEED, FeedQueries.PLAYER_PREPARED) + FeedEvents.VIDEO_CHANGED
    private val topFast = options.toggle(TOP_FAST)
    private val awemeId = NamedField("aweme_id")
    private val width = NamedCall("getWidth")
    private val height = NamedCall("getHeight")
    private val action = NamedCall("getActionMasked")
    private val eventX = NamedCall("getX")
    private val eventY = NamedCall("getY")

    override fun install(scope: FeatureScope) {
        prompts.install(scope)
        val setter = resolver.methods(hostClassLoader, FeedQueries.PLAYER_SPEED).single()
        check(setter.returnType == Void.TYPE && setter.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType))) {
            "${setter.name} is not a speed setter"
        }
        val prepared = resolver.methodLists(hostClassLoader, FeedQueries.PLAYER_PREPARED).single()
        prepared.forEach { check(it.parameterCount == 1) { "${it.name} does not take only the prepare data" } }
        val touch = rootViewName.toClass(hostClassLoader).resolve().optional(silent = true).method {
            name = "dispatchTouchEvent"
            parameterCount = 1
            returnType = Boolean::class
            isSynthetic = false
        }.singleOrNull()?.self ?: error("$rootViewName declares no single dispatchTouchEvent")
        setter.isAccessible = true
        touch.isAccessible = true

        val players = Collections.synchronizedMap(object : LinkedHashMap<String, WeakReference<Any>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WeakReference<Any>>) = size > MAX_PLAYERS
        })
        val current = AtomicReference<String?>()
        val timers = scope.own(Timers(scheduler))
        val gesture = SpeedGesture(
            timers, topFast,
            onSpeed = { speed -> setSpeed(setter, players, current.get(), speed) },
            onLocked = { speed -> prompts.toast(R.string.host_notice_speed_locked, speedText(speed)) },
            onKept = { speed ->
                setSpeed(setter, players, current.get(), speed)
                prompts.toast(R.string.host_notice_speed_kept, speedText(speed))
            },
        )

        for (method in prepared) {
            method.isAccessible = true
            scope.hooks.install(method, HookSpec(id = "TrackPlayer:${method.name}", before = { call ->
                val player = call.receiver
                val video = call.argument(0)?.let { VIDEO_ID.find(it.toString())?.groupValues?.get(1) }
                if (player != null && video != null) players[video] = WeakReference(player)
            }))
        }
        events.onVideoChanged(scope, ID) { aweme -> current.set(awemeId.of(aweme)?.get(aweme) as? String) }
        scope.hooks.install(touch, HookSpec(
            id = "EdgeLongPress",
            requiredCapabilities = setOf(HookCapability.EARLY_RETURN),
            before = { call ->
                val view = call.receiver
                val event = call.argument(0)
                if (view != null && event != null && handle(gesture, view, event)) call.returnEarly(true)
            },
        ))
    }

    private fun handle(gesture: SpeedGesture, view: Any, event: Any): Boolean {
        val kind = action.of(event)?.invoke(event) as? Int ?: return false
        val x = eventX.of(event)?.invoke(event) as? Float
        val y = eventY.of(event)?.invoke(event) as? Float
        return when (kind) {
            MotionEvent.ACTION_DOWN -> {
                val w = width.of(view)?.invoke(view) as? Int
                val h = height.of(view)?.invoke(view) as? Int
                if (w != null && h != null && x != null && y != null) gesture.down(x, y, w, h)
                false
            }
            MotionEvent.ACTION_MOVE -> x != null && y != null && gesture.move(x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> gesture.finish()
            else -> gesture.active
        }
    }

    private fun setSpeed(setter: Method, players: Map<String, WeakReference<Any>>, video: String?, speed: Float) {
        val player = video?.let { players[it]?.get() } ?: return
        setter.invoke(player, speed)
    }

    private fun speedText(speed: Float) = if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()

    companion object : TikTokFeature {
        const val ID = "LongPressSpeed"
        const val TOP_FAST = "top-fast"
        const val ROOT_VIEW = "com.ss.android.ugc.aweme.ad.feed.VideoViewHolderRootView"
        private const val MAX_PLAYERS = 8
        private val VIDEO_ID = Regex("""(?:^|[{,\s])id=([^,}\s]+)""")
        val OPTIONS = listOf(ToggleOption(TOP_FAST))

        override fun create(services: TikTokFeatureServices) = with(services) {
            listOf(LongPressSpeed(resolver, hostClassLoader, events, prompts(ID), scheduler, options(ID, OPTIONS)))
        }
    }
}
