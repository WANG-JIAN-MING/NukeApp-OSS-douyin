package io.github.nuke.oss.app.tiktok

import android.view.View
import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.general.*
import io.github.nuke.oss.app.tiktok.query.ConfirmQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.*
import org.junit.Test

// Contract tests for asking before a tap, against stand-ins for the host classes. They prove the hold-back and replay
// semantics, not that any Douyin build has these targets.
class ConfirmActionsTest : HookHarness() {
    class Follower {
        val followed = ArrayList<String?>()
        fun follow(userId: String?) { followed += userId }
    }
    class BoolFollower { fun follow(userId: String?): Boolean = true }
    class TwoArgFollower { fun follow(userId: String?, source: Int) {} }

    class LikeButton {
        var likes = 0
        fun onClick(view: View?) { likes++ }
    }
    class NoListener { fun other() {} }

    class Author(@JvmField var live_status: Long)
    class LiveVideo(@JvmField var author: Any?)
    class AvatarPresenter {
        var opened = 0
        fun open(a: Any?, b: Any?, video: Any?): Boolean { opened++; return false }
        fun open(a: Any?, b: Any?, video: Any?, c: Int): Boolean { opened++; return false }
    }

    class Entry {
        var entered = 0
        fun enter() { entered++ }
        fun enter(source: String?) { entered++ }
    }
    class ValueEntry { fun enter(): String = "x" }
    class BoolEntry { fun enter(): Boolean = true }

    class Collector {
        var collected = 0
        fun click(done: Function1<Any?, Unit>?, failed: Function2<Any?, Any?, Unit>?) { collected++ }
        fun click(done: Function1<Any?, Unit>?) { collected++ }
    }

    private val prompts = RecordingPrompts()
    private val logged = ArrayList<String>()

    private fun confirm(vararg picked: String, targets: Array<Pair<io.github.nuke.oss.app.shared.dex.DexQuery, List<java.lang.reflect.Method>>>) =
        ConfirmBeforeActions(
            resolver(*targets), loader,
            values(ConfirmBeforeActions.ID, ConfirmBeforeActions.OPTIONS, "events" to picked.toSet()), prompts, Logger { logged += it },
        )

    private val follow = method(Follower::class.java, "follow", String::class.java)

    @Test fun `a follow is held back until confirmed and then runs once with its arguments`() {
        val feature = confirm(ConfirmBeforeActions.FOLLOW, targets = arrayOf(ConfirmQueries.FOLLOW to listOf(follow)))
        enable(feature)
        val host = Follower()
        backend.call(follow, host, "user-1")
        assertTrue(host.followed.isEmpty())
        val (message, _, onConfirm) = prompts.confirms.single()
        assertEquals(R.string.host_confirm_follow, message)
        onConfirm()
        assertEquals(listOf<String?>("user-1"), host.followed)
        backend.call(follow, host, "user-2")
        assertEquals(listOf<String?>("user-1"), host.followed)
        assertEquals(2, prompts.confirms.size)
    }

    @Test fun `when no dialog can be shown the tap goes through`() {
        enable(confirm(ConfirmBeforeActions.FOLLOW, targets = arrayOf(ConfirmQueries.FOLLOW to listOf(follow))))
        val host = Follower()
        backend.call(follow, host, "user-1")
        prompts.unavailable.single()()
        assertEquals(listOf<String?>("user-1"), host.followed)
    }

    @Test fun `unchosen events and a disabled feature hook nothing or nothing more`() {
        val none = confirm(targets = arrayOf(ConfirmQueries.FOLLOW to listOf(follow)))
        enable(none)
        assertEquals(0, backend.attachments.get())
        disable(none)
        val host = Follower()
        backend.call(follow, host, "a")
        assertEquals(listOf<String?>("a"), host.followed)
        assertTrue(prompts.confirms.isEmpty())
    }

    @Test fun `the like listener is held back and replayed`() {
        val onClick = method(LikeButton::class.java, "onClick", View::class.java)
        enable(confirm(ConfirmBeforeActions.LIKE, targets = arrayOf(ConfirmQueries.LIKE to listOf(method(LikeButton::class.java, "onClick", View::class.java)))))
        val host = LikeButton()
        backend.call(onClick, host, null)
        assertEquals(0, host.likes)
        prompts.confirms.single().third()
        assertEquals(1, host.likes)
    }

