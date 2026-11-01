package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.R
import io.github.nuke.oss.app.tiktok.feature.comment.CommentDetails
import io.github.nuke.oss.app.tiktok.feature.comment.CommentLog
import io.github.nuke.oss.app.tiktok.feature.comment.CommentRecord
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the details row of a comment's long-press menu and its bookmarks.
class CommentDetailsTest : HookHarness() {
    class User(@JvmField val nickname: String, @JvmField val uid: String)
    class Comment(
        @JvmField val cid: String?, @JvmField val awemeId: String, @JvmField val text: String, @JvmField val createTime: Long,
        @JvmField val diggCount: Int, @JvmField val replyCommentTotal: Long, @JvmField val ipLabel: String?, @JvmField val status: Int,
        @JvmField val user: User?,
    )
    class MenuModel(@JvmField val comment: Comment?)
    object MenuProvider { @JvmStatic fun items(model: MenuModel?, other: Any?): List<Any> = emptyList() }
    class MenuView(@Suppress("UNUSED_PARAMETER") context: android.content.Context?, @Suppress("UNUSED_PARAMETER") attributes: android.util.AttributeSet?, @Suppress("UNUSED_PARAMETER") style: Int)

    private val prompts = RecordingPrompts()

    private val menuItems = method(MenuProvider::class.java, "items", MenuModel::class.java, Any::class.java)
    private val menuView = MenuView::class.java.getDeclaredConstructor(android.content.Context::class.java, android.util.AttributeSet::class.java, Int::class.javaPrimitiveType)
    private val copier = RecordingTextCopier()
    private val rows = RecordingCommentDetailsRow()
    private var clock = 10_000L

    private val entries = RecordingSharePanelEntries()
    private val pages = RecordingCommentPages()
    private val marks = CommentLog(java.io.File(java.nio.file.Files.createTempDirectory("marks").toFile(), "marked.json"))

    private fun details(vararg saved: Pair<String, Any>) = CommentDetails(
        resolver(CommentQueries.LONG_PRESS_PANEL to listOf(menuItems)), loader, { rows }, prompts, { copier },
        values(CommentDetails.ID, CommentDetails.OPTIONS, *saved), marks, entries, { pages }, Logger {}, { _, task -> task() },
        Comment::class.java.name, MenuView::class.java.name, { clock },
    )

    private val comment = Comment("c9", "p1", "nice", 1_700_000_000, 12, 3, "上海", 1, User("Ann", "42"))

    @Test fun `a comment's menu gets a details row that copies the text`() {
        enable(details())
        backend.call(menuItems, null, MenuModel(comment), null)
        backend.call(menuView, null, null, null, 0)
        val (_, buttons) = rows.attached.single()
        assertEquals(listOf(R.string.host_comment_details_entry, R.string.host_comment_bookmark), buttons.map { it.label })
        buttons[0].onClick()
        val (message, args, copy) = prompts.confirms.single()
        assertEquals(R.string.host_comment_details_message, message)
        assertEquals(listOf("Ann", "42"), args.take(2))
        assertEquals(listOf<Any>(12, 3L, "上海", 1, "c9", "p1", "nice"), args.drop(3))
        copy()
        assertEquals(listOf("nice"), copier.copied)
    }

    @Test fun `the row is added whichever comes first`() {
        enable(details())
        backend.call(menuView, null, null, null, 0)
        clock += 500
        backend.call(menuItems, null, MenuModel(comment), null)
        assertEquals(1, rows.attached.size)
    }

    @Test fun `a menu view long after a comment's menu, or without one, gets no row`() {
        enable(details())
        backend.call(menuView, null, null, null, 0)
        clock += 10_000
        backend.call(menuItems, null, MenuModel(comment), null)
        assertTrue(rows.attached.isEmpty())
        backend.call(menuItems, null, MenuModel(null), null)
        assertTrue(rows.attached.isEmpty())
        assertNull(prompts.confirms.firstOrNull())
    }

    @Test fun `a bookmarked comment is listed newest first and opens in its post`() {
        enable(details())
        backend.call(menuItems, null, MenuModel(comment), null)
        backend.call(menuView, null, null, null, 0)
        rows.attached.single().second[1].onClick()
        assertEquals(listOf(CommentRecord("p1", "c9", "nice", 1_700_000_000)), marks.read())
        assertEquals(R.string.host_comment_bookmarked, prompts.toasts.single().first)
        marks.add(CommentRecord("p2", "c10", "later", 2))
        val activity = Any()
        entries.added.single().onClick(activity, Any()) {}
        val choice = prompts.choices.single()
        assertEquals(listOf<Any>(R.string.host_comment_bookmarks_clear, "later", "nice"), choice.items)
        choice.onChosen(2)
        assertEquals(listOf(Triple(activity, "p1", "c9")), pages.opened)
        choice.onChosen(0)
        prompts.confirms.single().third()
        assertTrue(marks.read().isEmpty())
    }

    @Test fun `without bookmarks there is only the details button and no list`() {
        enable(details(CommentDetails.BOOKMARKS to false))
        backend.call(menuItems, null, MenuModel(comment), null)
        backend.call(menuView, null, null, null, 0)
        assertEquals(listOf(R.string.host_comment_details_entry), rows.attached.single().second.map { it.label })
        assertTrue(entries.added.isEmpty())
    }
}
