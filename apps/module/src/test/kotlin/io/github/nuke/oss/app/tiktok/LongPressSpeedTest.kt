package io.github.nuke.oss.app.tiktok

import com.ss.android.ugc.aweme.feed.model.Aweme
import io.github.nuke.oss.R
import io.github.nuke.oss.app.shared.activity.Timers
import io.github.nuke.oss.app.tiktok.feature.playback.LongPressSpeed
import io.github.nuke.oss.app.tiktok.feature.playback.SpeedGesture
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the edge long-press speed gesture and its wiring, with stand-ins for the feed's root view, touch
// events and player. They prove the gesture rules and the hook handling, not a Douyin build.
class LongPressSpeedTest : HookHarness() {
    class RootView(private val width: Int = 1000, private val height: Int = 2400) {
        var passed = 0
        fun getWidth() = width
        fun getHeight() = height
        fun dispatchTouchEvent(event: Touch?): Boolean { passed++; return false }
    }
    class Touch(private val action: Int, private val x: Float, private val y: Float) {
        fun getActionMasked() = action
        fun getX() = x
        fun getY() = y
    }
    class PrepareData(private val id: String) {
        override fun toString() = "PrepareData{urlSupplier=u, id=$id, other=1}"
    }
    class Player {
        val speeds = ArrayList<Float>()
        fun prepare(data: PrepareData?) {}
        fun setSpeed(speed: Float) { speeds += speed }
    }
    class Player2 { fun setSpeed(speed: Int) {} }
    class Changer { fun change(aweme: Aweme?, from: String?) {} }
    class Pager { fun setCurrentItemInternal(item: Int, smooth: Boolean, always: Boolean, velocity: Int) {} }
    class Engine { fun play() {}; fun pause() {}; fun marker() {} }
    class Completion { fun onCompletion(player: Any?) {} }

    private val down = 0
    private val up = 1
    private val move = 2
    private val cancel = 3

    private val dispatch = method(RootView::class.java, "dispatchTouchEvent", Touch::class.java)
    private val prepare = method(Player::class.java, "prepare", PrepareData::class.java)
    private val setSpeed = method(Player::class.java, "setSpeed", Float::class.javaPrimitiveType!!)
    private val change = method(Changer::class.java, "change", Aweme::class.java, String::class.java)
    private val scheduler = ManualScheduler()
    private val prompts = RecordingPrompts()

    private fun feature(vararg saved: Pair<String, Any>, speed: java.lang.reflect.Method = setSpeed): LongPressSpeed {
        val resolver = resolver(
            FeedQueries.AWEME_CHANGED to listOf(change),
            FeedQueries.PAGER_SELECT to listOf(method(Pager::class.java, "setCurrentItemInternal",
                Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)),
            FeedQueries.PLAYER_SPEED to listOf(speed),
            FeedQueries.PLAYER_PREPARED to listOf(prepare),
        )
        return LongPressSpeed(
            resolver, loader, FeedEvents(resolver, loader), prompts, scheduler,
            values(LongPressSpeed.ID, LongPressSpeed.OPTIONS, *saved), RootView::class.java.name,
        )
    }

    // Plays video "a" on a player and returns a function that sends one touch to the feed.
    private fun feed(): Pair<Player, (Int, Float, Float) -> Any?> {
        val player = Player()
        backend.call(prepare, player, PrepareData("a"))
        backend.call(change, Changer(), Aweme("a"), "")
        val view = RootView()
        return player to { action, x, y -> backend.call(dispatch, view, Touch(action, x, y)) }
    }

    @Test fun `holding at the edge and sliding sets the speed of the current video`() {
        enable(feature())
        val (player, touch) = feed()
        assertEquals(false, touch(down, 20f, 100f))
        assertEquals(false, touch(move, 20f, 105f))
        scheduler.advance(500)
        assertEquals(listOf(0.25f), player.speeds)
        assertEquals(true, touch(move, 20f, 1250f))
        assertEquals(listOf(0.25f, 1.75f), player.speeds)
        assertEquals(true, touch(up, 20f, 1250f))
        assertEquals(listOf(0.25f, 1.75f, 1f), player.speeds)
        assertTrue(prompts.toasts.isEmpty())
    }

