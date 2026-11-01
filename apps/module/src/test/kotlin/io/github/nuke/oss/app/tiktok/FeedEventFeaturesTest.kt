package io.github.nuke.oss.app.tiktok

import com.ss.android.ugc.aweme.feed.model.Aweme
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.feature.general.*
import io.github.nuke.oss.app.tiktok.feature.playback.*
import io.github.nuke.oss.app.tiktok.feature.screen.*
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedPager
import io.github.nuke.oss.core.Logger
import org.junit.Assert.*
import org.junit.Test

// Contract tests for the features driven by the feed's playback events, with stand-ins for the player, pager and
// video engine and recorded prompts. They prove the event handling and the rules, not a Douyin build.
class FeedEventFeaturesTest : HookHarness() {
    class Player { fun change(aweme: Aweme?, from: String?) {} }
    class PagerAdapter(private val items: List<Any?>) { fun getItem(index: Int): Any? = items.getOrNull(index) }
    class Pager(private val adapter: PagerAdapter) {
        var current = 0
        val moves = ArrayList<Int>()
        fun getAdapter() = adapter
        fun getCurrentItem() = current
        fun setCurrentItemInternal(item: Int, smooth: Boolean, always: Boolean, velocity: Int) { current = item }
        fun setCurrentItemWithDefaultVelocityOpt(item: Int) { moves += item }
    }
    class Engine {
        fun play() {}
        fun pause() {}
        fun marker() {}
    }
    class Listener { fun onCompletion(player: Any?) {} }

    private val change = method(Player::class.java, "change", Aweme::class.java, String::class.java)
    private val select = method(
        Pager::class.java, "setCurrentItemInternal",
        Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
    )
    private val play = method(Engine::class.java, "play")
    private val pause = method(Engine::class.java, "pause")
    private val scheduler = ManualScheduler()
    private val prompts = RecordingPrompts()

    private fun events(vararg extra: Pair<io.github.nuke.oss.app.shared.dex.DexQuery, List<java.lang.reflect.Method>>) = FeedEvents(
        resolver(
            FeedQueries.AWEME_CHANGED to listOf(change),
            FeedQueries.PAGER_SELECT to listOf(select),
            FeedQueries.VIDEO_ENGINE to listOf(method(Engine::class.java, "marker")),
            FeedQueries.PLAYBACK_COMPLETION to listOf(method(Listener::class.java, "onCompletion", Any::class.java)),
            *extra,
        ),
        loader,
    )

    @Test fun `a long video is announced once and the first video is not checked`() {
        val feature = LongVideoNotice(events(), prompts, values(LongVideoNotice.ID, LongVideoNotice.OPTIONS, "minutes" to 10L))
        enable(feature)
        backend.call(change, Player(), Aweme("first", 3_600_000), "")
        backend.call(change, Player(), Aweme("long", 754_000), "")
        backend.call(change, Player(), Aweme("long", 754_000), "")
        backend.call(change, Player(), Aweme("short", 30_000), "")
        val pager = Pager(PagerAdapter(listOf(Aweme("paged", 3_723_000))))
        backend.call(select, pager, 0, true, false, 0)
        assertEquals(1, prompts.installs)
        assertEquals(listOf(R.string.host_notice_long_video to listOf<Any>("12:34"), R.string.host_notice_long_video to listOf<Any>("1:02:03")), prompts.toasts)
    }

    @Test fun `the status bar hides on play and with the option comes back on a settled pause`() {
        val bars = RecordingBars()
        enable(HideStatusBar(events(), bars, scheduler, values(HideStatusBar.ID, HideStatusBar.OPTIONS, "only-while-playing" to true), scheduler::now))
        val engine = Engine()
        backend.call(play, engine)
        scheduler.advance(1000)
        backend.call(pause, engine)
        scheduler.advance(100)
        backend.call(play, engine)
        scheduler.advance(1000)
        backend.call(pause, engine)
        scheduler.advance(1000)
        assertEquals(listOf(false, false, true), bars.visible)
    }

