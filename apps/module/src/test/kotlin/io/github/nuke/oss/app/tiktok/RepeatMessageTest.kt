package io.github.nuke.oss.app.tiktok

import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Adapter
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Cell
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Entry
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Holder
import io.github.nuke.oss.app.tiktok.MessageSendTimeTest.Store
import io.github.nuke.oss.app.tiktok.feature.chat.RepeatMessage
import io.github.nuke.oss.app.tiktok.query.ChatQueries
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import io.github.nuke.oss.core.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for repeating a chat message: the copy that is built, how it is sent and what happens when the pieces
// are missing. Stand-ins replace the message, the request listener and the IM SDK's sender.
class RepeatMessageTest : HookHarness() {
    class Msg() {
        private var uuid: String? = null
        private var text: String? = null
        private var conversation: String? = null
        private var conversationKind: Int = 0
        private var kind: Int = 0
        var own = false
        constructor(conversation: String, text: String?, conversationKind: Int, kind: Int) : this() {
            this.conversation = conversation; this.text = text; this.conversationKind = conversationKind; this.kind = kind
        }
        fun getUuid() = uuid
        fun getContent() = text
        fun getConversationId() = conversation
        fun getConversationType() = conversationKind
        fun getMsgType() = kind
        fun isSelf() = own
        fun setUuid(value: String?) { uuid = value }
        fun setContent(value: String?) { text = value }
        fun setConversationId(value: String?) { conversation = value }
        fun setConversationType(value: Int) { conversationKind = value }
        fun setMsgType(value: Int) { kind = value }
    }
    interface Listener { fun onSuccess(); fun onFailure() }
    class Sender {
        val sent = ArrayList<Pair<Msg, Triple<Map<*, *>?, Listener?, Int>>>()
        fun send(message: Msg?, extras: Map<*, *>?, listener: Listener?, retries: Int, extra: Any?) {
            sent += message!! to Triple(extras, listener, retries)
        }
        companion object { @JvmField var INSTANCE: Sender? = null }
    }
    class WrongSender { fun send(message: Msg?, extras: Map<*, *>?, listener: Listener?) {} }

    private val logged = ArrayList<String>()
    private val marks = RecordingRepeatMarks()
    private val send = method(Sender::class.java, "send", Msg::class.java, Map::class.java, Listener::class.java, Int::class.javaPrimitiveType!!, Any::class.java)
    private val constructor = Adapter::class.java.getDeclaredConstructor(Any::class.java, Store::class.java)
    private val create = method(Adapter::class.java, "onCreateViewHolder", Any::class.java, Int::class.javaPrimitiveType!!)
    private val bind = method(Adapter::class.java, "onBindViewHolder", Holder::class.java, Int::class.javaPrimitiveType!!, List::class.java)

    // Keeps the side the mark was bound for, which the plain recording marks do not see.
    private class SideMarks : io.github.nuke.oss.app.tiktok.feature.chat.RepeatMarks {
        val sides = ArrayList<Boolean>()
        override fun prepare(row: Any) {}
        override fun onTap(row: Any, action: () -> Unit) { error("The bound form with the side is expected") }
        override fun onTap(row: Any, mine: Boolean, action: () -> Unit) { sides += mine }
    }

    private fun feature(sendStep: java.lang.reflect.Method = send, ui: io.github.nuke.oss.app.tiktok.feature.chat.RepeatMarks = marks) = RepeatMessage(
        resolver(ChatQueries.SEND_MESSAGE to listOf(sendStep)), loader,
        ChatAdapterEvents(loader, Adapter::class.java.name, Msg::class.java.name), { ui }, Logger { logged += it },
        Msg::class.java.name, Listener::class.java.name,
    )

    // Builds the adapter over one message and binds the first row to it.
    private fun chat(message: Msg): Any {
        backend.call(constructor, null, null, Store(listOf(Entry(Cell(message)))))
        val adapter = checkNotNull(Adapter.last)
        val holder = backend.call(create, adapter, Any(), 0) as Holder
        backend.call(bind, adapter, holder, 0, emptyList<Any>())
        return holder.itemView
    }

    @Test fun `tapping the mark sends a copy into the same conversation`() {
        enable(feature())
        val sender = Sender().also { Sender.INSTANCE = it }
        val original = Msg("conv-1", "hello", 1, 7)
        val row = chat(original)
        assertEquals(listOf(row), marks.prepared)
        marks.taps.getValue(row)()
        val (copy, rest) = sender.sent.single()
        assertEquals("conv-1", copy.getConversationId())
        assertEquals("hello", copy.getContent())
        assertEquals(1, copy.getConversationType())
        assertEquals(7, copy.getMsgType())
        assertNotEquals(original.getUuid(), copy.getUuid())
        assertTrue(copy.getUuid()!!.isNotEmpty())
        assertEquals(emptyMap<Any, Any>(), rest.first)
        assertEquals(0, rest.third)
        assertTrue(logged.isEmpty())
    }

    @Test fun `the mark is told which side the bubble is on`() {
        val sides = SideMarks()
        enable(feature(ui = sides))
        chat(Msg("conv-1", "mine", 1, 7).also { it.own = true })
        assertEquals(listOf(true), sides.sides)
    }

    @Test fun `a message that does not say who sent it is treated as the other side`() {
        val sides = SideMarks()
        enable(feature(ui = sides))
        chat(Msg("conv-1", "theirs", 1, 7))
        assertEquals(listOf(false), sides.sides)
    }

    @Test fun `a message without content is not sent`() {
        enable(feature())
        val sender = Sender().also { Sender.INSTANCE = it }
        val row = chat(Msg("conv-1", null, 1, 7))
        marks.taps.getValue(row)()
        assertTrue(sender.sent.isEmpty())
        assertTrue(logged.single().contains("repeat_skipped"))
    }

    @Test fun `without a sender instance nothing is sent and it is logged`() {
        enable(feature())
        Sender.INSTANCE = null
        val row = chat(Msg("conv-1", "x", 1, 7))
        marks.taps.getValue(row)()
        assertTrue(logged.single().contains("no sender instance"))
    }

    @Test fun `a send step of the wrong shape fails the install`() {
        failsToInstall(feature(method(WrongSender::class.java, "send", Msg::class.java, Map::class.java, Listener::class.java)), "is not a send step")
    }
}
