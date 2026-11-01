package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.query.OptionQueries
import io.github.nuke.oss.app.tiktok.sdk.screen.CleanNode.Kind
import io.github.nuke.oss.app.tiktok.feature.feed.*
import io.github.nuke.oss.app.tiktok.feature.screen.*
import io.github.nuke.oss.app.tiktok.sdk.screen.*
import org.junit.Assert.*
import org.junit.Test

// Tests for the clean mode rules on hand-built view snapshots, and for its play and pause handling.
class CleanModeTest : HookHarness() {
    private fun node(
        simple: String, kind: Kind = Kind.OTHER, text: String? = null, hint: String? = null, className: String = "x.$simple", vararg children: CleanNode,
    ): CleanNode = CleanNode(simple, className, text, hint, kind, Any()).also { parent -> children.forEach { parent.add(it) } }

    private fun root(vararg children: CleanNode) = node("DecorView", Kind.FRAME, children = children)
    private val defaults = CleanOptions(keepSeekBar = false, hideDanmaku = false, keepVideoInfo = false)

    @Test fun `known containers fade and unknown layouts stay`() {
        val right = node("FeedRightScaleView", Kind.LINEAR)
        val other = node("SomeLayout", Kind.LINEAR)
        val unknownKindButKnownName = node("EnterBottomLayout", Kind.OTHER)
        val plan = planCleanScreen(root(right, other, unknownKindButKnownName), defaults)
        assertEquals(listOf(right.handle), plan.fade)
        assertTrue(plan.gone.isEmpty())
    }

    @Test fun `tabs and the sound disc are only hidden`() {
        val tabs = node("MainBottomTabContainer", Kind.LINEAR)
        val disc = node("MusicCoverContainerLayout", Kind.FRAME)
        val relativeA = node("A", Kind.RELATIVE)
        val relativeB = node("B", Kind.RELATIVE)
        val fps = node("FpsControlFrameLayout", Kind.FRAME, children = arrayOf(relativeA, node("C", Kind.LINEAR), relativeB))
        val plan = planCleanScreen(root(tabs, disc, fps), defaults)
        assertTrue(plan.fade.isEmpty())
        assertEquals(listOf(tabs.handle, disc.handle, relativeA.handle, relativeB.handle), plan.gone)
    }

    @Test fun `label text hides the entry that holds it`() {
        val feedbackEntry = node("Entry", Kind.LINEAR)
        val feedback = node("T", Kind.TEXT, text = "反馈")
        feedbackEntry.add(feedback)
        val fullEntry = node("Entry2", Kind.LINEAR)
        fullEntry.add(node("T", Kind.TEXT, text = "全屏观看"))
        val plan = planCleanScreen(root(feedbackEntry, fullEntry), defaults)
        assertEquals(listOf(feedbackEntry.handle, fullEntry.handle), plan.fade)
    }

    @Test fun `the progress bar and the danmaku follow their options`() {
        val seek = node("CustomizedUISeekBar", Kind.SEEK_BAR)
        val danmaku = node("DanmakuView", Kind.FRAME)
        val tree = { root(seek, danmaku) }
        assertEquals(listOf(seek.handle), planCleanScreen(tree(), defaults).fade)
        assertEquals(listOf(seek.handle, danmaku.handle), planCleanScreen(tree(), defaults.copy(hideDanmaku = true)).fade)
        assertTrue(planCleanScreen(tree(), defaults.copy(keepSeekBar = true)).fade.isEmpty())
    }

    @Test fun `the video info is hidden unless it is to stay`() {
        val info = node("AwemeIntroInfoLayout", Kind.FRAME)
        assertEquals(listOf(info.handle), planCleanScreen(root(info), defaults).fade)
        assertTrue(planCleanScreen(root(info), defaults.copy(keepVideoInfo = true)).fade.isEmpty())
    }