    @Test fun `without the option a pause leaves the status bar hidden`() {
        val bars = RecordingBars()
        enable(HideStatusBar(events(), bars, scheduler, values(HideStatusBar.ID, HideStatusBar.OPTIONS), scheduler::now))
        backend.call(play, Engine())
        backend.call(pause, Engine())
        scheduler.advance(1000)
        assertEquals(listOf(false), bars.visible)
    }

    class Touchable { fun dispatchTouchEvent(event: Any?): Boolean = false }
    class Lifecycle {
        fun resume(activity: Any?) {}
        fun pause(activity: Any?) {}
    }

    private val touch = method(Touchable::class.java, "dispatchTouchEvent", Any::class.java)
    private val resume = method(Lifecycle::class.java, "resume", Any::class.java)
    private val pauseActivity = method(Lifecycle::class.java, "pause", Any::class.java)

    private fun timedClose(scheme: String, exit: RecordingExit, extra: List<Pair<io.github.nuke.oss.app.shared.dex.DexQuery, List<java.lang.reflect.Method>>> = emptyList()) = TimedClose(
        resolver(*extra.toTypedArray()), loader,
        values(TimedClose.ID, TimedClose.OPTIONS, "minutes" to 30L, "scheme" to scheme),
        prompts, exit, scheduler, Logger {},
    ) { ActivitySignals(touch, resume, pauseActivity) { it as? Int } }

    @Test fun `the module scheme counts down after a stretch without touches`() {
        val exit = RecordingExit()
        enable(timedClose(TimedClose.MODULE, exit))
        scheduler.advance(29 * 60_000L)
        backend.call(touch, Touchable(), android.view.MotionEvent.ACTION_DOWN)
        scheduler.advance(29 * 60_000L)
        assertTrue(prompts.countdowns.isEmpty())
        scheduler.advance(60_000L)
        val first = prompts.countdowns.single()
        assertEquals(10, first.seconds)
        backend.call(touch, Touchable(), android.view.MotionEvent.ACTION_UP)
        assertTrue(first.closed)
        backend.call(pauseActivity, Lifecycle(), null)
        scheduler.advance(60 * 60_000L)
        assertEquals(1, prompts.countdowns.size)
        backend.call(resume, Lifecycle(), null)
        scheduler.advance(30 * 60_000L)
        prompts.countdowns.last().onFinish()
        assertEquals(1, exit.exits)
    }

    @Test fun `turning timed close off cancels its timer`() {
        val feature = timedClose(TimedClose.MODULE, RecordingExit())
        enable(feature)
        disable(feature)
        scheduler.advance(60 * 60_000L)
        assertTrue(prompts.countdowns.isEmpty())
    }

    enum class Mode { STABLE_TIME, OTHER }
    class CountdownTask {
        init { created += this }
        fun start(seconds: Long, mode: Mode, source: String) { started += listOf(seconds, mode, source) }
        companion object {
            val created = ArrayList<CountdownTask>()
            val started = ArrayList<List<Any>>()
        }
    }

    @Test fun `the host scheme starts Douyin's own timer with the chosen time`() {
        CountdownTask.started.clear()
        val start = method(CountdownTask::class.java, "start", Long::class.javaPrimitiveType!!, Mode::class.java, String::class.java)
        enable(timedClose(TimedClose.HOST, RecordingExit(), listOf(OptionQueries.COUNTDOWN_TASK to listOf(start))))
        backend.call(CountdownTask::class.java.getDeclaredConstructor())
        assertTrue(CountdownTask.started.isEmpty())
        scheduler.advance(1000)
        assertEquals(listOf(listOf<Any>(1800L, Mode.STABLE_TIME, "long_press")), CountdownTask.started)
    }

    @Test fun `the pager moves on by one page`() {
        val events = events()
        val pager = Pager(PagerAdapter(listOf(Aweme("a"), Aweme("b"))))
        lateinit var tracked: FeedPager
        val feature = object : io.github.nuke.oss.runtime.RuntimeFeature {
            override val id = "PagerTest"
            override fun install(scope: io.github.nuke.oss.runtime.FeatureScope) { tracked = events.pager(scope, id) }
        }
        enable(feature)
        assertFalse(tracked.next())
        backend.call(select, pager, 3, false, false, 0)
        assertTrue(tracked.next())
        assertEquals(listOf(4), pager.moves)
    }

