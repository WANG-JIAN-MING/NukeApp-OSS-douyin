package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.FilterComments
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the comment word filter across the host's older and newer comment steps, with stand-ins for the
// page of comments and the steps that receive it.
class FilterCommentsTest : HookHarness() {
    class Comment(@JvmField val text: String)
    class Page(@JvmField val items: MutableList<Any?>)
    class Old { fun handle(page: Page?, flag: Boolean) {} }
    class New { fun handle(source: Any?, page: Page?) {}; fun handle(other: String?) {} }
    class Reply { fun handle(page: Any?) {} }
    class Bare { fun handle(text: String?) {} }

    private fun page(vararg texts: String) = Page(texts.map { Comment(it) }.toMutableList<Any?>())
    private fun texts(page: Page) = page.items.map { (it as Comment).text }

    private fun feature(vararg steps: java.lang.reflect.Method, words: String = "spam\nads") = FilterComments(
        resolver(CommentQueries.LOADED_PAGES to steps.toList()), loader,
        values(FilterComments.ID, FilterComments.OPTIONS, "words" to words.split("\n")), pageName = Page::class.java.name,
    )

    private val old = method(Old::class.java, "handle", Page::class.java, Boolean::class.javaPrimitiveType!!)
    private val newer = method(New::class.java, "handle", Any::class.java, Page::class.java)
    private val reply = method(Reply::class.java, "handle", Any::class.java)

    @Test fun `comments with a blocked word are removed on every kind of step`() {
        enable(feature(old, newer, reply))
        val a = page("nice", "buy spam now", "ads", "ok")
        backend.call(old, Old(), a, true)
        assertEquals(listOf("nice", "ok"), texts(a))
        val b = page("x ads", "fine")
        backend.call(newer, New(), Any(), b)
        assertEquals(listOf("fine"), texts(b))
        val c = page("spam", "keep")
        backend.call(reply, Reply(), c)
        assertEquals(listOf("keep"), texts(c))
    }

    @Test fun `an untyped step leaves arguments that are not pages alone`() {
        enable(feature(reply))
        backend.call(reply, Reply(), "spam")
        backend.call(reply, Reply(), null)
    }

    @Test fun `a step that takes no page fails the install`() {
        failsToInstall(feature(method(Bare::class.java, "handle", String::class.java)), "None of the")
    }

    @Test fun `without words nothing is hooked`() {
        enable(feature(old, words = ""))
        assertEquals(0, backend.attachments.get())
    }

    @Test fun `turning the feature off stops the filtering`() {
        val feature = feature(old)
        enable(feature)
        disable(feature)
        val p = page("spam")
        backend.call(old, Old(), p, true)
        assertTrue(texts(p).isNotEmpty())
    }
}
