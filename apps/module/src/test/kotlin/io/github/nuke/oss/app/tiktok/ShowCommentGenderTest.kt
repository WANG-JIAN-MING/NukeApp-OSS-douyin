package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.ShowCommentGender
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import org.junit.Assert.assertEquals
import org.junit.Test

// Contract tests for the commenter gender mark, with stand-ins for the comment, its author and the cell step.
class ShowCommentGenderTest : HookHarness() {
    class Author(@JvmField val gender: Int)
    class Comment(@JvmField val user: Author?, @JvmField var ip_label: String?)
    // The shape in Douyin 40.7.0: camelCase fields, and a commentUser that wins over user when it is set.
    class HostComment(@JvmField val user: Author?, @JvmField val commentUser: Author?, @JvmField var ipLabel: String?)
    class HostCell { fun publish(comment: HostComment?) {} }
    class Cell { fun publish(flag: Boolean, comment: Comment?) {}; fun publish(comment: Comment?) {} }
    class NoComment { fun publish(text: String?) {} }

    private val two = method(Cell::class.java, "publish", Boolean::class.javaPrimitiveType!!, Comment::class.java)
    private val one = method(Cell::class.java, "publish", Comment::class.java)

    private fun feature(vararg steps: java.lang.reflect.Method = arrayOf(two, one)) =
        ShowCommentGender(resolver(CommentQueries.COMMENT_IP_READERS to steps.toList()), loader, Comment::class.java.name)

    @Test fun `the mark follows the gender and every overload is hooked`() {
        enable(feature())
        val man = Comment(Author(1), "Beijing")
        val woman = Comment(Author(2), "Shanghai")
        backend.call(two, Cell(), true, man)
        backend.call(one, Cell(), woman)
        assertEquals("Beijing(♂)", man.ip_label)
        assertEquals("Shanghai(♀)", woman.ip_label)
    }

    @Test fun `a comment bound twice is marked once`() {
        enable(feature())
        val comment = Comment(Author(1), "Beijing")
        backend.call(one, Cell(), comment)
        backend.call(one, Cell(), comment)
        assertEquals("Beijing(♂)", comment.ip_label)
    }

    @Test fun `an unknown gender, a missing label or author is left alone`() {
        enable(feature())
        val unknown = Comment(Author(0), "Beijing")
        val noLabel = Comment(Author(1), null)
        val noAuthor = Comment(null, "Beijing")
        backend.call(one, Cell(), unknown)
        backend.call(one, Cell(), noLabel)
        backend.call(one, Cell(), noAuthor)
        backend.call(one, Cell(), null)
        assertEquals("Beijing", unknown.ip_label)
        assertEquals(null, noLabel.ip_label)
        assertEquals("Beijing", noAuthor.ip_label)
    }

    @Test fun `the camelCase fields of the current host are read and the comment user wins`() {
        val step = method(HostCell::class.java, "publish", HostComment::class.java)
        enable(ShowCommentGender(resolver(CommentQueries.COMMENT_IP_READERS to listOf(step)), loader, HostComment::class.java.name))
        val viaUser = HostComment(Author(1), null, "Beijing")
        val viaCommentUser = HostComment(Author(1), Author(2), "Shanghai")
        backend.call(step, HostCell(), viaUser)
        backend.call(step, HostCell(), viaCommentUser)
        assertEquals("Beijing(♂)", viaUser.ipLabel)
        assertEquals("Shanghai(♀)", viaCommentUser.ipLabel)
    }

    @Test fun `a comment user without a known gender falls back to the user`() {
        val step = method(HostCell::class.java, "publish", HostComment::class.java)
        val messages = ArrayList<String>()
        enable(ShowCommentGender(resolver(CommentQueries.COMMENT_IP_READERS to listOf(step)), loader, HostComment::class.java.name, io.github.nuke.oss.core.Logger { messages += it }))
        val fallback = HostComment(Author(2), Author(0), "Shanghai")
        val neither = HostComment(Author(0), Author(0), "Beijing")
        backend.call(step, HostCell(), fallback)
        backend.call(step, HostCell(), neither)
        backend.call(step, HostCell(), neither)
        assertEquals("Shanghai(♀)", fallback.ipLabel)
        assertEquals("Beijing", neither.ipLabel)
        assertEquals(1, messages.count { it.contains("gender_unknown commentUser=0 user=0") })
        assertEquals(1, messages.count { it.endsWith("marked") })
    }

    @Test fun `a step that takes no comment fails the install`() {
        failsToInstall(feature(method(NoComment::class.java, "publish", String::class.java)), "takes")
    }
}
