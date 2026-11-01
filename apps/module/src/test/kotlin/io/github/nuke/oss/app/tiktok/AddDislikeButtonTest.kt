package io.github.nuke.oss.app.tiktok

import com.ss.android.ugc.aweme.feed.model.Aweme
import com.ss.android.ugc.aweme.sharer.ui.SharePackage
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.feed.AddDislikeButton
import io.github.nuke.oss.app.tiktok.feature.feed.DislikeButton
import io.github.nuke.oss.app.tiktok.feature.feed.DislikePlacement
import io.github.nuke.oss.app.tiktok.feature.feed.dislikeSlot
import io.github.nuke.oss.app.tiktok.query.FeedQueries
import io.github.nuke.oss.app.tiktok.sdk.feed.FeedEvents
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the dislike button: the right-hand column hook, the click handling and the action it replays, with
// stand-ins for the column, the share package and the action. They prove the wiring, not a Douyin build.
class AddDislikeButtonTest : HookHarness() {
    class RightBar
    class Shared(val aweme: Aweme, val builder: SharePackage.Builder) { fun marker() {} }
    // Each run builds a fresh action, so the latest one is kept for the test to look at.
    class Action(val aweme: Aweme, val share: Shared, val page: String, val element: String, val extra: String, val flag: Int) {
        var ran: Pair<Any, Shared>? = null
        init { last = this }
        fun dislike(activity: Any, share: Shared) { ran = activity to share }
        companion object { @JvmStatic var last: Action? = null }
    }
    class Unbuildable { fun dislike(activity: Any, share: Shared) {} }
    class Wrong { fun dislike(activity: Any) {} }
    class Changer { fun change(aweme: Aweme?, from: String?) {} }
    class Pager { fun setCurrentItemInternal(item: Int, smooth: Boolean, always: Boolean, velocity: Int) {} }

    private val change = method(Changer::class.java, "change", Aweme::class.java, String::class.java)
    private val prompts = RecordingPrompts()
    private val ui = RecordingDislikeButton()
    private val logged = ArrayList<String>()
    private fun feature(dislike: java.lang.reflect.Method = method(Action::class.java, "dislike", Any::class.java, Shared::class.java), vararg saved: Pair<String, Any>): AddDislikeButton {
        val resolver = resolver(
            FeedQueries.AWEME_CHANGED to listOf(change),
            FeedQueries.PAGER_SELECT to listOf(method(Pager::class.java, "setCurrentItemInternal",
                Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)),
            FeedQueries.DISLIKE_ACTION to listOf(dislike),
            FeedQueries.SHARE_PACKAGE to listOf(method(Shared::class.java, "marker")),
        )
        return AddDislikeButton(
            resolver, loader, FeedEvents(resolver, loader), { ui }, prompts, Logger { logged += it },
            values(AddDislikeButton.ID, AddDislikeButton.OPTIONS, *saved), RightBar::class.java.name,
        )
    }

    private fun built() = backend.call(RightBar::class.java.getDeclaredConstructor())

    @Test fun `a built right column gets the button at the chosen place`() {
        enable(feature(saved = arrayOf("position" to "like")))
        built()
        assertEquals(1, ui.attached.size)
        assertEquals(DislikePlacement.AFTER_LIKE, ui.attached.single().placement)
        assertTrue(ui.attached.single().bar is RightBar)
        assertEquals(1, prompts.installs)
    }

    @Test fun `tapping the button runs the dislike action for the video on screen`() {
        enable(feature())
        val aweme = Aweme("a")
        backend.call(change, Changer(), aweme, "")
        built()
        val activity = Any()
        Action.last = null
        ui.attached.single().onClick(activity)
        val action = checkNotNull(Action.last)
        assertSame(aweme, action.aweme)
        assertEquals("homepage_hot", action.page)
        assertEquals("click_share_button", action.element)
        assertSame(activity, action.ran?.first)
        assertSame(action.share, action.ran?.second)
        assertEquals("disLike", action.share.builder.type)
        assertSame(aweme, action.share.aweme)
        assertTrue(prompts.toasts.isEmpty())
    }

    @Test fun `with confirmation the action waits for the dialog`() {
        enable(feature(saved = arrayOf("confirm" to true)))
        backend.call(change, Changer(), Aweme("a"), "")
        built()
        ui.attached.single().onClick(Any())
        assertEquals(1, prompts.confirms.size)
        assertEquals(R.string.host_confirm_dislike, prompts.confirms.single().first)
        Action.last = null
        assertNull(Action.last)
        prompts.confirms.single().third()
        assertTrue(checkNotNull(Action.last).ran != null)
        assertTrue(prompts.toasts.isEmpty())
    }

    @Test fun `a tap before any video is known says it could not act`() {
        enable(feature())
        built()
        ui.attached.single().onClick(Any())
        assertEquals(listOf(R.string.host_dislike_failed to emptyList<Any>()), prompts.toasts)
    }

    @Test fun `an action the host cannot build is reported not thrown`() {
        enable(feature(dislike = method(Unbuildable::class.java, "dislike", Any::class.java, Shared::class.java)))
        backend.call(change, Changer(), Aweme("a"), "")
        built()
        ui.attached.single().onClick(Any())
        assertEquals(listOf(R.string.host_dislike_failed to emptyList<Any>()), prompts.toasts)
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("dislike_failed"))
    }

    @Test fun `a dislike method of the wrong shape fails the install`() {
        failsToInstall(feature(dislike = method(Wrong::class.java, "dislike", Any::class.java)), "does not take an Activity")
    }

    @Test fun `the slot follows the host button the placement names`() {
        val children = listOf(
            listOf("头像"), listOf("点赞按钮 未选中", "1.2w"), listOf("评论按钮", "300"), listOf("收藏按钮 未选中"), listOf("分享按钮"),
        )
        assertEquals(-1, dislikeSlot(DislikePlacement.END, children))
        assertEquals(2, dislikeSlot(DislikePlacement.AFTER_LIKE, children))
        assertEquals(1, dislikeSlot(DislikePlacement.BELOW_AVATAR, children))
        assertEquals(3, dislikeSlot(DislikePlacement.AFTER_COMMENT, children))
        assertEquals(4, dislikeSlot(DislikePlacement.AFTER_FAVORITE, children))
        assertEquals(-1, dislikeSlot(DislikePlacement.AFTER_FAVORITE, listOf(listOf("收藏"))))
        assertSame(DislikePlacement.END, DislikePlacement.of("unknown"))
    }
}