    @Test fun `a search bar is found by its hint or label and a message entry by its text`() {
        val constraint = "androidx.constraintlayout.widget.ConstraintLayout"
        val withHint = node("CL", Kind.CONSTRAINT, className = constraint)
        withHint.add(node("E", Kind.EDIT_TEXT, hint = "搜索你感兴趣的内容"))
        val withLabel = node("CL", Kind.CONSTRAINT, className = constraint)
        withLabel.add(node("T", Kind.TEXT, text = "转发到日常"))
        val shortHint = node("CL", Kind.CONSTRAINT, className = constraint)
        shortHint.add(node("E", Kind.EDIT_TEXT, hint = "找找"))
        val noHint = node("CL", Kind.CONSTRAINT, className = constraint)
        noHint.add(node("E", Kind.EDIT_TEXT, hint = null))
        noHint.add(node("T", Kind.TEXT, text = "搜索"))
        val messages = node("QLayout", Kind.RELATIVE)
        messages.add(node("T", Kind.TEXT, text = "发私信"))
        val plan = planCleanScreen(root(withHint, withLabel, shortHint, noHint, messages), defaults)
        assertEquals(listOf(withHint.handle, withLabel.handle, messages.handle), plan.fade)
    }

    private val scheduler = ManualScheduler()
    private val bars = RecordingBars()
    private val screen = RecordingCleanScreen()
    private val foreground = FakeForeground("com.ss.android.ugc.aweme.main.MainActivity")

    private class Engine { fun play() {}; fun pause() {}; fun marker() {} }
    private val play = method(Engine::class.java, "play")
    private val pause = method(Engine::class.java, "pause")

    private fun clean(vararg on: String) = CleanMode(
        FeedEvents(resolver(FeedQueries.VIDEO_ENGINE to listOf(method(Engine::class.java, "marker"))), loader),
        screen, foreground, bars, scheduler,
        values(CleanMode.ID, CleanMode.OPTIONS, *on.map { it to true }.toTypedArray()), scheduler::now,
    )

    @Test fun `playing hides the controls and a settled pause brings them back`() {
        screen.root = root(node("FeedRightScaleView", Kind.LINEAR))
        enable(clean(CleanMode.HIDE_STATUS_BAR, CleanMode.KEEP_CLICKABLE))
        backend.call(play, Engine())
        scheduler.advance(10)
        assertEquals(1, screen.hidden.size)
        assertTrue(screen.hidden[0].second)
        assertEquals(listOf(false), bars.visible)
        backend.call(pause, Engine())
        scheduler.advance(100)
        backend.call(play, Engine())
        scheduler.advance(1000)
        assertEquals(0, screen.restores)
        backend.call(pause, Engine())
        scheduler.advance(1000)
        assertEquals(1, screen.restores)
        assertEquals(listOf(false, false, true), bars.visible)
    }

    @Test fun `screens that do not play the feed and a missing screen are left alone`() {
        screen.root = root(node("FeedRightScaleView", Kind.LINEAR))
        foreground.name = "com.ss.android.ugc.aweme.search.SearchResultActivity"
        enable(clean())
        backend.call(play, Engine())
        scheduler.advance(10)
        foreground.name = "com.ss.android.ugc.aweme.main.MainActivity"
        screen.root = null
        backend.call(play, Engine())
        scheduler.advance(10)
        assertTrue(screen.hidden.isEmpty())
    }

    @Test fun `turning the mode off cancels a pending hide`() {
        screen.root = root(node("FeedRightScaleView", Kind.LINEAR))
        val feature = clean()
        enable(feature)
        backend.call(play, Engine())
        disable(feature)
        scheduler.advance(1000)
        assertTrue(screen.hidden.isEmpty())
    }

