package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.feature.comment.CustomCommentStyle
import io.github.nuke.oss.app.tiktok.query.CommentQueries
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for the comment markup styles, with stand-ins for the publish request and the text extras.
class CustomCommentStyleTest : HookHarness() {
    class Extra { @JvmField var type = 0; @JvmField var start = 0; @JvmField var end = 0; @JvmField var userId: String? = null }
    class Publish(@JvmField var text: CharSequence?, @JvmField val extras: MutableList<Any?>)
    class Sender { fun publish(request: Publish?, scene: String?) {} }
    class TwoTexts(@JvmField val a: String, @JvmField val b: String, @JvmField val list: MutableList<Any?>)
    class WrongSender { fun publish(request: TwoTexts?) {} }
    // The shape of the request in Douyin 40.7.0: three more String fields and a second list that is assigned later.
    class ClickParams(
        @JvmField var text: CharSequence?, @JvmField val extras: MutableList<Any?>, @JvmField val method: String,
        @JvmField val order: String = "", @JvmField var source: String? = null, @JvmField var later: MutableList<Any?>? = null,
    )
    class ClickSender { fun publish(request: ClickParams?) {} }
    class TwoLists(@JvmField val text: CharSequence?, @JvmField val a: MutableList<Any?>, @JvmField val b: MutableList<Any?>)
    class TwoListsSender { fun publish(request: TwoLists?) {} }

    private val logged = ArrayList<String>()
    private val publish = method(Sender::class.java, "publish", Publish::class.java, String::class.java)

    private fun style(kind: String, user: String? = "u1", sender: java.lang.reflect.Method = publish) = CustomCommentStyle(
        resolver(CommentQueries.COMMENT_SEND to listOf(sender)), loader, Logger { logged += it },
        values(CustomCommentStyle.ID, CustomCommentStyle.OPTIONS, "style" to kind), { user }, Extra::class.java.name,
    )

    @Test fun `the hashtag style marks the whole text and adds an extra when there is none`() {
        enable(style("hashtag"))
        val request = Publish("hello", mutableListOf())
        backend.call(publish, Sender(), request, "scene")
        val extra = request.extras.single() as Extra
        assertEquals("hello", request.text.toString())
        assertEquals(listOf(1, 0, 5), listOf(extra.type, extra.start, extra.end))
    }

    @Test fun `the mention style points at the user and ends the text with an at sign`() {
        enable(style("mention"))
        val existing = Extra().apply { type = 9 }
        val request = Publish("hi", mutableListOf(existing))
        backend.call(publish, Sender(), request, "scene")
        assertEquals("hi@", request.text.toString())
        assertEquals(listOf(0, 0, 3), listOf(existing.type, existing.start, existing.end))
        assertEquals("u1", existing.userId)
    }

    @Test fun `the location style puts the marker in front and covers all of it`() {
        enable(style("location"))
        val request = Publish("here", mutableListOf())
        backend.call(publish, Sender(), request, "scene")
        assertEquals("[位置]here", request.text.toString())
        val extra = request.extras.single() as Extra
        assertEquals(listOf(33, 0, 8), listOf(extra.type, extra.start, extra.end))
    }

    @Test fun `an empty comment is left alone`() {
        enable(style("hashtag"))
        val request = Publish("", mutableListOf())
        backend.call(publish, Sender(), request, "scene")
        assertTrue(request.extras.isEmpty())
    }

    @Test fun `the mention style without a known user leaves the comment as written`() {
        enable(style("mention", user = null))
        val request = Publish("hi", mutableListOf(Extra()))
        backend.call(publish, Sender(), request, "scene")
        assertEquals("hi", request.text.toString())
        val extra = request.extras.single() as Extra
        assertEquals(listOf(0, 0, 0), listOf(extra.type, extra.start, extra.end))
        assertTrue(logged.any { it.contains("user_unavailable") })
    }

    @Test fun `the user is read when the comment is published`() {
        var user: String? = null
        enable(CustomCommentStyle(
            resolver(CommentQueries.COMMENT_SEND to listOf(publish)), loader, Logger { logged += it },
            values(CustomCommentStyle.ID, CustomCommentStyle.OPTIONS, "style" to "mention"), { user }, Extra::class.java.name,
        ))
        user = "u2"
        val request = Publish("hi", mutableListOf(Extra()))
        backend.call(publish, Sender(), request, "scene")
        assertEquals("hi@", request.text.toString())
        assertEquals("u2", (request.extras.single() as Extra).userId)
    }

    @Test fun `extra string fields and a second list do not hide the text and the extras`() {
        enable(style("hashtag", sender = method(ClickSender::class.java, "publish", ClickParams::class.java)))
        val request = ClickParams("hello", mutableListOf(), "click", later = mutableListOf())
        backend.call(method(ClickSender::class.java, "publish", ClickParams::class.java), ClickSender(), request)
        val extra = request.extras.single() as Extra
        assertEquals(listOf(1, 0, 5), listOf(extra.type, extra.start, extra.end))
        assertEquals(true, request.later?.isEmpty())
    }

    @Test fun `two final lists still fail the install`() {
        failsToInstall(style("hashtag", sender = method(TwoListsSender::class.java, "publish", TwoLists::class.java)), "Expected one list field")
    }

    @Test fun `a publish request with two texts fails the install`() {
        failsToInstall(style("hashtag", sender = method(WrongSender::class.java, "publish", TwoTexts::class.java)), "Expected one text field")
    }
}
