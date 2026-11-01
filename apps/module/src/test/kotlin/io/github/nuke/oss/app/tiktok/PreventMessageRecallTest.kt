package io.github.nuke.oss.app.tiktok

import com.bytedance.im.core.model.Message
import io.github.nuke.oss.app.tiktok.feature.chat.KeptMessages
import io.github.nuke.oss.app.tiktok.feature.chat.PreventMessageRecall
import io.github.nuke.oss.app.tiktok.sdk.chat.ChatAdapterEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Contract tests for anti-recall: the isRecalled answer, the own-message exemption and the row marks.
class PreventMessageRecallTest : HookHarness() {
    class Cell(@JvmField val payload: Any?)
    class Store(private val entries: List<Cell>) { fun entries(): List<Cell> = entries }
    class Holder(@JvmField val itemView: Any)
    class Adapter(@Suppress("UNUSED_PARAMETER") context: Any?, @Suppress("UNUSED_PARAMETER") store: Store?) {
        init { last = this }
        fun onCreateViewHolder(parent: Any?, type: Int): Holder = Holder(Any())
        fun onBindViewHolder(holder: Holder?, position: Int, payloads: List<*>?) {}
        companion object { @JvmStatic var last: Adapter? = null }
    }

    private val marks = RecordingRecallMarks()
    private val isRecalled = method(Message::class.java, "isRecalled")
    private val bind = method(Adapter::class.java, "onBindViewHolder", Holder::class.java, Int::class.javaPrimitiveType!!, List::class.java)
    private val constructor = Adapter::class.java.getDeclaredConstructor(Any::class.java, Store::class.java)

    private fun feature(vararg saved: Pair<String, Any>) = PreventMessageRecall(
        loader, ChatAdapterEvents(loader, Adapter::class.java.name, Message::class.java.name), { marks },
        values(PreventMessageRecall.ID, PreventMessageRecall.OPTIONS, *saved),
    )

    @Test fun `a recalled message is reported as not recalled while enabled`() {
        val feature = feature()
        enable(feature)
        assertEquals(false, backend.call(isRecalled, Message(true)))
        assertEquals(false, backend.call(isRecalled, Message(true, self = true)))
        assertEquals(false, backend.call(isRecalled, Message(false)))
        disable(feature)
        assertEquals(true, backend.call(isRecalled, Message(true)))
        // Without a style no row hook is installed.
        assertEquals(1, backend.attachments.get())
    }

    @Test fun `your own recall goes through when own messages are not kept`() {
        enable(feature(PreventMessageRecall.OWN to false))
        assertEquals(true, backend.call(isRecalled, Message(true, self = true)))
        assertEquals(false, backend.call(isRecalled, Message(true, self = false)))
    }

    @Test fun `rows of kept messages are marked and recycled rows are cleared`() {
        enable(feature(PreventMessageRecall.STYLE to PreventMessageRecall.TINT))
        val kept = Message(true, msgId = 7)
        val plain = Message(false, msgId = 8)
        backend.call(isRecalled, kept)
        backend.call(isRecalled, plain)
        backend.call(constructor, null, null, Store(listOf(Cell(plain), Cell(kept))))
        val adapter = checkNotNull(Adapter.last)
        val row = Any()
        // Positions count back from the newest entry.
        backend.call(bind, adapter, Holder(row), 0, emptyList<Any>())
        assertEquals(mapOf(row to PreventMessageRecall.TINT), marks.marked)
        backend.call(bind, adapter, Holder(row), 1, emptyList<Any>())
        assertTrue(marks.marked.isEmpty())
    }

    @Test fun `the kept list forgets the oldest ids past its capacity`() {
        val kept = KeptMessages(capacity = 2)
        kept.add(1); kept.add(2); kept.add(3)
        assertFalse(1L in kept)
        assertTrue(2L in kept && 3L in kept)
    }
}