    class Progress { fun onProgress(videoId: String?, position: Long, duration: Long) {} }
    class LiveAweme(@JvmField var aweme_id: String?, @JvmField var aweme_type: Int = 0, @JvmField var images: List<Any?>? = null)

    private val progress = method(Progress::class.java, "onProgress", String::class.java, Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!)

    @Test fun `the module scheme moves on just before a playing video ends`() {
        val events = events(FeedQueries.PLAY_PROGRESS to listOf(progress))
        enable(KeepAutoPlayOn(loader, events, scheduler, { RecordingAutoPlayButton() }, values(KeepAutoPlayOn.ID, KeepAutoPlayOn.OPTIONS, "scheme" to KeepAutoPlayOn.MODULE)))
        val pager = Pager(PagerAdapter(listOf(Aweme("a"), Aweme("b"))))
        backend.call(select, pager, 0, true, false, 0)
        backend.call(play, Engine())
        backend.call(progress, Progress(), "a", 10_000L, 30_000L)
        backend.call(progress, Progress(), "a", 27_000L, 30_000L)
        backend.call(progress, Progress(), "a", 28_000L, 30_000L)
        scheduler.advance(2_999)
        assertTrue(pager.moves.isEmpty())
        scheduler.advance(1)
        assertEquals(listOf(1), pager.moves)
    }

    @Test fun `a pause or a new video stops the queued move`() {
        val events = events(FeedQueries.PLAY_PROGRESS to listOf(progress))
        enable(KeepAutoPlayOn(loader, events, scheduler, { RecordingAutoPlayButton() }, values(KeepAutoPlayOn.ID, KeepAutoPlayOn.OPTIONS, "scheme" to KeepAutoPlayOn.MODULE)))
        val pager = Pager(PagerAdapter(listOf(Aweme("a"), Aweme("b"))))
        backend.call(select, pager, 0, true, false, 0)
        backend.call(play, Engine())
        backend.call(progress, Progress(), "a", 27_000L, 30_000L)
        backend.call(pause, Engine())
        scheduler.advance(5_000)
        backend.call(play, Engine())
        backend.call(select, pager, 1, true, false, 0)
        backend.call(progress, Progress(), "b", 27_000L, 30_000L)
        backend.call(change, Player(), Aweme("c"), "")
        scheduler.advance(5_000)
        assertTrue(pager.moves.isEmpty())
    }

    @Test fun `live rooms and image posts move on after their own time`() {
        val events = FeedEvents(
            resolver(
                FeedQueries.AWEME_CHANGED to listOf(method(LivePlayer::class.java, "change", LiveAweme::class.java, String::class.java)),
                FeedQueries.PAGER_SELECT to listOf(select),
                FeedQueries.VIDEO_ENGINE to listOf(method(Engine::class.java, "marker")),
                FeedQueries.PLAY_PROGRESS to listOf(progress),
            ),
            loader, LiveAweme::class.java.name,
        )
        enable(KeepAutoPlayOn(loader, events, scheduler, { RecordingAutoPlayButton() }, values(
            KeepAutoPlayOn.ID, KeepAutoPlayOn.OPTIONS, "scheme" to KeepAutoPlayOn.MODULE, "live-seconds" to 20L, "images-seconds" to 0L,
        )))
        val pager = Pager(PagerAdapter(emptyList()))
        backend.call(select, pager, 2, true, false, 0)
        val change = method(LivePlayer::class.java, "change", LiveAweme::class.java, String::class.java)
        backend.call(change, LivePlayer(), LiveAweme("photos", images = listOf("x")), "")
        scheduler.advance(60_000)
        assertTrue(pager.moves.isEmpty())
        backend.call(change, LivePlayer(), LiveAweme("room", aweme_type = 101), "")
        scheduler.advance(20_000)
        assertEquals(listOf(3), pager.moves)
    }

    class LivePlayer { fun change(aweme: LiveAweme?, from: String?) {} }

