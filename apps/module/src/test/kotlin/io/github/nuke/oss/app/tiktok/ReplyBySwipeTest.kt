package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Adapter
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Cell
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Entry
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Holder
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Msg
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Store
import io.github.nuke.oss.app.tiktok.feature.chat.ReplyBySwipe
import io.github.nuke.oss.app.tiktok.feature.chat.SwipeDetector
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for swipe-to-reply: the chat logic and row bookkeeping, the call into the reply step, and the swipe
// rule. Stand-ins replace the chat logic, the long-click provider and the adapter.
class ReplyBySwipeTest : HookHarness() {
    class Logic { fun refreshed() {}; init { last = this }; companion object { @JvmStatic var last: Logic? = null } }
    class Provider(val logic: Logic) {
        fun reply(message: Msg, flag: Boolean) { replies += Triple(logic, message, flag) }
        companion object { val replies = ArrayList<Triple<Logic, Msg, Boolean>>() }
    }
    class SourceProvider(val logic: Logic) {
        fun reply(message: Msg, source: String, flag: Boolean) { sources += Triple(message, source, flag) }
        companion object { val sources = ArrayList<Triple<Msg, String, Boolean>>() }
    }
    class WrongProvider(logic: Logic) { fun reply(message: Msg) {} }

    private val swipe = RecordingChatSwipe()
    private val foreground = FakeForeground("com.example.ChatActivity")
    private val logged = ArrayList<String>()
    private val refreshed = method(Logic::class.java, "refreshed")
    private val constructor = Adapter::class.java.getDeclaredConstructor(Any::class.java, Store::class.java)
    private val create = method(Adapter::class.java, "onCreateViewHolder", Any::class.java, Int::class.javaPrimitiveType!!)
    private val bind = method(Adapter::class.java, "onBindViewHolder", Holder::class.java, Int::class.javaPrimitiveType!!, List::class.java)

    private fun feature(reply: java.lang.reflect.Method = method(Provider::class.java, "reply", Msg::class.java, Boolean::class.javaPrimitiveType!!)) = ReplyBySwipe(
        resolver(ChatQueries.CHAT_LOGIC to listOf(refreshed), ChatQueries.REPLY_ACTION to listOf(reply)), loader,
        ChatAdapterEvents(loader, Adapter::class.java.name, Msg::class.java.name), { swipe }, foreground, Logger { logged += it },
    )

    // Builds the logic and an adapter over the messages, creates and binds a row for the first one, and returns the row.
    private fun chat(message: Msg, withLogic: Boolean = true): Any {
        if (withLogic) backend.call(Logic::class.java.getDeclaredConstructor())
        backend.call(constructor, null, null, Store(listOf(Entry(Cell(message)))))
        val adapter = checkNotNull(Adapter.last)
        val list = Any()
        val holder = backend.call(create, adapter, list, 0) as Holder
        backend.call(bind, adapter, holder, 0, emptyList<Any>())
        return holder.itemView
    }

    @Test fun `a swipe on a bound row replies to its message through the provider`() {
        Provider.replies.clear()
        enable(feature())
        val message = Msg(5, false)
        val row = chat(message)
        assertEquals(1, swipe.lists.size)
        swipe.handlers.single()(row)
        assertEquals(1, Provider.replies.size)
        assertSame(Logic.last, Provider.replies.single().first)
        assertSame(message, Provider.replies.single().second)
        assertFalse(Provider.replies.single().third)
    }

    @Test fun `the click source is passed where the host takes one`() {
        SourceProvider.sources.clear()
        enable(feature(method(SourceProvider::class.java, "reply", Msg::class.java, String::class.java, Boolean::class.javaPrimitiveType!!)))
        val message = Msg(5, true)
        swipe.handlers.clear()
        val row = chat(message)
        swipe.handlers.single()(row)
        assertEquals(listOf(Triple(message, "click_menu_reply", false)), SourceProvider.sources)
    }

    @Test fun `a swipe before the chat logic was seen is skipped and logged`() {
        Provider.replies.clear()
        enable(feature())
        val row = chat(Msg(5, false), withLogic = false)
        swipe.handlers.single()(row)
        assertTrue(Provider.replies.isEmpty())
        assertEquals(1, logged.size)
        assertTrue(logged.single().contains("reply_skipped"))
    }

    @Test fun `the merged forward viewer is left alone`() {
        Provider.replies.clear()
        foreground.name = "com.example.ShareMergeShowActivity"
        enable(feature())
        val row = chat(Msg(5, false))
        swipe.handlers.single()(row)
        assertTrue(Provider.replies.isEmpty())
    }

    @Test fun `a swipe on a row that was never bound does nothing`() {
        Provider.replies.clear()
        enable(feature())
        chat(Msg(5, false))
        swipe.handlers.single()(Any())
        assertTrue(Provider.replies.isEmpty())
    }

    @Test fun `a reply step of the wrong shape fails the install`() {
        failsToInstall(feature(method(WrongProvider::class.java, "reply", Msg::class.java)), "is not a reply step")
    }

    @Test fun `the swipe must be mostly sideways and far enough to the left`() {
        val detector = SwipeDetector()
        detector.down(900f, 500f)
        assertTrue(detector.up(600f, 520f, 1000))
        detector.down(900f, 500f)
        assertFalse(detector.up(800f, 500f, 1000))
        detector.down(900f, 500f)
        assertFalse(detector.up(600f, 900f, 1000))
        detector.down(300f, 500f)
        assertFalse(detector.up(600f, 500f, 1000))
        detector.down(900f, 500f)
        detector.cancel()
        assertFalse(detector.up(100f, 500f, 1000))
    }
}