    @Test fun `each opacity region gets its own views and the bottom setting wins on the tab bar`() {
        val tabs = node("MainBottomTabContainer", Kind.LINEAR)
        val title = node("MainTitleBar", Kind.FRAME)
        val right = node("FeedRightScaleView", Kind.LINEAR)
        val intro = node("AwemeIntroInfoLayout", Kind.FRAME)
        val seek = node("S", Kind.SEEK_BAR)
        val fullEntry = node("Entry", Kind.LINEAR).also { it.add(node("T", Kind.TEXT, text = "全屏观看")) }
        val fps = node("FpsControlFrameLayout", Kind.FRAME)
        val sameStyle = node("T", Kind.TEXT, text = "拍同款")
        fps.add(sameStyle)
        fps.add(node("T", Kind.TEXT, text = "别的"))
        val plan = planOpacity(root(tabs, title, right, intro, seek, fullEntry, fps))
        assertEquals(listOf(tabs.handle, intro.handle, seek.handle, fullEntry.handle), plan.bottom)
        assertEquals(listOf(title.handle), plan.top)
        assertEquals(listOf(right.handle, sameStyle.handle, fps.handle), plan.right)
    }

    private val opacity = RecordingOpacityScreen()

    private fun opacity(bottom: Long = 100, top: Long = 100, right: Long = 100) = ControlOpacity(
        FeedEvents(resolver(FeedQueries.AWEME_CHANGED to listOf(method(Changer::class.java, "change", com.ss.android.ugc.aweme.feed.model.Aweme::class.java, String::class.java)),
            FeedQueries.PAGER_SELECT to listOf(method(PagerStub::class.java, "select", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!))), loader),
        opacity, scheduler, values(ControlOpacity.ID, ControlOpacity.OPTIONS, "bottom" to bottom, "top" to top, "right" to right),
    )

    class Changer { fun change(aweme: com.ss.android.ugc.aweme.feed.model.Aweme?, from: String?) {} }
    class PagerStub { fun select(a: Int, b: Boolean, c: Boolean, d: Int) {} }

    @Test fun `the chosen opacities are applied after each video change and again later`() {
        opacity.root = root(node("MainTitleBar", Kind.FRAME), node("FeedRightScaleView", Kind.LINEAR), node("S", Kind.SEEK_BAR))
        enable(opacity(bottom = 40, right = 70))
        backend.call(method(Changer::class.java, "change", com.ss.android.ugc.aweme.feed.model.Aweme::class.java, String::class.java), Changer(), com.ss.android.ugc.aweme.feed.model.Aweme("a"), "")
        scheduler.advance(2000)
        // Four sweeps, each applying the bottom and right regions only: the top one is left at 100.
        assertEquals(8, opacity.applied.size)
        assertEquals(listOf(0.4f, 0.7f), opacity.applied.take(2).map { it.second })
    }