    @Test fun `resting on a speed locks it and lifting keeps it`() {
        enable(feature())
        val (player, touch) = feed()
        touch(down, 980f, 2300f)
        scheduler.advance(500)
        scheduler.advance(1000)
        assertEquals(listOf(R.string.host_notice_speed_locked to listOf<Any>("3")), prompts.toasts)
        assertEquals(true, touch(up, 980f, 2300f))
        assertEquals(listOf(3f, 3f), player.speeds)
        assertEquals(R.string.host_notice_speed_kept to listOf<Any>("3"), prompts.toasts.last())
    }

    @Test fun `moving to another speed restarts the lock`() {
        enable(feature("top-fast" to true))
        val (player, touch) = feed()
        touch(down, 20f, 100f)
        scheduler.advance(500)
        scheduler.advance(900)
        touch(move, 20f, 1250f)
        scheduler.advance(900)
        assertTrue(prompts.toasts.isEmpty())
        scheduler.advance(100)
        assertEquals(listOf(R.string.host_notice_speed_locked to listOf<Any>("1.5")), prompts.toasts)
        assertEquals(listOf(3f, 1.5f), player.speeds)
    }

    @Test fun `a press in the middle or a swipe from the edge is left to the host`() {
        enable(feature())
        val (player, touch) = feed()
        touch(down, 500f, 1000f)
        scheduler.advance(2000)
        assertEquals(false, touch(up, 500f, 1000f))
        touch(down, 20f, 1000f)
        assertEquals(false, touch(move, 20f, 1200f))
        scheduler.advance(2000)
        assertEquals(false, touch(up, 20f, 1200f))
        assertTrue(player.speeds.isEmpty())
        assertEquals(0, scheduler.pending())
    }

    @Test fun `lifting before the hold completes does nothing and a cancel restores normal speed`() {
        enable(feature())
        val (player, touch) = feed()
        touch(down, 20f, 500f)
        scheduler.advance(300)
        assertEquals(false, touch(up, 20f, 500f))
        scheduler.advance(2000)
        assertTrue(player.speeds.isEmpty())
        touch(down, 20f, 500f)
        scheduler.advance(500)
        assertEquals(true, touch(cancel, 20f, 500f))
        assertEquals(listOf(0.75f, 1f), player.speeds)
    }

    @Test fun `without a known player for the video nothing is set`() {
        enable(feature())
        val player = Player()
        backend.call(change, Changer(), Aweme("other"), "")
        backend.call(prepare, player, PrepareData("a"))
        val view = RootView()
        backend.call(dispatch, view, Touch(down, 20f, 100f))
        scheduler.advance(500)
        assertEquals(true, backend.call(dispatch, view, Touch(up, 20f, 100f)))
        assertTrue(player.speeds.isEmpty())
    }

    @Test fun `turning the feature off cancels a pending hold and stops consuming touches`() {
        val feature = feature()
        enable(feature)
        val (player, touch) = feed()
        touch(down, 20f, 100f)
        disable(feature)
        assertEquals(0, scheduler.pending())
        scheduler.advance(2000)
        assertTrue(player.speeds.isEmpty())
        assertEquals(false, touch(up, 20f, 100f))
    }

    @Test fun `a speed setter of the wrong shape fails the install`() {
        failsToInstall(feature(speed = method(Player2::class.java, "setSpeed", Int::class.javaPrimitiveType!!)), "is not a speed setter")
    }

    @Test fun `a root view that does not handle touches fails the install`() {
        val resolver = resolver(
            FeedQueries.AWEME_CHANGED to listOf(change),
            FeedQueries.PAGER_SELECT to listOf(method(Pager::class.java, "setCurrentItemInternal",
                Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)),
            FeedQueries.PLAYER_SPEED to listOf(setSpeed),
            FeedQueries.PLAYER_PREPARED to listOf(prepare),
        )
        failsToInstall(
            LongPressSpeed(resolver, loader, FeedEvents(resolver, loader), prompts, scheduler,
                values(LongPressSpeed.ID, LongPressSpeed.OPTIONS), Player::class.java.name),
            "declares no single dispatchTouchEvent",
        )
    }

    @Test fun `the gesture picks one of twelve quarter steps`() {
        val seen = ArrayList<Float>()
        val gesture = SpeedGesture(Timers(scheduler), false, seen::add, {}, {})
        gesture.down(10f, 0f, 1000, 1200)
        scheduler.advance(500)
        for (y in listOf(99f, 100f, 1199f, 5000f, -40f)) gesture.move(10f, y)
        assertEquals(listOf(0.25f, 0.5f, 3f, 0.25f), seen)
        assertTrue(gesture.active)
        assertTrue(gesture.finish())
    }
}