    class FeedSeekBar {
        fun setProgress(progress: Int) {}
        fun setProgress(progress: Int, animate: Boolean) {}
    }

    private class RecordingLabel : ProgressLabel {
        val attached = ArrayList<Any>()
        val shown = ArrayList<String>()
        override fun attach(seekBar: Any) { attached += seekBar }
        override fun show(text: String) { shown += text }
    }

    @Test fun `the progress bar gets the played and total time`() {
        val label = RecordingLabel()
        val events = events(FeedQueries.PLAY_PROGRESS to listOf(progress))
        enable(ProgressTime(loader, events, { label }, listOf("missing.SeekBar", FeedSeekBar::class.java.name)))
        val bar = FeedSeekBar()
        backend.call(method(FeedSeekBar::class.java, "setProgress", Int::class.javaPrimitiveType!!), bar, 3)
        backend.call(method(FeedSeekBar::class.java, "setProgress", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!), bar, 4, true)
        backend.call(progress, Progress(), "a", 65_000L, 3_725_000L)
        backend.call(progress, Progress(), "a", 0L, 0L)
        assertEquals(listOf<Any>(bar, bar), label.attached)
        assertEquals(listOf("1:05/1:02:05"), label.shown)
    }

    @Test fun `no known progress bar fails the install`() {
        failsToInstall(ProgressTime(loader, events(), { RecordingLabel() }, listOf("missing.SeekBar")), "No known progress bar class")
    }

    @Test fun `the navigation bar is made transparent for every new video`() {
        val bars = RecordingBars()
        enable(TransparentNavigationBar(events(), bars))
        backend.call(change, Player(), Aweme("a"), "")
        backend.call(change, Player(), Aweme("a"), "")
        backend.call(change, Player(), Aweme("b"), "")
        assertEquals(2, bars.transparentNavigation)
    }

    class IntroLayout { var built = 0 }
    private class RecordingInfoLabels : BottomInfoLabels {
        val attached = ArrayList<Any>()
        val shown = ArrayList<String>()
        override fun attach(layout: Any) { attached += layout }
        override fun show(text: String) { shown += text }
    }

    private val timeZoneGuard = java.util.TimeZone.getDefault()

    @Test fun `the bottom line is attached to each intro layout and follows the video and its position`() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            val labels = RecordingInfoLabels()
            val feature = VideoBottomInfo(
                resolver(), loader, events(FeedQueries.PLAY_PROGRESS to listOf(progress)),
                values(VideoBottomInfo.ID, VideoBottomInfo.OPTIONS, "template" to "{published} {duration}|{played}/{remaining}", "time-pattern" to "yyyy-MM-dd"),
                { labels }, IntroLayout::class.java.name,
            )
            enable(feature)
            val layout = IntroLayout::class.java.getDeclaredConstructor()
            backend.call(layout)
            assertEquals(1, labels.attached.size)
            backend.call(change, Player(), Aweme("a", duration = 90_000, create_time = 1_700_000_000), "")
            backend.call(progress, Progress(), "a", 30_000L, 90_000L)
            assertEquals(listOf("2023-11-14 1:30|0:00/1:30", "2023-11-14 1:30|0:30/1:00"), labels.shown)
        } finally {
            java.util.TimeZone.setDefault(timeZoneGuard)
        }
    }

    @Test fun `a bad time pattern falls back to the default and no video shows nothing`() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            assertEquals("2023-11-14 22:13:20", renderBottomInfo("{published}", 1_700_000_000_000, "not a pattern!!", 0, 0))
            val labels = RecordingInfoLabels()
            enable(VideoBottomInfo(resolver(), loader, events(FeedQueries.PLAY_PROGRESS to listOf(progress)), values(VideoBottomInfo.ID, VideoBottomInfo.OPTIONS), { labels }, IntroLayout::class.java.name))
            backend.call(progress, Progress(), "a", 1_000L, 5_000L)
            assertTrue(labels.shown.isEmpty())
        } finally {
            java.util.TimeZone.setDefault(timeZoneGuard)
        }
    }
}