    @Test fun `all regions at 100 hook nothing`() {
        enable(opacity())
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `chosen video controls are found by label, name and the order of the title bar icons`() {
        val feedbackEntry = node("Entry", Kind.LINEAR).also { it.add(node("T", Kind.TEXT, text = "反馈")) }
        val fullEntry = node("Entry2", Kind.LINEAR).also { it.add(node("T", Kind.TEXT, text = "全屏观看")) }
        val danmaku = node("DanmakuVisibilityFrameLayout", Kind.FRAME)
        val search = node("AntiBurnSmartImageView")
        val menuA = node("AntiBurnSmartImageView")
        val menuB = node("AntiBurnSmartImageView")
        val title = node("MainTitleBar", Kind.FRAME, children = arrayOf(search, node("Other"), menuA, menuB))
        val tree = { root(feedbackEntry, fullEntry, danmaku, title) }
        assertEquals(listOf(search.handle), planVideoControls(tree(), setOf(VideoControls.SEARCH)))
        assertEquals(listOf(menuA.handle, menuB.handle), planVideoControls(tree(), setOf(VideoControls.MENU)))
        assertEquals(
            listOf(feedbackEntry.handle, fullEntry.handle, danmaku.handle),
            planVideoControls(tree(), setOf(VideoControls.FEEDBACK, VideoControls.FULL_SCREEN, VideoControls.DANMAKU)),
        )
        assertTrue(planVideoControls(tree(), emptySet()).isEmpty())
    }

    class QuickSharePresenter {
        var shown = 0
        fun show() { shown++ }
        fun show(flag: Boolean) { shown++ }
    }
    class ValueShare { fun show(): Boolean = true }
    class FollowAuthor(@JvmField var follow_status: Int)
    class FollowVideo(@JvmField var author: Any?)
    open class FollowPresenterBase { @JvmField var current: FollowVideo? = null }
    class FollowPresenter : FollowPresenterBase() { fun setup() {} }

    private fun hideControls(
        hidden: Set<String> = emptySet(), quickShare: Boolean = false, follow: Boolean = false,
        share: java.lang.reflect.Method = method(QuickSharePresenter::class.java, "show"),
    ) = HideVideoControls(
        resolver(
            FeedQueries.QUICK_SHARE to listOf(share), OptionQueries.LIVE_RING to listOf(method(FollowPresenter::class.java, "setup")),
            FeedQueries.AWEME_CHANGED to listOf(method(Changer::class.java, "change", com.ss.android.ugc.aweme.feed.model.Aweme::class.java, String::class.java)),
            FeedQueries.PAGER_SELECT to listOf(method(PagerStub::class.java, "select", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)),
        ),
        loader, FeedEvents(resolver(
            FeedQueries.AWEME_CHANGED to listOf(method(Changer::class.java, "change", com.ss.android.ugc.aweme.feed.model.Aweme::class.java, String::class.java)),
            FeedQueries.PAGER_SELECT to listOf(method(PagerStub::class.java, "select", Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)),
        ), loader),
        screen, scheduler,
        values(HideVideoControls.ID, HideVideoControls.OPTIONS, "hidden" to hidden, "quick-share" to quickShare, "follow-button" to follow),
        FollowPresenterBase::class.java.name, FollowVideo::class.java.name,
    )

    @Test fun `chosen controls are hidden after a video change`() {
        screen.root = root(node("DanmakuVisibilityFrameLayout", Kind.FRAME))
        enable(hideControls(hidden = setOf(VideoControls.DANMAKU)))
        backend.call(method(Changer::class.java, "change", com.ss.android.ugc.aweme.feed.model.Aweme::class.java, String::class.java), Changer(), com.ss.android.ugc.aweme.feed.model.Aweme("v"), "")
        scheduler.advance(2000)
        assertEquals(3, screen.hidden.size)
        assertFalse(screen.hidden[0].second)
        assertEquals(1, screen.hidden[0].first.gone.size)
    }

    @Test fun `the quick share presenter shows nothing in any overload`() {
        enable(hideControls(quickShare = true))
        val host = QuickSharePresenter()
        backend.call(method(QuickSharePresenter::class.java, "show"), host)
        backend.call(method(QuickSharePresenter::class.java, "show", Boolean::class.javaPrimitiveType!!), host, true)
        assertEquals(0, host.shown)
    }

    @Test fun `a quick share method that returns a value fails the install`() {
        failsToInstall(hideControls(quickShare = true, share = method(ValueShare::class.java, "show")), "not void")
    }

    @Test fun `the author is marked followed on screen`() {
        enable(hideControls(follow = true))
        val author = FollowAuthor(0)
        val presenter = FollowPresenter().also { it.current = FollowVideo(author) }
        backend.call(method(FollowPresenter::class.java, "setup"), presenter)
        assertEquals(1, author.follow_status)
        backend.call(method(FollowPresenter::class.java, "setup"), FollowPresenter())
    }

    @Test fun `nothing chosen hooks nothing for the controls`() {
        enable(hideControls())
        assertEquals(0, backend.attachments.get())
    }

    class Component(private val name: String) { override fun toString() = name }
    class ComponentAction(@JvmField val components: MutableSet<Any>, val label: String)
    class CopyingAction(components: MutableSet<Any>) { @JvmField val held: MutableSet<Any> = components.toMutableSet() }
    class StepAction { @JvmField val items: MutableSet<Any> = mutableSetOf(Component("a")); @JvmField val other: List<Any> = emptyList() }
    class Lifecycle { fun step(action: StepAction?, continuation: Any?) {} }
    class OneParamLifecycle { fun step(action: StepAction?) {} }
    class RightBar { fun built() {} }

    private val logged = ArrayList<String>()

    private fun components(
        names: List<String> = emptyList(), comment: Boolean = false, share: Boolean = false,
        step: java.lang.reflect.Method = method(Lifecycle::class.java, "step", StepAction::class.java, Any::class.java),
    ) = HideFeedComponents(
        resolver(FeedQueries.COMPONENT_LIFECYCLE to listOf(step)), loader,
        values(HideFeedComponents.ID, HideFeedComponents.OPTIONS, "components" to names, "comment" to comment, "share" to share),
        screen, scheduler, io.github.nuke.oss.core.Logger { logged += it },
        listOf("missing.Action", ComponentAction::class.java.name), RightBar::class.java.name,
    )

    @Test fun `chosen components are removed from the set the host passes and every name is logged once`() {
        enable(components(names = listOf("like", "comment")))
        val set = mutableSetOf<Any>(Component("like"), Component("collect"), Component("comment"))
        val constructor = ComponentAction::class.java.getDeclaredConstructor(MutableSet::class.java, String::class.java)
        backend.call(constructor, null, set, "x")
        assertEquals(setOf("collect", "comment"), set.map { it.toString() }.toSet())
        backend.call(constructor, null, mutableSetOf<Any>(Component("like")), "y")
        assertEquals(3, logged.size)
        assertTrue(logged[0].contains("component_seen name=like chosen=true"))
    }

    @Test fun `a set the action copied is trimmed too`() {
        val feature = HideFeedComponents(
            resolver(FeedQueries.COMPONENT_LIFECYCLE to listOf(method(Lifecycle::class.java, "step", StepAction::class.java, Any::class.java))), loader,
            values(HideFeedComponents.ID, HideFeedComponents.OPTIONS, "components" to listOf("like")),
            screen, scheduler, io.github.nuke.oss.core.Logger { logged += it }, listOf(CopyingAction::class.java.name), RightBar::class.java.name,
        )
        enable(feature)
        val action = backend.call(CopyingAction::class.java.getDeclaredConstructor(MutableSet::class.java), null, mutableSetOf<Any>(Component("like"), Component("keep")))
        assertNull(action)
    }

    @Test fun `the lifecycle step trims the set its action carries`() {
        val step = method(Lifecycle::class.java, "step", StepAction::class.java, Any::class.java)
        enable(components(names = listOf("a"), step = step))
        val action = StepAction()
        backend.call(step, Lifecycle(), action, null)
        assertTrue(action.items.isEmpty())
        backend.call(step, Lifecycle(), null, null)
    }

    @Test fun `a lifecycle step with another shape fails the install`() {
        failsToInstall(components(names = listOf("a"), step = method(OneParamLifecycle::class.java, "step", StepAction::class.java)), "does not take two parameters")
    }

    @Test fun `the comment and share buttons are found by id name and class`() {
        val comment = node("HPFrameLayout", Kind.FRAME).let { CleanNode(it.simpleName, it.className, null, null, Kind.FRAME, Any(), "comment_layout") }
        val otherFrame = CleanNode("HPFrameLayout", "x.HPFrameLayout", null, null, Kind.FRAME, Any(), "like_layout")
        val shareButton = node("FrameLayoutHoldTouchListener", Kind.FRAME)
        val tree = { root(comment, otherFrame, shareButton) }
        assertEquals(listOf(comment.handle), planRightButtons(tree(), comment = true, share = false))
        assertEquals(listOf(shareButton.handle), planRightButtons(tree(), comment = false, share = true))
        assertEquals(listOf(comment.handle, shareButton.handle), planRightButtons(tree(), comment = true, share = true))
    }

    @Test fun `building the right bar schedules the button sweeps`() {
        screen.root = root(CleanNode("HPFrameLayout", "x.HPFrameLayout", null, null, Kind.FRAME, Any(), "comment_layout"))
        enable(components(comment = true))
        backend.call(RightBar::class.java.getDeclaredConstructor())
        scheduler.advance(4000)
        assertEquals(4, screen.hidden.size)
        assertEquals(1, screen.hidden[0].first.gone.size)
    }
}