    @Test fun `a live avatar is held back only while its author is live`() {
        val open = method(AvatarPresenter::class.java, "open", Any::class.java, Any::class.java, Any::class.java)
        enable(confirm(ConfirmBeforeActions.LIVE, targets = arrayOf(ConfirmQueries.LIVE_AVATAR to listOf(open))))
        val host = AvatarPresenter()
        assertEquals(true, backend.call(open, host, null, null, LiveVideo(Author(1))))
        assertEquals(0, host.opened)
        assertEquals(false, backend.call(open, host, null, null, LiveVideo(Author(0))))
        assertEquals(false, backend.call(open, host, null, null, null))
        assertEquals(2, host.opened)
        prompts.confirms.single().third()
        assertEquals(3, host.opened)
    }

    @Test fun `every overload of a name is held back`() {
        val enter = method(Entry::class.java, "enter")
        enable(confirm(ConfirmBeforeActions.TOPIC, targets = arrayOf(ConfirmQueries.TOPIC to listOf(enter))))
        val host = Entry()
        backend.call(enter, host)
        backend.call(method(Entry::class.java, "enter", String::class.java), host, "s")
        assertEquals(0, host.entered)
        assertEquals(2, prompts.confirms.size)
    }

    @Test fun `a replay only lets its own call through`() {
        val enter = method(Entry::class.java, "enter")
        enable(confirm(ConfirmBeforeActions.TOPIC, ConfirmBeforeActions.FULL_SCREEN, targets = arrayOf(
            ConfirmQueries.TOPIC to listOf(enter), ConfirmQueries.FULL_SCREEN to listOf(method(Entry::class.java, "enter", String::class.java)),
        )))
        val host = Entry()
        backend.call(enter, host)
        prompts.confirms.single().third()
        assertEquals(1, host.entered)
        backend.call(enter, host)
        assertEquals(1, host.entered)
    }

    class CommentDigg {
        var likes = 0
        fun like(comment: Any?, flag: Boolean) { likes++ }
        fun like(comment: Any?) { likes++ }
    }

    @Test fun `a comment like is held back and replayed for every overload`() {
        val like = method(CommentDigg::class.java, "like", Any::class.java, Boolean::class.javaPrimitiveType!!)
        val single = method(CommentDigg::class.java, "like", Any::class.java)
        enable(confirm(ConfirmBeforeActions.COMMENT_LIKE, targets = arrayOf(ConfirmQueries.COMMENT_LIKE to listOf(like))))
        val host = CommentDigg()
        backend.call(like, host, "c", true)
        backend.call(single, host, "c")
        assertEquals(0, host.likes)
        assertEquals(listOf(R.string.host_confirm_comment_like, R.string.host_confirm_comment_like), prompts.confirms.map { it.first })
        prompts.confirms.first().third()
        assertEquals(1, host.likes)
    }

    @Test fun `a favourite is held back until confirmed and then runs once`() {
        val click = method(Collector::class.java, "click", Function1::class.java, Function2::class.java)
        enable(confirm(ConfirmBeforeActions.COLLECT, targets = arrayOf(ConfirmQueries.COLLECT to listOf(click))))
        val host = Collector()
        val done: (Any?) -> Unit = {}
        backend.call(click, host, done, null)
        assertEquals(0, host.collected)
        val (message, _, onConfirm) = prompts.confirms.single()
        assertEquals(R.string.host_confirm_collect, message)
        onConfirm()
        assertEquals(1, host.collected)
    }

    @Test fun `a favourite click without both callbacks fails the install`() {
        failsToInstall(
            confirm(ConfirmBeforeActions.COLLECT, targets = arrayOf(ConfirmQueries.COLLECT to listOf(method(Collector::class.java, "click", Function1::class.java)))),
            "does not take the favourite callbacks",
        )
    }

    @Test fun `a method with the wrong shape or return type fails the install`() {
        failsToInstall(
            confirm(ConfirmBeforeActions.FOLLOW, targets = arrayOf(ConfirmQueries.FOLLOW to listOf(method(TwoArgFollower::class.java, "follow", String::class.java, Int::class.javaPrimitiveType!!)))),
            "does not take only the user ID",
        )
    }

    @Test fun `a boolean the event does not expect fails the install`() {
        failsToInstall(
            confirm(ConfirmBeforeActions.TOPIC, targets = arrayOf(ConfirmQueries.TOPIC to listOf(method(BoolEntry::class.java, "enter")))),
            "returns boolean, which",
        )
    }

    @Test fun `a non-void result the event cannot answer fails the install`() {
        failsToInstall(
            confirm(ConfirmBeforeActions.TOPIC, targets = arrayOf(ConfirmQueries.TOPIC to listOf(method(ValueEntry::class.java, "enter")))),
            "returns java.lang.String",
        )
    }

    @Test fun `a listener class without onClick fails the install`() {
        failsToInstall(
            confirm(ConfirmBeforeActions.LIKE, targets = arrayOf(ConfirmQueries.LIKE to listOf(method(NoListener::class.java, "other")))),
            "Expected one onClick(View)",
        )
    }
}
